package com.portflow.ui;

import com.portflow.model.Resource;
import com.portflow.service.ResourceService;
import com.portflow.service.ServiceException;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

final class ResourcePanel extends UiKit.RefreshablePanel {
    private final JTextField name = UiKit.field(12), cap = UiKit.field(8), loc = UiKit.field(12), tMin = UiKit.field(5), tMax = UiKit.field(5), caps = UiKit.field(18);
    private final JComboBox<String> type = new JComboBox<>();
    private final UiKit.DataTable table = new UiKit.DataTable(), allocs = new UiKit.DataTable();
    private volatile TableData data, alloc; private volatile List<String> types = List.of();

    ResourcePanel(MainFrame m) {
        super(m);
        add(UiKit.title("Resource Management"), BorderLayout.NORTH);
        JPanel form = UiKit.flow(new JLabel("Name"), name, new JLabel("Type"), type, new JLabel("Capacity"), cap, new JLabel("Location"), loc, new JLabel("Temp min/max C"), tMin, tMax, new JLabel("Capabilities (comma)"), caps,
            UiKit.button("Add", this::add), UiKit.button("Update selected", this::update));
        JPanel actions = UiKit.flow(new JLabel("Selected resource:"), UiKit.button("Set AVAILABLE", () -> status("AVAILABLE")), UiKit.button("Simulate MAINTENANCE", () -> status("MAINTENANCE")), UiKit.button("Simulate FAILURE (UNAVAILABLE)", () -> status("UNAVAILABLE")));
        JPanel north = new JPanel(new GridLayout(2, 1)); north.setOpaque(false); north.add(form); north.add(actions);
        JPanel center = new JPanel(new GridLayout(2, 1, 0, 8)); center.setOpaque(false);
        center.add(table);
        JPanel a = new JPanel(new BorderLayout()); a.setOpaque(false); JLabel l = new JLabel("Allocations (history is never deleted: ACTIVE -> HELD -> RELEASED / REVOKED)"); l.setFont(UiKit.BOLD); a.add(l, BorderLayout.NORTH); a.add(allocs, BorderLayout.CENTER); center.add(a);
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.setOpaque(false); body.add(north, BorderLayout.NORTH); body.add(center, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        table.table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) fillFromSelection(); });
    }

    private void fillFromSelection() {
        if (table.selected(0) == null) return;
        name.setText(table.selected(1)); cap.setText(table.selected(3)); loc.setText(table.selected(7) == null ? "" : table.selected(7));
        String t = table.selected(8); if (t != null && t.contains("..")) { String[] p = t.replace(" C", "").split("\\.\\."); tMin.setText(p[0]); tMax.setText(p[1]); } else { tMin.setText(""); tMax.setText(""); }
        caps.setText(table.selected(9) == null ? "" : table.selected(9));
    }
    private Double d(JTextField f) { return f.getText().isBlank() ? null : Double.valueOf(f.getText().trim()); }
    private List<String> capList() { return caps.getText().isBlank() ? List.of() : Arrays.stream(caps.getText().split(",")).map(String::trim).toList(); }

    private void add() {
        try {
            String n = name.getText(), t = (String) type.getSelectedItem(); BigDecimal c = new BigDecimal(cap.getText().trim()); Double a = d(tMin), b = d(tMax);
            run(() -> { main.ctx.resources.add(n, t, c, loc.getText(), a, b, capList()); return "Resource added"; });
        } catch (NumberFormatException e) { UiKit.error(this, "Capacity and temperatures must be numbers."); }
    }
    private void update() {
        Long id = table.selectedId(); if (id == null) { UiKit.info(this, "Select a resource first."); return; }
        try {
            BigDecimal c = new BigDecimal(cap.getText().trim()); Double a = d(tMin), b = d(tMax);
            run(() -> { main.ctx.resources.update(id.intValue(), name.getText(), c, loc.getText(), a, b, capList()); return "Resource updated"; });
        } catch (NumberFormatException e) { UiKit.error(this, "Capacity and temperatures must be numbers."); }
    }
    private void status(String s) {
        Long id = table.selectedId(); if (id == null) { UiKit.info(this, "Select a resource first."); return; }
        String reason = "AVAILABLE".equals(s) ? "operator restored" : JOptionPane.showInputDialog(this, "Reason (optional):", "simulated " + s.toLowerCase());
        if (reason == null) return;
        run(() -> { ResourceService.StatusChange c = main.ctx.resources.setStatus(id.intValue(), s, reason);
            return "Status -> " + s + "\nBlocked tasks: " + c.blockedTasks() + ", recovered: " + c.recoveredTasks() + ", stored-cargo holds revoked: " + c.revokedHolds() + ", running tasks allowed to finish: " + c.runningDrained() + (c.revisionId() > 0 ? "\nSchedule revision #" + c.revisionId() + " recorded" : ""); });
    }
    private void run(java.util.concurrent.Callable<String> work) {
        UiKit.async(() -> { try { return work.call(); } catch (ServiceException e) { return "REFUSED: " + e.getMessage(); } }, msg -> { UiKit.info(this, msg); refresh(); }, msg -> UiKit.error(this, msg));
    }

    @Override void load() throws Exception { data = main.ctx.reports.resources(); alloc = main.ctx.reports.allocations(200); types = main.ctx.resources.types(); }
    @Override void apply() { if (type.getItemCount() == 0) types.forEach(type::addItem); table.setData(data); allocs.setData(alloc); }
}
