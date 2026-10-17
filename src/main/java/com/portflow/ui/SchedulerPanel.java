package com.portflow.ui;

import com.portflow.dao.ResourceDao;
import com.portflow.scheduler.PolicyRegistry;
import com.portflow.scheduler.SchedulePlanner;
import com.portflow.scheduler.SchedulingPolicy;
import com.portflow.service.Settings;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Policy selection, schedule preview (non-destructive), conflict detection, revision history and policy comparison on the current data. */
final class SchedulerPanel extends UiKit.RefreshablePanel {
    private final JComboBox<String> policy = new JComboBox<>(PolicyRegistry.names().toArray(new String[0]));
    private final JLabel desc = new JLabel();
    private final UiKit.DataTable plan = new UiKit.DataTable(), revisions = new UiKit.DataTable(), compare = new UiKit.DataTable();
    private final JTextArea notes = new JTextArea(5, 40);
    private volatile TableData rev;
    private SchedulePlanner.Plan lastPlan;

    SchedulerPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Scheduler and Allocation"), BorderLayout.NORTH);
        policy.setSelectedItem(main.ctx.policy().name());
        policy.addActionListener(e -> { main.ctx.setPolicy((String) policy.getSelectedItem()); desc.setText(PolicyRegistry.get((String) policy.getSelectedItem()).description()); });
        desc.setText(main.ctx.policy().description());
        JPanel top = UiKit.flow(new JLabel("Policy:"), policy, desc, UiKit.button("Preview schedule", this::preview), UiKit.button("Save preview as revision", this::save), UiKit.button("Compare all policies (preview)", this::comparePolicies));
        notes.setEditable(false); notes.setLineWrap(true); notes.setWrapStyleWord(true); notes.setFont(UiKit.BASE); notes.setBorder(BorderFactory.createTitledBorder("Conflicts, deadline risks, tasks that cannot be placed"));
        revisions.table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && revisions.selectedId() != null) showRevision(revisions.selectedId()); });
        JPanel planPanel = new JPanel(new BorderLayout()); planPanel.setOpaque(false); planPanel.add(label("Proposed / selected schedule (task -> resource, simulated times)"), BorderLayout.NORTH); planPanel.add(plan, BorderLayout.CENTER); planPanel.add(notes, BorderLayout.SOUTH);
        JPanel right = new JPanel(new GridLayout(2, 1, 0, 6)); right.setOpaque(false);
        JPanel rp = new JPanel(new BorderLayout()); rp.setOpaque(false); rp.add(label("Schedule revision history (select one to see its plan)"), BorderLayout.NORTH); rp.add(revisions, BorderLayout.CENTER);
        JPanel cp = new JPanel(new BorderLayout()); cp.setOpaque(false); cp.add(label("Policy comparison on the SAME current data (planned, not executed)"), BorderLayout.NORTH); cp.add(compare, BorderLayout.CENTER);
        right.add(rp); right.add(cp);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, planPanel, right); split.setResizeWeight(0.5); split.setBorder(null);
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.setOpaque(false); body.add(top, BorderLayout.NORTH); body.add(split, BorderLayout.CENTER); add(body, BorderLayout.CENTER);
    }
    private static JLabel label(String t) { JLabel l = new JLabel(t); l.setFont(UiKit.BOLD); return l; }

    private static TableData planTable(SchedulePlanner.Plan p) {
        List<List<Object>> rows = new ArrayList<>();
        for (SchedulePlanner.Entry e : p.entries()) rows.add(List.of(e.taskId(), e.step(), e.resourceName(), UiKit.fmt(e.start()), UiKit.fmt(e.end())));
        return new TableData(List.of("task", "step", "resource", "start", "end"), rows);
    }
    private static String notesText(SchedulePlanner.Plan p) {
        StringBuilder sb = new StringBuilder("Conflicts: " + (p.conflicts().isEmpty() ? "none" : p.conflicts()) + "\n");
        sb.append("Deadline / expiry risks: ").append(p.deadlineRisks().isEmpty() ? "none" : "\n  " + String.join("\n  ", p.deadlineRisks())).append("\n");
        sb.append("Cannot be placed: ").append(p.unscheduled().isEmpty() ? "none" : "\n  " + String.join("\n  ", p.unscheduled()));
        return sb.toString();
    }

    private SchedulePlanner.Plan plan(SchedulingPolicy sp, java.sql.Connection c) throws Exception { return new SchedulePlanner().preview(c, sp, Settings.load(c), main.ctx.now()); }

    private void preview() {
        SchedulingPolicy sp = PolicyRegistry.get((String) policy.getSelectedItem());
        UiKit.async(() -> main.ctx.db.read(c -> { try { return plan(sp, c); } catch (java.sql.SQLException e) { throw e; } catch (Exception e) { throw new java.sql.SQLException(e); } }),
            p -> { lastPlan = p; plan.setData(planTable(p)); notes.setText(notesText(p)); }, msg -> UiKit.error(this, msg));
    }
    private void save() {
        if (lastPlan == null) { UiKit.info(this, "Press 'Preview schedule' first."); return; }
        SchedulePlanner.Plan p = lastPlan;
        UiKit.async(() -> main.ctx.db.inTransaction(c -> new SchedulePlanner().save(c, p, "MANUAL_PLAN", "Operator saved preview (" + p.policy() + ")", null, main.ctx.now())), id -> { UiKit.info(this, "Saved as revision #" + id); refresh(); }, msg -> UiKit.error(this, msg));
    }
    private void showRevision(long id) { UiKit.async(() -> main.ctx.reports.planEntries(id), plan::setData, msg -> UiKit.error(this, msg)); }
    private void comparePolicies() {
        UiKit.async(() -> main.ctx.db.read(c -> {
            List<List<Object>> rows = new ArrayList<>();
            try {
                for (SchedulingPolicy sp : PolicyRegistry.all()) {
                    SchedulePlanner.Plan p = plan(sp, c);
                    java.time.LocalDateTime end = p.entries().stream().map(SchedulePlanner.Entry::end).max(java.time.LocalDateTime::compareTo).orElse(main.ctx.now());
                    rows.add(List.of(sp.name(), (int) p.entries().stream().map(SchedulePlanner.Entry::taskId).distinct().count(), Math.round(java.time.Duration.between(main.ctx.now(), end).toMinutes()), p.deadlineRisks().size(), p.unscheduled().size(), p.conflicts().size()));
                }
            } catch (java.sql.SQLException e) { throw e; } catch (Exception e) { throw new java.sql.SQLException(e); }
            return new TableData(List.of("policy", "tasks planned", "planned makespan (min)", "deadline risks", "unplaceable", "conflicts"), rows);
        }), compare::setData, msg -> UiKit.error(this, msg));
    }

    @Override void load() throws Exception { rev = main.ctx.reports.revisions(); }
    @Override void apply() { revisions.setData(rev); }
}
