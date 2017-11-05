package com.github.gtache.client.languageserver.serverdefinition

import com.github.gtache.client.connection.{ProcessStreamConnectionProvider, StreamConnectionProvider}

object CommandServerDefinition extends UserConfigurableServerDefinitionObject {
  override def fromArray(arr: Array[String]): UserConfigurableServerDefinition = {
    val raw = RawCommandServerDefinition.fromArray(arr)
    if (raw == null) {
      val exe = ExeLanguageServerDefinition.fromArray(arr)
      exe
    } else {
      raw
    }
  }

  override def getPresentableTyp: String = "Command"

  override def typ = "command"
}

/**
  * A base trait for every command-line server definition
  */
trait CommandServerDefinition extends UserConfigurableServerDefinition {

  def command: Array[String]

  override def createConnectionProvider(workingDir: String): StreamConnectionProvider = {
    if (streamConnectionProvider == null) {
      streamConnectionProvider = new ProcessStreamConnectionProvider(command, workingDir)
    }
    streamConnectionProvider
  }
}
