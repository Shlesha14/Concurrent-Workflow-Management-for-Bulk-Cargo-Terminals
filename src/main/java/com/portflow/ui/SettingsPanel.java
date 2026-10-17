package com.portflow.ui;

import com.portflow.config.SchemaInstaller;
import com.portflow.service.ServiceException;
import com.portflow.service.Settings;
import com.portflow.simulation.DemoData;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Engine / simulation / scheduling-weight settings (validated against safe bounds), database status (no credentials) and profile constraints. */
final class SettingsPanel extends UiKit.RefreshablePanel {
    private record Def(String key, String label, double min, double max) {}
    private static final Def[] DEFS = {
        new Def("engine.workers", "Worker threads (restart engine to apply)", 1, 16), new Def("engine.poll_ms", "Dispatcher poll interval (ms)", 20, 2000),
        new Def("engine.max_retries", "Automatic retries per task", 0, 10), new Def("engine.backoff_ms", "Retry back-off base (real ms, doubles each retry)", 10, 60000),
        new Def("engine.lock_timeout_ms", "Allocation lock timeout (ms)", 100, 60000), new Def("sim.speed", "Simulation speed (sim seconds per real second)", 1, 100000),
        new Def("sim.excursion_probability", "Simulated temperature excursion probability (0-1)", 0, 1), new Def("weight.priority", "Score weight: priority (0-1)", 0, 1),
        new Def("weight.urgency", "Score weight: urgency (0-1)", 0, 1), new Def("weight.wait", "Score weight: waiting (0-1)", 0, 1), new Def("weight.short", "Score weight: short job (0-1)", 0, 1),
        new Def("urgency.horizon_min", "Urgency horizon H (minutes)", 30, 100000), new Def("ui.refresh_ms", "Screen refresh interval (ms)", 250, 10000)};
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final JLabel dbInfo = new JLabel();
    private final UiKit.DataTable profiles = new UiKit.DataTable();
    private volatile Settings loaded; private volatile TableData prof; private volatile String dbStatus;

    SettingsPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Settings and Configuration"), BorderLayout.NORTH);
        JPanel form = new JPanel(new GridBagLayout()); form.setBackground(UiKit.CARD); form.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UiKit.GRID), BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        int r = 0; for (Def d : DEFS) { JTextField f = UiKit.field(8); fields.put(d.key(), f); UiKit.addRow(form, r++, d.label(), f); }
        JPanel left = new JPanel(new BorderLayout(0, 6)); left.setOpaque(false); left.add(form, BorderLayout.NORTH);
        left.add(UiKit.flow(UiKit.button("Save settings", this::save), UiKit.button("Reload", this::reloadFields)), BorderLayout.CENTER); left.setPreferredSize(new Dimension(500, 0));
        dbInfo.setFont(UiKit.BOLD);
        JPanel right = new JPanel(new BorderLayout(0, 6)); right.setOpaque(false);
        JPanel top = new JPanel(new GridLayout(2, 1)); top.setOpaque(false); top.add(dbInfo);
        top.add(UiKit.flow(UiKit.button("Edit selected profile...", this::editProfile), UiKit.button("Reset database + load demo data", this::resetDemo), UiKit.button("Load demo terminal resources", this::demoResources)));
        right.add(top, BorderLayout.NORTH); right.add(profiles, BorderLayout.CENTER);
        add(left, BorderLayout.WEST); add(right, BorderLayout.CENTER);
    }

    private boolean populated;
    private void reloadFields() { if (loaded == null) return; Settings s = loaded;
        double[] v = {s.workers(), s.pollMs(), s.maxRetries(), s.backoffMs(), s.lockTimeoutMs(), s.speed(), s.excursionProbability(), s.wPriority(), s.wUrgency(), s.wWait(), s.wShort(), s.horizonMin(), s.uiRefreshMs()};
        int i = 0; for (Def d : DEFS) { fields.get(d.key()).setText(v[i] == Math.rint(v[i]) ? String.valueOf((long) v[i]) : String.valueOf(v[i])); i++; } }

    private void save() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Def d : DEFS) {
            try { double v = Double.parseDouble(fields.get(d.key()).getText().trim());
                if (v < d.min() || v > d.max()) { UiKit.error(this, d.label() + " must be between " + d.min() + " and " + d.max()); return; }
                out.put(d.key(), fields.get(d.key()).getText().trim());
            } catch (NumberFormatException e) { UiKit.error(this, d.label() + " must be a number."); return; }
        }
        UiKit.async(() -> main.ctx.db.inTransaction(c -> { for (var e : out.entrySet()) Settings.save(c, e.getKey(), e.getValue()); return true; }),
            ok -> { main.ctx.clock.setSpeed(Double.parseDouble(out.get("sim.speed"))); UiKit.info(this, "Saved. Worker count and refresh interval apply after restarting the engine / application."); populated = false; refresh(); }, msg -> UiKit.error(this, msg));
    }

    private void editProfile() {
        String code = profiles.selected(0); if (code == null) { UiKit.info(this, "Select a profile first."); return; }
        JTextField wait = UiKit.field(6), a = UiKit.field(6), b = UiKit.field(6); JCheckBox exp = new JCheckBox("Expiry required");
        wait.setText(profiles.selected(6)); a.setText(profiles.selected(4) == null ? "" : profiles.selected(4)); b.setText(profiles.selected(5) == null ? "" : profiles.selected(5)); exp.setSelected("true".equalsIgnoreCase(profiles.selected(3)) || "1".equals(profiles.selected(3)));
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6)); p.add(new JLabel("Max wait (min)")); p.add(wait); p.add(new JLabel("Temp min C (blank = none)")); p.add(a); p.add(new JLabel("Temp max C")); p.add(b); p.add(exp);
        if (JOptionPane.showConfirmDialog(this, p, "Edit profile " + code, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        UiKit.async(() -> { try { main.ctx.cargo.updateProfileConstraints(code, Integer.parseInt(wait.getText().trim()), a.getText().isBlank() ? null : Double.valueOf(a.getText().trim()), b.getText().isBlank() ? null : Double.valueOf(b.getText().trim()), exp.isSelected()); return "Profile updated."; }
            catch (ServiceException e) { return "Refused: " + e.getMessage(); } catch (NumberFormatException e) { return "Enter numbers only."; } },
            msg -> { UiKit.info(this, msg); refresh(); }, msg -> UiKit.error(this, msg));
    }

    private void resetDemo() {
        if (!UiKit.confirm(this, "This DROPS all PortFlow tables and recreates them with demo data. Continue?")) return;
        main.ctx.engine.stop();
        UiKit.async(() -> { SchemaInstaller.install(main.ctx.db, true); return true; }, ok -> { UiKit.info(this, "Database re-initialised."); populated = false; refresh(); }, msg -> UiKit.error(this, msg));
    }
    private void demoResources() {
        UiKit.async(() -> { try { DemoData.addTerminalResources(main.ctx); return "Demo terminal resources added."; } catch (ServiceException e) { return "Refused: " + e.getMessage(); } },
            msg -> UiKit.info(this, msg), msg -> UiKit.error(this, msg));
    }

    @Override void load() throws Exception { loaded = main.ctx.settings(); prof = main.ctx.reports.profiles(); dbStatus = main.ctx.db.checkConnection(); }
    @Override void apply() {
        if (!populated) { reloadFields(); populated = true; }
        dbInfo.setText(dbStatus == null ? "Database: CONNECTED - " + main.ctx.db.config().safeDescription() : "Database problem: " + dbStatus);
        dbInfo.setForeground(dbStatus == null ? UiKit.GOOD : UiKit.BAD);
        profiles.setData(prof);
    }
}
