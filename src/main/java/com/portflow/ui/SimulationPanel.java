package com.portflow.ui;

import com.portflow.monitoring.MetricsService.RunMetrics;
import com.portflow.scheduler.PolicyRegistry;
import com.portflow.simulation.ScenarioResult;
import com.portflow.simulation.ScenarioRunner;
import com.portflow.simulation.StressTest;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Controlled scenarios A-H and the configurable load test. Both RESET operational data, so they ask for confirmation. */
final class SimulationPanel extends UiKit.RefreshablePanel {
    private final JComboBox<String> scenario = new JComboBox<>(ScenarioRunner.TITLES.entrySet().stream().map(e -> e.getKey() + " - " + e.getValue()).toArray(String[]::new));
    private final JTextArea output = new JTextArea();
    private final JTextField cargo = UiKit.field(5), cranes = UiKit.field(3), forks = UiKit.field(3), trucks = UiKit.field(3), workers = UiKit.field(3);
    private final JComboBox<String> policy = new JComboBox<>(PolicyRegistry.names().toArray(new String[0]));
    private final UiKit.DataTable metrics = new UiKit.DataTable();
    private final List<RunMetrics> history = new ArrayList<>();
    private final JLabel busy = new JLabel(" ");

    SimulationPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Simulation and Analytics"), BorderLayout.NORTH);
        cargo.setText("40"); cranes.setText("2"); forks.setText("2"); trucks.setText("2"); workers.setText("4");
        JPanel scen = UiKit.flow(new JLabel("Scenario:"), scenario, UiKit.button("Run scenario", () -> runScenarios(false)), UiKit.button("Run all A-H", () -> runScenarios(true)));
        JPanel load = UiKit.flow(new JLabel("Load test - cargo items:"), cargo, new JLabel("cranes"), cranes, new JLabel("forklifts"), forks, new JLabel("trucks"), trucks, new JLabel("workers"), workers, new JLabel("policy"), policy,
            UiKit.button("Run load test", this::runLoad), UiKit.button("Export results CSV", this::export));
        JPanel top = new JPanel(new GridLayout(3, 1)); top.setOpaque(false); top.add(scen); top.add(load); top.add(busy);
        output.setEditable(false); output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); output.setText("Scenario reports appear here. Each run RESETS cargo, tasks, allocations and resources in the database.\nThe simulation clock runs fast during scenarios (" + (int) ScenarioRunner.SCENARIO_SPEED + "x).\n");
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(output), metrics); split.setResizeWeight(0.65); split.setBorder(null);
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.setOpaque(false); body.add(top, BorderLayout.NORTH); body.add(split, BorderLayout.CENTER); add(body, BorderLayout.CENTER);
    }

    private boolean ok() { return UiKit.confirm(this, "This clears all cargo, tasks, allocations, history and resources in the database and replaces them with the scenario data.\nContinue?"); }

    private void runScenarios(boolean all) {
        if (!ok()) return;
        String id = ((String) scenario.getSelectedItem()).substring(0, 1);
        busy.setText("Running... (the UI stays responsive)");
        UiKit.async(() -> { var r = new ScenarioRunner(main.ctx, 120000); return all ? r.runAll() : List.of(r.run(id)); },
            rs -> { busy.setText(" "); for (ScenarioResult r : rs) { output.append("\n" + r.report()); if (r.metrics() != null) history.addAll(r.metrics()); } showMetrics(); output.setCaretPosition(output.getDocument().getLength()); },
            msg -> { busy.setText(" "); UiKit.error(this, msg); });
    }

    private void runLoad() {
        int n, c, f, t, w;
        try { n = Integer.parseInt(cargo.getText().trim()); c = Integer.parseInt(cranes.getText().trim()); f = Integer.parseInt(forks.getText().trim()); t = Integer.parseInt(trucks.getText().trim()); w = Integer.parseInt(workers.getText().trim()); }
        catch (NumberFormatException e) { UiKit.error(this, "All load-test fields must be whole numbers."); return; }
        if (n < 1 || n > 500 || c < 1 || f < 1 || t < 1 || w < 1 || w > 16) { UiKit.error(this, "Use 1-500 cargo items, at least 1 of each resource, and 1-16 workers."); return; }
        if (!ok()) return;
        String p = (String) policy.getSelectedItem();
        busy.setText("Load test running...");
        UiKit.async(() -> StressTest.run(main.ctx, n, c, f, t, w, p, 600000), m -> { busy.setText(" "); history.add(m); showMetrics(); output.append("\nLoad test finished: " + m.toCsv() + "\n"); }, msg -> { busy.setText(" "); UiKit.error(this, msg); });
    }

    private void showMetrics() {
        List<List<Object>> rows = new ArrayList<>();
        for (RunMetrics m : history) rows.add(List.of(m.label(), m.tasks(), m.completed(), m.failed(), m.blocked(), m.deadlineMisses(), String.format("%.1f", m.avgWaitMin()), String.format("%.1f", m.makespanMin()),
            String.format("%.2f", m.throughputPerHour()), String.format("%.1f", m.avgUtilizationPct()), m.conflicts(), String.format("%.1f", m.lockWaitMs()), m.peakConcurrency()));
        metrics.setData(new com.portflow.util.TableData(List.of("run", "tasks", "done", "failed", "blocked", "deadline misses", "avg wait (min)", "makespan (min)", "throughput /h", "slot util %", "conflicts", "lock wait ms", "peak concurrency"), rows));
    }

    private void export() {
        if (history.isEmpty()) { UiKit.info(this, "Nothing to export yet."); return; }
        JFileChooser fc = new JFileChooser(); fc.setSelectedFile(new java.io.File("portflow_results.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) try {
            StringBuilder sb = new StringBuilder(RunMetrics.header()).append('\n'); history.forEach(m -> sb.append(m.toCsv()).append('\n'));
            Files.writeString(Path.of(fc.getSelectedFile().toURI()), sb.toString()); UiKit.info(this, "Saved " + fc.getSelectedFile());
        } catch (Exception e) { UiKit.error(this, e.getMessage()); }
    }

    @Override void load() { }
    @Override void apply() { }
}
