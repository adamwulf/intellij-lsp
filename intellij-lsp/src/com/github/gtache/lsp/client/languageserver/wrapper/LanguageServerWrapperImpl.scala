/* Adapted from lsp4e */
package com.github.gtache.lsp.client.languageserver.wrapper

import java.io._
import java.net.URI
import java.util.concurrent._
import java.util.{Date, Scanner}

import com.github.gtache.lsp.PluginMain
import com.github.gtache.lsp.client.languageserver.requestmanager.{RequestManager, SimpleRequestManager}
import com.github.gtache.lsp.client.languageserver.serverdefinition.LanguageServerDefinition
import com.github.gtache.lsp.client.languageserver.{LSPServerStatusWidget, ServerOptions, ServerStatus}
import com.github.gtache.lsp.client.{DynamicRegistrationMethods, LanguageClientImpl}
import com.github.gtache.lsp.editor.EditorEventManager
import com.github.gtache.lsp.editor.listeners.{DocumentListenerImpl, EditorMouseListenerImpl, EditorMouseMotionListenerImpl, SelectionListenerImpl}
import com.github.gtache.lsp.requests.{Timeout, Timeouts}
import com.github.gtache.lsp.settings.LSPState
import com.github.gtache.lsp.utils.{ApplicationUtils, FileUtils, LSPException}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.{FileEditorManager, TextEditor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{Either, Message, ResponseErrorCode, ResponseMessage}
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.jetbrains.annotations.Nullable

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

object LanguageServerWrapperImpl {
  private val uriToLanguageServerWrapper: mutable.Map[(String, String), LanguageServerWrapper] = TrieMap()

  /**
    * @param uri A file uri
    * @return The wrapper for the given uri, or None
    */
  def forUri(uri: String, project: Project): Option[LanguageServerWrapper] = {
    uriToLanguageServerWrapper.get(uri, FileUtils.projectToUri(project))
  }

  /**
    * @param editor An editor
    * @return The wrapper for the given editor, or None
    */
  def forEditor(editor: Editor): Option[LanguageServerWrapper] = {
    uriToLanguageServerWrapper.get((FileUtils.editorToURIString(editor), FileUtils.editorToProjectFolderUri(editor)))
  }
}

/**
  * The implementation of a LanguageServerWrapper (specific to a serverDefinition and a project)
  *
  * @param serverDefinition The serverDefinition
  * @param project          The project
  */
class LanguageServerWrapperImpl(val serverDefinition: LanguageServerDefinition, val project: Project) extends LanguageServerWrapper {

  import LanguageServerWrapperImpl._
  import ServerStatus._

  private val toConnect: mutable.Set[Editor] = mutable.Set()
  private val rootPath = project.getBasePath
  private val connectedEditors: mutable.Map[String, EditorEventManager] = mutable.HashMap()
  private val LOG: Logger = Logger.getInstance(classOf[LanguageServerWrapperImpl])
  private val statusWidget: LSPServerStatusWidget = LSPServerStatusWidget.createWidgetFor(this)
  private val registrations: mutable.Map[String, DynamicRegistrationMethods] = mutable.HashMap()
  private var crashCount = 0
  @volatile private var alreadyShownTimeout = false
  @volatile private var alreadyShownCrash = false
  @volatile private var status: ServerStatus = ServerStatus.STOPPED
  private var languageServer: LanguageServer = _
  private var client: LanguageClientImpl = _
  private var requestManager: RequestManager = _
  private var initializeResult: InitializeResult = _
  private var launcherFuture: Future[_] = _
  private var initializeFuture: CompletableFuture[InitializeResult] = _
  private var capabilitiesAlreadyRequested = false
  private var initializeStartTime = 0L
  private var errLogThread: Thread = _

  override def getServerDefinition: LanguageServerDefinition = serverDefinition

  /**
    * @return if the server supports willSaveWaitUntil
    */
  def isWillSaveWaitUntil: Boolean = {
    val capabilities = getServerCapabilities.getTextDocumentSync
    if (capabilities.isLeft) {
      false
    } else {
      capabilities.getRight.getWillSaveWaitUntil
    }
  }

  /**
    * Warning: this is a long running operation
    *
    * @return the languageServer capabilities, or null if initialization job didn't complete
    */
  @Nullable override def getServerCapabilities: ServerCapabilities = {
    if (this.initializeResult != null) this.initializeResult.getCapabilities else {
      try {
        start()
        if (this.initializeFuture != null) this.initializeFuture.get(if (capabilitiesAlreadyRequested) 0 else Timeout.INIT_TIMEOUT, TimeUnit.MILLISECONDS)
        notifySuccess(Timeouts.INIT)
      } catch {
        case e: TimeoutException =>
          notifyFailure(Timeouts.INIT)
          val msg = "LanguageServer for definition\n " + serverDefinition + "\nnot initialized after " + Timeout.INIT_TIMEOUT / 1000 + "s\nCheck settings"
          LOG.warn(msg, e)
          ApplicationUtils.invokeLater(() => if (!alreadyShownTimeout) {
            Messages.showErrorDialog(msg, "LSP error")
            alreadyShownTimeout = true
          })
          stop()

        case e@(_: IOException | _: InterruptedException | _: ExecutionException) =>
          LOG.warn(e)
          stop()
      }
      this.capabilitiesAlreadyRequested = true
      if (initializeResult != null) this.initializeResult.getCapabilities
      else null
    }
  }

  override def notifyResult(timeout: Timeouts, success: Boolean): Unit = {
    statusWidget.notifyResult(timeout, success)
  }

  /**
    * Returns the EditorEventManager for a given uri
    *
    * @param uri the URI as a string
    * @return the EditorEventManager (or null)
    */
  override def getEditorManagerFor(uri: String): EditorEventManager = {
    connectedEditors.get(uri).orNull
  }

  /**
    * @return The request manager for this wrapper
    */
  override def getRequestManager: RequestManager = {
    requestManager
  }

  /**
    * @return whether the underlying connection to language languageServer is still active
    */
  override def isActive: Boolean = this.launcherFuture != null && !this.launcherFuture.isDone && !this.launcherFuture.isCancelled && !alreadyShownTimeout && !alreadyShownCrash

  /**
    * Connects an editor to the languageServer
    *
    * @param editor the editor
    */
  @throws[IOException]
  override def connect(editor: Editor): Unit = {
    if (editor == null) {
      LOG.warn("editor is null for " + serverDefinition)
    } else {
      val uri = FileUtils.editorToURIString(editor)
      uriToLanguageServerWrapper.synchronized {
        uriToLanguageServerWrapper.put((uri, FileUtils.editorToProjectFolderUri(editor)), this)
      }
      if (!this.connectedEditors.contains(uri)) {
        start()
        if (this.initializeFuture != null) {
          val capabilities = getServerCapabilities
          if (capabilities != null) {
            initializeFuture.thenRun(() => {
              if (!this.connectedEditors.contains(uri)) {
                try {
                  val syncOptions: Either[TextDocumentSyncKind, TextDocumentSyncOptions] = if (capabilities == null) null else capabilities.getTextDocumentSync
                  var syncKind: TextDocumentSyncKind = null
                  if (syncOptions != null) {
                    if (syncOptions.isRight) syncKind = syncOptions.getRight.getChange
                    else if (syncOptions.isLeft) syncKind = syncOptions.getLeft
                    val mouseListener = new EditorMouseListenerImpl
                    val mouseMotionListener = new EditorMouseMotionListenerImpl
                    val documentListener = new DocumentListenerImpl
                    val selectionListener = new SelectionListenerImpl
                    val serverOptions = ServerOptions(syncKind, capabilities.getCompletionProvider, capabilities.getSignatureHelpProvider,
                      capabilities.getCodeLensProvider, capabilities.getDocumentOnTypeFormattingProvider, capabilities.getDocumentLinkProvider,
                      capabilities.getExecuteCommandProvider, capabilities.getSemanticHighlighting)
                    val manager = new EditorEventManager(editor, mouseListener, mouseMotionListener, documentListener, selectionListener, requestManager, serverOptions, this)
                    mouseListener.setManager(manager)
                    mouseMotionListener.setManager(manager)
                    documentListener.setManager(manager)
                    selectionListener.setManager(manager)
                    manager.registerListeners()
                    this.connectedEditors.synchronized {
                      this.connectedEditors.put(uri, manager)
                    }
                    manager.documentOpened()
                    LOG.info("Created a manager for " + uri)
                    toConnect.remove(editor)
                    toConnect.foreach(e => connect(e))
                  }
                } catch {
                  case e: Exception => LOG.error(e)
                }
              }

            })
          } else {
            LOG.warn("Capabilities are null for " + serverDefinition)
          }
        } else {
          toConnect.synchronized {
            toConnect.add(editor)
          }
        }
      }
    }
  }

  /**
    * Disconnects an editor from the LanguageServer
    *
    * @param uri The uri of the editor
    */
  override def disconnect(uri: String): Unit = {
    this.connectedEditors.synchronized {
      uriToLanguageServerWrapper.synchronized {
        this.connectedEditors.remove(uri).foreach({ e =>
          uriToLanguageServerWrapper.remove((uri, FileUtils.projectToUri(project)))
          e.removeListeners()
          e.documentClosed()
        })
      }
    }

    if (this.connectedEditors.isEmpty) stop()
  }

  override def stop(): Unit = {
    if (this.initializeFuture != null) {
      if (!this.initializeFuture.isCancelled) this.initializeFuture.cancel(true)
      this.initializeFuture = null
    }
    this.initializeResult = null
    this.capabilitiesAlreadyRequested = false
    if (this.languageServer != null) try {
      val shutdown: CompletableFuture[AnyRef] = this.languageServer.shutdown
      shutdown.get(Timeout.SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS)
      notifySuccess(Timeouts.SHUTDOWN)
    } catch {
      case _: Exception =>
        notifyFailure(Timeouts.SHUTDOWN)
      // most likely closed externally
    }
    if (this.launcherFuture != null) {
      if (!this.launcherFuture.isCancelled) this.launcherFuture.cancel(true)
      this.launcherFuture = null
    }
    if (this.serverDefinition != null) this.serverDefinition.stop(rootPath)
    connectedEditors.foreach(e => disconnect(e._1))
    this.languageServer = null
    setStatus(STOPPED)
    stopLoggingServerErrors()
  }

  /**
    * Checks if the wrapper is already connected to the document at the given path
    */
  override def isConnectedTo(location: String): Boolean = connectedEditors.contains(location)

  /**
    * @return the LanguageServer
    */
  @Nullable override def getServer: LanguageServer = {
    start()
    if (initializeFuture != null && !this.initializeFuture.isDone) this.initializeFuture.join
    this.languageServer
  }

  /**
    * Starts the LanguageServer
    */
  @throws[IOException]
  override def start(): Unit = {
    if (status == STOPPED && !alreadyShownCrash && !alreadyShownTimeout) {
      setStatus(STARTING)
      try {
        val (inputStream, outputStream) = serverDefinition.start(rootPath)
        startLoggingServerErrors()
        client = serverDefinition.createLanguageClient
        val initParams = new InitializeParams
        initParams.setRootUri(FileUtils.pathToUri(rootPath))
        val outWriter = getOutWriter

        val launcher =
          if (LSPState.getInstance().isLoggingServersOutput) LSPLauncher.createClientLauncher(client, inputStream, outputStream, false, outWriter)
          else LSPLauncher.createClientLauncher(client, inputStream, outputStream)

        this.languageServer = launcher.getRemoteProxy
        client.connect(languageServer, this)
        this.launcherFuture = launcher.startListening
        //TODO update capabilities when implemented
        val workspaceClientCapabilities = new WorkspaceClientCapabilities
        workspaceClientCapabilities.setApplyEdit(true)
        //workspaceClientCapabilities.setDidChangeConfiguration(new DidChangeConfigurationCapabilities)
        workspaceClientCapabilities.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities)
        workspaceClientCapabilities.setExecuteCommand(new ExecuteCommandCapabilities)
        val wec = new WorkspaceEditCapabilities
        //TODO set failureHandling and resourceOperations
        wec.setDocumentChanges(true)
        workspaceClientCapabilities.setWorkspaceEdit(wec)
        workspaceClientCapabilities.setSymbol(new SymbolCapabilities)
        workspaceClientCapabilities.setWorkspaceFolders(false)
        workspaceClientCapabilities.setConfiguration(false)
        val textDocumentClientCapabilities = new TextDocumentClientCapabilities
        textDocumentClientCapabilities.setCodeAction(new CodeActionCapabilities)
        //textDocumentClientCapabilities.setCodeLens(new CodeLensCapabilities)
        //textDocumentClientCapabilities.setColorProvider(new ColorProviderCapabilities)
        textDocumentClientCapabilities.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(false)))
        textDocumentClientCapabilities.setDefinition(new DefinitionCapabilities)
        textDocumentClientCapabilities.setDocumentHighlight(new DocumentHighlightCapabilities)
        //textDocumentClientCapabilities.setDocumentLink(new DocumentLinkCapabilities)
        //textDocumentClientCapabilities.setDocumentSymbol(new DocumentSymbolCapabilities)
        //textDocumentClientCapabilities.setFoldingRange(new FoldingRangeCapabilities)
        textDocumentClientCapabilities.setFormatting(new FormattingCapabilities)
        textDocumentClientCapabilities.setHover(new HoverCapabilities)
        //textDocumentClientCapabilities.setImplementation(new ImplementationCapabilities)
        textDocumentClientCapabilities.setOnTypeFormatting(new OnTypeFormattingCapabilities)
        textDocumentClientCapabilities.setRangeFormatting(new RangeFormattingCapabilities)
        textDocumentClientCapabilities.setReferences(new ReferencesCapabilities)
        textDocumentClientCapabilities.setRename(new RenameCapabilities)
        textDocumentClientCapabilities.setSemanticHighlightingCapabilities(new SemanticHighlightingCapabilities(false))
        textDocumentClientCapabilities.setSignatureHelp(new SignatureHelpCapabilities)
        textDocumentClientCapabilities.setSynchronization(new SynchronizationCapabilities(true, true, true))
        //textDocumentClientCapabilities.setTypeDefinition(new TypeDefinitionCapabilities)
        initParams.setCapabilities(new ClientCapabilities(workspaceClientCapabilities, textDocumentClientCapabilities, null))
        initParams.setInitializationOptions(this.serverDefinition.getInitializationOptions(URI.create(initParams.getRootUri)))
        initializeFuture = languageServer.initialize(initParams).thenApply((res: InitializeResult) => {
          initializeResult = res
          LOG.info("Got initializeResult for " + serverDefinition + " ; " + rootPath)
          setStatus(STARTED)
          requestManager = new SimpleRequestManager(this, languageServer, client, res.getCapabilities)
          requestManager.initialized(new InitializedParams())
          res
        })
        initializeStartTime = System.currentTimeMillis
      } catch {
        case e@(_: LSPException | _: IOException) =>
          LOG.warn(e)
          ApplicationUtils.invokeLater(() => Messages.showErrorDialog("Can't start server, please check settings\n" + e.getMessage, "LSP Error"))
          stop()
          removeServerWrapper()
      }
    }
  }

  /**
    * @return The language ID that this wrapper is dealing with if defined in the content type mapping for the language languageServer
    */
  @Nullable override def getLanguageId(contentTypes: Array[String]): String = {
    if (contentTypes.exists(serverDefinition.getMappedExtensions.contains(_))) serverDefinition.id else null
  }

  override def logMessage(message: Message): Unit = {
    message match {
      case responseMessage: ResponseMessage if responseMessage.getError != null && (responseMessage.getId eq Integer.toString(ResponseErrorCode.RequestCancelled.getValue)) =>
        LOG.error(new ResponseErrorException(responseMessage.getError))
      case _ =>
    }
  }

  override def registerCapability(params: RegistrationParams): CompletableFuture[Void] = {
    CompletableFuture.runAsync(() => {
      import scala.collection.JavaConverters._
      params.getRegistrations.asScala.foreach(r => {
        val id = r.getId
        val method = DynamicRegistrationMethods.forName(r.getMethod)
        if(method.isPresent) {
          val options = r.getRegisterOptions
          registrations.put(id, method.get())
        }
      })
    })
  }

  override def unregisterCapability(params: UnregistrationParams): CompletableFuture[Void] = {
    CompletableFuture.runAsync(() => {
      import scala.collection.JavaConverters._
      params.getUnregisterations.asScala.foreach(r => {
        val id = r.getId
        val method = DynamicRegistrationMethods.forName(r.getMethod)
        if(method.isPresent) {
          if (registrations.contains(id)) {
            registrations.remove(id)
          } else {
            val invert = registrations.map(mapping => (mapping._2, mapping._1))
            if (invert.contains(method.get())) {
              registrations.remove(invert(method.get()))
            }
          }
        }
      })
    })
  }

  override def getProject: Project = project

  override def getStatus: ServerStatus = status

  private def setStatus(status: ServerStatus): Unit = {
    this.status = status
    statusWidget.setStatus(status)
  }

  override def crashed(e: Exception): Unit = {
    crashCount += 1
    if (crashCount < 2) {
      val editors = connectedEditors.clone().toMap.keys
      stop()
      editors.foreach(uri => connect(uri))
    } else {
      removeServerWrapper()
      if (!alreadyShownCrash) ApplicationUtils.invokeLater(() => if (!alreadyShownCrash) {
        Messages.showErrorDialog("LanguageServer for definition " + serverDefinition + ", project " + project + " keeps crashing due to \n" + e.getMessage + "\nCheck settings.", "LSP Error")
        alreadyShownCrash = true
      })
    }
  }

  override def getConnectedFiles: Iterable[String] = {
    connectedEditors.keys.map(s => new URI(FileUtils.sanitizeURI(s)).toString)
  }

  override def removeWidget(): Unit = {
    statusWidget.dispose()
  }

  /**
    * Disconnects an editor from the LanguageServer
    *
    * @param editor The editor
    */
  override def disconnect(editor: Editor): Unit = {
    disconnect(FileUtils.editorToURIString(editor))
  }

  private def removeServerWrapper(): Unit = {
    stop()
    removeWidget()
    PluginMain.removeWrapper(this)
  }

  private def connect(uri: String): Unit = {
    val editors = FileEditorManager.getInstance(project).getAllEditors(FileUtils.URIToVFS(uri))
      .collect { case t: TextEditor => t.getEditor }
    if (editors.nonEmpty) {
      connect(editors.head)
    }
  }

  private def startLoggingServerErrors(): Unit = {
    case class ReaderPrinterRunnable(in: InputStream, outPath: String) extends Runnable {
      override def run(): Unit = {
        var notInterrupted = true
        val scanner = new Scanner(in)
        val out = new File(outPath)
        val writer = new BufferedWriter(new FileWriter(out, true))
        while (scanner.hasNextLine && notInterrupted) {
          if (!Thread.currentThread().isInterrupted) {
            writer.write(scanner.nextLine() + "\n")
            writer.flush()
          } else {
            notInterrupted = false
            writer.close()
          }
        }
      }
    }
    val (_, errStream) = serverDefinition.getOutputStreams(rootPath)
    val errRunnable = ReaderPrinterRunnable(errStream, getLogPath("err"))
    errLogThread = new Thread(errRunnable)
    errLogThread.start()
  }

  private def getLogPath(suffix: String): String = {
    val dir = new File(rootPath + "/lsp")
    dir.mkdir()
    import java.text.SimpleDateFormat
    val date = new SimpleDateFormat("yyyyMMdd").format(new Date())
    val basename = rootPath + "/lsp/" + serverDefinition.id.replace(";", "_")
    basename + "_" + suffix + "_" + date + ".log"
  }

  private def getOutWriter: PrintWriter = {
    new PrintWriter(new FileWriter(new File(getLogPath("out")), true))
  }

  private def stopLoggingServerErrors(): Unit = {
    errLogThread.interrupt()
  }
}
