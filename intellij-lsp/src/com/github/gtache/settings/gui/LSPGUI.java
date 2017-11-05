package com.github.gtache.settings.gui;

import com.github.gtache.client.MessageDialog;
import com.github.gtache.client.languageserver.serverdefinition.*;
import com.github.gtache.settings.LSPState;
import com.github.gtache.utils.Utils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.*;
import java.util.List;

/**
 * The GUI for the LSP settings
 */
public class LSPGUI {

    private static final String EXT = "ext";
    private static final String MAINCLASS = "mainclass";
    private static final String ARGS = "args";
    private static final String PACKGE = "packge";
    private static final String COMMAND = "command";
    private static final String PATH = "path";
    private static final Logger LOG = Logger.getInstance(LSPGUI.class);
    private final LSPState state;
    private final JPanel rootPanel;
    private final List<LSPGUIRow> rows = new ArrayList<>();
    private final Map<String, UserConfigurableServerDefinition> serverDefinitions = new LinkedHashMap<>();

    public LSPGUI() {
        state = LSPState.getInstance();
        rootPanel = new JPanel();
        rootPanel.setLayout(new BoxLayout(rootPanel, BoxLayout.Y_AXIS));
        rootPanel.add(createArtifactRow("", "", "", ""));
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    public Collection<LSPGUIRow> getRows() {
        return rows;
    }

    public void clear() {
        rows.clear();
        serverDefinitions.clear();
        rootPanel.removeAll();
        rootPanel.validate();
        rootPanel.repaint();
    }

    public void addServerDefinition(UserConfigurableServerDefinition serverDefinition) {
        if (serverDefinition != null) {
            serverDefinitions.put(serverDefinition.ext(), serverDefinition);
            if (serverDefinition.getClass().equals(ArtifactLanguageServerDefinition.class)) {
                final ArtifactLanguageServerDefinition def = (ArtifactLanguageServerDefinition) serverDefinition;
                rootPanel.add(createArtifactRow(def.ext(), def.packge(), def.mainClass(), Utils.arrayToString(def.args(), " ")));
            } else if (serverDefinition.getClass().equals(ExeLanguageServerDefinition.class)) {
                final ExeLanguageServerDefinition def = (ExeLanguageServerDefinition) serverDefinition;
                rootPanel.add(createExeRow(def.ext(), def.path(), Utils.arrayToString(def.args(), " ")));
            } else if (serverDefinition.getClass().equals(RawCommandServerDefinition.class)) {
                final RawCommandServerDefinition def = (RawCommandServerDefinition) serverDefinition;
                rootPanel.add(createCommandRow(def.ext(), Utils.arrayToString(def.command(), " ")));
            } else {
                LOG.error("Unknown UserConfigurableServerDefinition : " + serverDefinition);
            }
        }
    }

    public void apply() {
        MessageDialog.main("The changes will be applied after restarting the IDE.");
        serverDefinitions.clear();
        for (final LSPGUIRow row : rows) {
            final String[] arr = row.toStringArray();
            final String ext = row.getText(EXT);
            final UserConfigurableServerDefinition serverDefinition = UserConfigurableServerDefinition$.MODULE$.fromArray(arr);
            if (serverDefinition != null) {
                serverDefinitions.put(ext, serverDefinition);
            }
        }
        LSPState.getInstance().setExtToServ(serverDefinitions);
    }

    public boolean isModified() {
        if (serverDefinitions.size() == rows.size()) {
            for (final LSPGUIRow row : rows) {
                final UserConfigurableServerDefinition stateDef = serverDefinitions.get(row.getText(EXT));
                final UserConfigurableServerDefinition rowDef = UserConfigurableServerDefinition$.MODULE$.fromArray(row.toStringArray());
                if (rowDef != null && !rowDef.equals(stateDef)) {
                    return true;
                }
            }
            return false;
        } else {
            return true;
        }
    }

    public void reset() {
        this.clear();
        if (state.getExtToServ() != null) {
            for (UserConfigurableServerDefinition serverDefinition : state.getExtToServ().values()) {
                addServerDefinition(serverDefinition);
            }
        }
    }

    private JComboBox<String> createComboBox(final JPanel panel, final String selected) {
        final JComboBox<String> typeBox = new ComboBox<>();
        final ConfigurableTypes[] types = ConfigurableTypes.values();
        for (final ConfigurableTypes type : types) {
            typeBox.addItem(type.getTyp());
        }
        typeBox.setSelectedItem(selected);
        typeBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                final int idx = getComponentIndex(panel);
                if (e.getItem().equals(ConfigurableTypes.ARTIFACT.getTyp())) {
                    rootPanel.add(createArtifactRow("", "", "", ""), idx);
                    rootPanel.remove(panel);
                    rows.remove(idx);
                } else if (e.getItem().equals(ConfigurableTypes.RAWCOMMAND.getTyp())) {
                    rootPanel.add(createCommandRow("", ""), idx);
                    rootPanel.remove(panel);
                    rows.remove(idx);
                } else if (e.getItem().equals(ConfigurableTypes.EXE.getTyp())) {
                    rootPanel.add(createExeRow("", "", ""), idx);
                    rootPanel.remove(panel);
                    rows.remove(idx);
                } else {
                    LOG.error("Unknown type : " + e.getItem());
                }
            }
        });
        return typeBox;
    }

    private JButton createNewRowButton() {
        final JButton newRowButton = new JButton();
        newRowButton.setText("+");
        newRowButton.addActionListener(e -> rootPanel.add(createArtifactRow("", "", "", "")));
        return newRowButton;
    }

    private JButton createRemoveRowButton(final JPanel panel) {
        final JButton removeRowButton = new JButton();
        removeRowButton.setText("-");
        removeRowButton.addActionListener(e -> {
            final int idx = getComponentIndex(panel);
            rootPanel.remove(panel);
            rows.remove(idx);
        });
        return removeRowButton;
    }

    private JPanel createArtifactRow(final String ext, final String serv, final String mainClass, final String args) {
        final JPanel panel = new JPanel();
        panel.setLayout(new GridLayoutManager(2, 18, JBUI.emptyInsets(), -1, -1));

        panel.add(new Spacer(), new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        panel.add(new Spacer(), new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JComboBox<String> typeBox = createComboBox(panel, ArtifactLanguageServerDefinition$.MODULE$.getPresentableTyp());
        panel.add(typeBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Extension"), new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField extField = new JTextField();
        extField.setToolTipText("e.g. scala, java, c, js, ...");
        extField.setText(ext);
        panel.add(extField, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 5, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Artifact"), new GridConstraints(0, 6, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField packgeField = new JTextField();
        packgeField.setToolTipText("e.g. ch.epfl.lamp:dotty-language-server_0.3:0.3.0-RC2");
        packgeField.setText(serv);
        panel.add(packgeField, new GridConstraints(0, 7, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 8, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Main class"), new GridConstraints(0, 9, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField mainClassField = new JTextField();
        mainClassField.setToolTipText("e.g. dotty.tools.languageserver.Main");
        mainClassField.setText(mainClass);
        panel.add(mainClassField, new GridConstraints(0, 10, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 11, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Args"), new GridConstraints(0, 12, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField argsField = new JTextField();
        argsField.setToolTipText("e.g. -stdio");
        argsField.setText(args);
        panel.add(argsField, new GridConstraints(0, 13, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 14, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JButton newRowButton = createNewRowButton();
        panel.add(newRowButton, new GridConstraints(0, 15, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        if (!rows.isEmpty()) {
            final JButton removeRowButton = createRemoveRowButton(panel);
            panel.add(removeRowButton, new GridConstraints(0, 16, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        }

        panel.add(new Spacer(), new GridConstraints(0, 17, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final scala.collection.mutable.LinkedHashMap<String, JComponent> map = new scala.collection.mutable.LinkedHashMap<>();
        map.put(EXT, extField);
        map.put(PACKGE, packgeField);
        map.put(MAINCLASS, mainClassField);
        map.put(ARGS, argsField);
        rows.add(new LSPGUIRow(panel, ArtifactLanguageServerDefinition$.MODULE$.typ(), map));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel createExeRow(final String ext, final String path, final String args) {
        final JPanel panel = new JPanel();
        panel.setLayout(new GridLayoutManager(2, 15, JBUI.emptyInsets(), -1, -1));
        panel.add(new Spacer(), new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        panel.add(new Spacer(), new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JComboBox<String> typeBox = createComboBox(panel, ExeLanguageServerDefinition$.MODULE$.getPresentableTyp());
        panel.add(typeBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Extension"), new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField extField = new JTextField();
        extField.setToolTipText("e.g. scala, java, c, js, ...");
        extField.setText(ext);
        panel.add(extField, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 5, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Path"), new GridConstraints(0, 6, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField pathField = new JTextField();
        pathField.setToolTipText("e.g. C:\\rustLS\\rls.exe");
        pathField.setText(path);
        panel.add(pathField, new GridConstraints(0, 7, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 8, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Args"), new GridConstraints(0, 9, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField argsField = new JTextField();
        argsField.setToolTipText("e.g. -stdio");
        argsField.setText(args);
        panel.add(argsField, new GridConstraints(0, 10, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 11, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JButton newRowButton = createNewRowButton();
        panel.add(newRowButton, new GridConstraints(0, 12, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        if (!rows.isEmpty()) {
            final JButton removeRowButton = createRemoveRowButton(panel);
            panel.add(removeRowButton, new GridConstraints(0, 13, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        }
        panel.add(new Spacer(), new GridConstraints(0, 14, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));

        final scala.collection.mutable.LinkedHashMap<String, JComponent> map = new scala.collection.mutable.LinkedHashMap<>();
        map.put(EXT, extField);
        map.put(PATH, pathField);
        map.put(ARGS, argsField);
        rows.add(new LSPGUIRow(panel, ExeLanguageServerDefinition$.MODULE$.typ(), map));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel createCommandRow(final String ext, final String command) {
        final JPanel panel = new JPanel();
        panel.setLayout(new GridLayoutManager(2, 12, JBUI.emptyInsets(), -1, -1));
        panel.add(new Spacer(), new GridConstraints(1, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        panel.add(new Spacer(), new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JComboBox<String> typeBox = createComboBox(panel, RawCommandServerDefinition$.MODULE$.getPresentableTyp());
        panel.add(typeBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Extension"), new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField extField = new JTextField();
        extField.setToolTipText("e.g. scala, java, c, js, ...");
        extField.setText(ext);
        panel.add(extField, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 5, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        panel.add(new JLabel("Command"), new GridConstraints(0, 6, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JTextField commandField = new JTextField();
        commandField.setText(command);
        commandField.setToolTipText("e.g. python.exe -m C:\\python-ls\\pyls");
        panel.add(commandField, new GridConstraints(0, 7, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));

        panel.add(new Spacer(), new GridConstraints(0, 8, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JButton newRowButton = createNewRowButton();
        panel.add(newRowButton, new GridConstraints(0, 9, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        if (!rows.isEmpty()) {
            final JButton removeRowButton = createRemoveRowButton(panel);
            panel.add(removeRowButton, new GridConstraints(0, 10, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        }
        panel.add(new Spacer(), new GridConstraints(0, 11, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));

        final scala.collection.mutable.LinkedHashMap<String, JComponent> map = new scala.collection.mutable.LinkedHashMap<>();
        map.put(EXT, extField);
        map.put(COMMAND, commandField);
        rows.add(new LSPGUIRow(panel, RawCommandServerDefinition$.MODULE$.typ(), map));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private int getComponentIndex(final JComponent component) {
        for (int i = 0; i < rootPanel.getComponentCount(); ++i) {
            if (rootPanel.getComponent(i).equals(component)) {
                return i;
            }
        }
        return -1;
    }
}
