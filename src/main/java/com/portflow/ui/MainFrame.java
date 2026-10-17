package com.portflow.ui;

import com.portflow.config.PortFlowContext;
import com.portflow.scheduler.PolicyRegistry;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Main window: navigation, engine controls, status bar and the periodic (bounded, configurable) refresh of the visible screen. */
public final class MainFrame extends JFrame {
    final PortFlowContext ctx;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<String, UiKit.RefreshablePanel> panels = new LinkedHashMap<>();
    private final JLabel status = new JLabel(" "), clockLabel = new JLabel(), engineLabel = new JLabel();
    private final JButton engineButton = new JButton("Start engine");
    private final JComboBox<String> policyBox = new JComboBox<>(PolicyRegistry.names().toArray(new String[0]));
    private final JList<String> nav;
    private Timer timer;
    private String current = "Dashboard";

    public MainFrame(PortFlowContext ctx) {
        super("PortFlow - Concurrent Workflow Management for Bulk Cargo Terminals");
        this.ctx = ctx;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent e) { shutdown(); } });
        panels.put("Dashboard", new DashboardPanel(this));
        panels.put("Cargo", new CargoPanel(this));
        panels.put("Resources", new ResourcePanel(this));
        panels.put("Workflows & Tasks", new WorkflowPanel(this));
        panels.put("Scheduler", new SchedulerPanel(this));
        panels.put("Live Monitor", new MonitorPanel(this));
        panels.put("Activity Log", new ActivityPanel(this));
        panels.put("Simulation", new SimulationPanel(this));
        panels.put("Settings", new SettingsPanel(this));
        panels.forEach((k, v) -> content.add(v, k));

        nav = new JList<>(panels.keySet().toArray(new String[0]));
        nav.setFont(UiKit.BASE.deriveFont(14f)); nav.setFixedCellHeight(34); nav.setBackground(UiKit.NAVY); nav.setForeground(Color.WHITE);
        nav.setSelectionBackground(UiKit.TEAL); nav.setSelectionForeground(Color.WHITE); nav.setSelectedIndex(0);
        nav.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        nav.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && nav.getSelectedValue() != null) show(nav.getSelectedValue()); });

        JLabel brand = new JLabel("  PortFlow"); brand.setFont(UiKit.TITLE.deriveFont(22f)); brand.setForeground(Color.WHITE);
        JPanel west = new JPanel(new BorderLayout()); west.setBackground(UiKit.NAVY); west.add(brand, BorderLayout.NORTH); west.add(nav, BorderLayout.CENTER);
        west.setPreferredSize(new Dimension(190, 0)); brand.setBorder(BorderFactory.createEmptyBorder(14, 6, 6, 6));

        policyBox.setSelectedItem(ctx.policy().name());
        policyBox.addActionListener(e -> ctx.setPolicy((String) policyBox.getSelectedItem()));
        engineButton.addActionListener(e -> toggleEngine());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6)); top.setBackground(Color.WHITE);
        top.add(engineButton); top.add(new JLabel("Policy:")); top.add(policyBox); top.add(engineLabel); top.add(Box.createHorizontalStrut(20)); top.add(clockLabel);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UiKit.GRID));
        status.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10)); status.setFont(UiKit.BASE);

        setLayout(new BorderLayout());
        add(west, BorderLayout.WEST); add(top, BorderLayout.NORTH); add(content, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        setSize(1280, 800); setLocationRelativeTo(null);

        int refresh = 1000;
        try { refresh = ctx.settings().uiRefreshMs(); } catch (Exception ignored) { }
        timer = new Timer(refresh, e -> tick());
        timer.start();
        show("Dashboard");
    }

    PortFlowContext ctx() { return ctx; }

    void show(String name) { current = name; cards.show(content, name); panels.get(name).refresh(); }
    void refreshCurrent() { panels.get(current).refresh(); }

    private void tick() {
        clockLabel.setText("Simulation time: " + UiKit.FMT.format(ctx.now()) + "  (x" + (int) ctx.clock.speed() + ")");
        boolean on = ctx.engine.isRunning();
        engineButton.setText(on ? "Stop engine" : "Start engine");
        engineLabel.setText(on ? "RUNNING - " + ctx.engine.runningSnapshot().size() + " task(s) executing" : "stopped");
        engineLabel.setForeground(on ? UiKit.GOOD : Color.GRAY);
        if (ctx.engine.lastError() != null) setStatus(ctx.engine.lastError());
        panels.get(current).refresh();
    }

    void toggleEngine() {
        if (ctx.engine.isRunning()) { ctx.engine.stop(); }
        else UiKit.async(() -> { ctx.engine.start(); return true; }, ok -> { }, msg -> UiKit.error(this, msg));
    }

    /** null = healthy. Shown in the status bar; never a stack trace. */
    void setStatus(String problem) {
        if (problem == null) { status.setText("Database connected: " + ctx.db.config().safeDescription()); status.setForeground(UiKit.GOOD); }
        else { status.setText("Problem: " + problem); status.setForeground(UiKit.BAD); }
    }

    private void shutdown() {
        timer.stop();
        ctx.close();
        dispose();
        System.exit(0);
    }
}
