package com.portflow.ui;

import com.portflow.service.ServiceException;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;

final class WorkflowPanel extends UiKit.RefreshablePanel {
    private final UiKit.DataTable tasks = new UiKit.DataTable(), deps = new UiKit.DataTable();
    private final JTextArea why = new JTextArea(3, 40);
    private volatile TableData t, d;

    WorkflowPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Workflows and Tasks"), BorderLayout.NORTH);
        JPanel actions = UiKit.flow(UiKit.button("Retry selected", () -> act("retry")), UiKit.button("Cancel selected", () -> act("cancel")), UiKit.button("Cancel whole workflow of selected", () -> act("cancelWf")),
            UiKit.button("Add dependency...", this::addDependency));
        why.setEditable(false); why.setLineWrap(true); why.setWrapStyleWord(true); why.setFont(UiKit.BASE); why.setBorder(BorderFactory.createTitledBorder("Why is the selected task not running?"));
        tasks.table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) explain(); });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tasks, wrapDeps()); split.setResizeWeight(0.65); split.setBorder(null);
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.setOpaque(false); body.add(actions, BorderLayout.NORTH); body.add(split, BorderLayout.CENTER); body.add(why, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);
    }
    private JPanel wrapDeps() { JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false); JLabel l = new JLabel("Dependencies (a task starts only when every prerequisite is COMPLETED)"); l.setFont(UiKit.BOLD); p.add(l, BorderLayout.NORTH); p.add(deps, BorderLayout.CENTER); return p; }

    private void explain() {
        String state = tasks.selected(5), code = tasks.selected(6), reason = tasks.selected(7);
        if (state == null) { why.setText(""); return; }
        String text = switch (state) {
            case "BLOCKED" -> "BLOCKED (" + code + "): " + reason;
            case "FAILED" -> "FAILED: " + (reason == null ? "execution failed; see Activity Log" : reason) + " - use Retry after fixing the cause.";
            case "WAITING_FOR_RESOURCE" -> "Waiting: all compatible resources are currently full (ordinary contention). It will run when capacity is released or time out after the profile's maximum wait.";
            case "READY" -> "Ready: prerequisites done; waiting for the scheduler to offer it resources.";
            case "VALIDATED" -> "Validated: waiting for prerequisite tasks (see Dependencies) or for the cargo's arrival time.";
            case "RUNNING" -> "Running on " + tasks.selected(13);
            case "COMPLETED" -> "Completed" + ("true".equalsIgnoreCase(tasks.selected(14)) || "1".equals(tasks.selected(14)) ? " (deadline missed)" : "");
            default -> state;
        };
        why.setText(text);
    }

    private void act(String what) {
        Long id = tasks.selectedId(); if (id == null) { UiKit.info(this, "Select a task first."); return; }
        UiKit.async(() -> {
            try {
                switch (what) {
                    case "retry" -> main.ctx.admin.retry(id);
                    case "cancel" -> main.ctx.admin.cancel(id);
                    default -> main.ctx.admin.cancelWorkflow(Long.parseLong(tasks.selected(1)));
                }
                return "Done.";
            } catch (ServiceException e) { return "Refused: " + e.getMessage(); }
        }, msg -> { UiKit.info(this, msg); refresh(); }, msg -> UiKit.error(this, msg));
    }

    private void addDependency() {
        String a = JOptionPane.showInputDialog(this, "Task id that must WAIT:"), b = a == null ? null : JOptionPane.showInputDialog(this, "...for this prerequisite task id:");
        if (a == null || b == null) return;
        UiKit.async(() -> {
            try { main.ctx.workflows.addDependency(Long.parseLong(a.trim()), Long.parseLong(b.trim())); return "Dependency added."; }
            catch (ServiceException e) { return "Rejected: " + e.getMessage() + " " + e.details(); }
            catch (NumberFormatException e) { return "Task ids must be numbers."; }
        }, msg -> { UiKit.info(this, msg); refresh(); }, msg -> UiKit.error(this, msg));
    }

    @Override void load() throws Exception { t = main.ctx.reports.tasks(); d = main.ctx.reports.dependencies(); }
    @Override void apply() { tasks.setData(t); deps.setData(d); explain(); }
}
