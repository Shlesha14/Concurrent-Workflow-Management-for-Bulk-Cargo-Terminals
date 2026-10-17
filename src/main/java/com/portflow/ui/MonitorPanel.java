package com.portflow.ui;

import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;

/** Live execution monitor: what runs right now, on which worker thread, plus the newest allocation/state events. Polls the database at the configured refresh rate. */
final class MonitorPanel extends UiKit.RefreshablePanel {
    private final UiKit.DataTable active = new UiKit.DataTable(), events = new UiKit.DataTable(), resources = new UiKit.DataTable();
    private final JLabel info = new JLabel();
    private volatile TableData a, e, r; private volatile String summary = "";

    MonitorPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Live Execution Monitor"), BorderLayout.NORTH);
        info.setFont(UiKit.BOLD);
        JPanel top = UiKit.flow(UiKit.button("Start / stop engine", main::toggleEngine), info);
        JPanel left = new JPanel(new GridLayout(2, 1, 0, 6)); left.setOpaque(false);
        left.add(wrap("Running, waiting and ready tasks (worker thread and acquired resources)", active)); left.add(wrap("Resources: capacity, in use, free", resources));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, wrap("Newest events (acquire / release / state changes / alerts)", events)); split.setResizeWeight(0.5); split.setBorder(null);
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.setOpaque(false); body.add(top, BorderLayout.NORTH); body.add(split, BorderLayout.CENTER); add(body, BorderLayout.CENTER);
    }
    private static JPanel wrap(String t, Component c) { JPanel p = new JPanel(new BorderLayout(0, 4)); p.setOpaque(false); JLabel l = new JLabel(t); l.setFont(UiKit.BOLD); p.add(l, BorderLayout.NORTH); p.add(c, BorderLayout.CENTER); return p; }

    @Override void load() throws Exception {
        a = main.ctx.reports.running(); e = main.ctx.reports.recentEvents(60); r = main.ctx.reports.resources();
        var run = main.ctx.engine.runningSnapshot();
        summary = "Engine " + (main.ctx.engine.isRunning() ? "RUNNING" : "stopped") + " | executing now: " + run.size() + " | peak concurrency this session: " + main.ctx.engine.maxConcurrentObserved()
            + " | threads: " + run.stream().map(x -> x.thread()).sorted().toList() + " | lock conflicts: " + main.ctx.resourceManager.stats().conflicts();
    }
    @Override void apply() { active.setData(a); events.setData(e); resources.setData(r); info.setText(summary); }
}
