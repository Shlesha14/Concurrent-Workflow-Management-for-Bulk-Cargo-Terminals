package com.portflow.ui;

import com.portflow.model.EventType;
import com.portflow.model.TaskState;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Filterable, append-only audit history. Filters are bound parameters (no SQL text from the UI). Auto-refresh is OFF here so filtered results stay put. */
final class ActivityPanel extends UiKit.RefreshablePanel {
    private final JTextField task = UiKit.field(6), cargo = UiKit.field(6), resource = UiKit.field(6), from = UiKit.field(12), to = UiKit.field(12);
    private final JComboBox<String> type = new JComboBox<>(), state = new JComboBox<>();
    private final UiKit.DataTable table = new UiKit.DataTable();
    private volatile TableData data;
    private boolean first = true;

    ActivityPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Activity Log and Audit History"), BorderLayout.NORTH);
        type.addItem(""); for (EventType t : EventType.values()) type.addItem(t.name());
        state.addItem(""); for (TaskState s : TaskState.values()) state.addItem(s.name());
        JPanel f = UiKit.flow(new JLabel("Task"), task, new JLabel("Cargo"), cargo, new JLabel("Resource"), resource, new JLabel("Type"), type, new JLabel("State"), state,
            new JLabel("From"), from, new JLabel("To"), to, UiKit.button("Search", () -> { first = true; refresh(); }), UiKit.button("Export CSV", this::export));
        JPanel t = new JPanel(new BorderLayout()); t.setOpaque(false); t.add(UiKit.title("Activity Log and Audit History"), BorderLayout.NORTH); t.add(f, BorderLayout.CENTER);
        add(t, BorderLayout.NORTH); add(table, BorderLayout.CENTER);
    }
    private static Long l(JTextField f) { return f.getText().isBlank() ? null : Long.valueOf(f.getText().trim()); }

    @Override void load() throws Exception {
        if (!first) return;
        try {
            Long tk = l(task), cg = l(cargo), rs = l(resource);
            var fr = UiKit.parse(from.getText()); var tt = UiKit.parse(to.getText());
            data = main.ctx.db.read(c -> com.portflow.dao.EventLog.search(c, tk, cg, rs == null ? null : rs.intValue(), (String) type.getSelectedItem(), (String) state.getSelectedItem(), fr, tt, 1000));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Task, cargo and resource filters must be numbers."); }
    }
    @Override void apply() { if (first && data != null) { table.setData(data); first = false; } }

    private void export() {
        if (data == null) return;
        JFileChooser fc = new JFileChooser(); fc.setSelectedFile(new java.io.File("portflow_activity.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) try { Files.writeString(Path.of(fc.getSelectedFile().toURI()), data.toCsv()); UiKit.info(this, "Saved " + fc.getSelectedFile()); } catch (Exception e) { UiKit.error(this, e.getMessage()); }
    }
}
