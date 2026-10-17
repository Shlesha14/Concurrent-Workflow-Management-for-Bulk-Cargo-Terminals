package com.portflow.ui;

import com.portflow.dao.ReportDao.Dashboard;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;

final class DashboardPanel extends UiKit.RefreshablePanel {
    private final JLabel[] values = new JLabel[12];
    private final String[] names = {"Cargo items", "Waiting tasks", "Running tasks", "Completed tasks", "Failed tasks", "Blocked tasks",
        "Resources available", "Resources in use", "Resources down", "Urgent (<= 2 h)", "Expired / rejected", "Active workflows"};
    private final JProgressBar util = new JProgressBar(0, 100);
    private final UiKit.DataTable recent = new UiKit.DataTable(), alerts = new UiKit.DataTable();
    private volatile Dashboard d; private volatile TableData ev, al;

    DashboardPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Dashboard"), BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(2, 6, 10, 10)); grid.setOpaque(false);
        for (int i = 0; i < 12; i++) {
            JPanel c = new JPanel(new BorderLayout()); c.setBackground(UiKit.CARD); c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UiKit.GRID), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            values[i] = new JLabel("-"); values[i].setFont(UiKit.TITLE.deriveFont(26f)); values[i].setForeground(UiKit.NAVY);
            JLabel n = new JLabel(names[i]); n.setFont(UiKit.BASE); n.setForeground(Color.GRAY);
            c.add(values[i], BorderLayout.CENTER); c.add(n, BorderLayout.SOUTH); grid.add(c);
        }
        util.setStringPainted(true); util.setForeground(UiKit.TEAL);
        JPanel top = new JPanel(new BorderLayout(0, 8)); top.setOpaque(false);
        top.add(grid, BorderLayout.CENTER);
        JPanel ub = new JPanel(new BorderLayout(8, 0)); ub.setOpaque(false); ub.add(new JLabel("Slot-resource utilisation (now):"), BorderLayout.WEST); ub.add(util, BorderLayout.CENTER);
        top.add(ub, BorderLayout.SOUTH);
        top.setPreferredSize(new Dimension(0, 190));
        JPanel tables = new JPanel(new GridLayout(1, 2, 10, 0)); tables.setOpaque(false);
        tables.add(wrap("Recent activity", recent)); tables.add(wrap("Alerts and conflicts (blocked / failed / excursions / deadline misses)", alerts));
        JPanel body = new JPanel(new BorderLayout(0, 10)); body.setOpaque(false); body.add(top, BorderLayout.NORTH); body.add(tables, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
    }
    private static JPanel wrap(String t, Component c) { JPanel p = new JPanel(new BorderLayout(0, 4)); p.setOpaque(false); JLabel l = new JLabel(t); l.setFont(UiKit.BOLD); p.add(l, BorderLayout.NORTH); p.add(c, BorderLayout.CENTER); return p; }

    @Override void load() throws Exception { d = main.ctx.reports.dashboard(); ev = main.ctx.reports.recentEvents(25); al = main.ctx.reports.alerts(); }
    @Override void apply() {
        Dashboard x = d;
        int[] v = {x.cargoTotal(), x.waiting(), x.running(), x.completed(), x.failed(), x.blocked(), x.resourcesAvailable(), x.resourcesOccupied(), x.resourcesDown(), x.urgentCargo(), x.expiredCargo(), x.activeWorkflows()};
        for (int i = 0; i < 12; i++) values[i].setText(String.valueOf(v[i]));
        values[4].setForeground(x.failed() > 0 ? UiKit.BAD : UiKit.NAVY); values[5].setForeground(x.blocked() > 0 ? UiKit.WARN : UiKit.NAVY);
        util.setValue((int) Math.round(x.utilizationPct())); util.setString(String.format("%.0f %%", x.utilizationPct()));
        recent.setData(ev); alerts.setData(al);
    }
}
