package com.portflow.ui;

import com.portflow.config.AppConfig;
import com.portflow.config.PortFlowContext;
import com.portflow.config.SchemaInstaller;
import com.portflow.service.Settings;
import com.portflow.simulation.SimClock;

import javax.swing.*;
import java.time.LocalDateTime;

/** Entry point: java -jar portflow.jar  (reads portflow.properties; see README). */
public final class PortFlowApp {
    private PortFlowApp() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            AppConfig cfg = AppConfig.load();
            PortFlowContext ctx = new PortFlowContext(cfg, SimClock.scaled(LocalDateTime.now().withSecond(0).withNano(0), 60));
            String problem = ctx.db.checkConnection();
            if (problem != null) {
                JOptionPane.showMessageDialog(null, problem + "\n\nConnection used: " + cfg.safeDescription() + "\nSee README.md > Troubleshooting.", "PortFlow cannot start", JOptionPane.ERROR_MESSAGE);
                System.exit(2);
            }
            try {
                if (!SchemaInstaller.isInstalled(ctx.db)) {
                    int a = JOptionPane.showConfirmDialog(null, "The PortFlow tables are not installed in this database yet.\nCreate them now (schema, triggers, views and demo data)?", "First run", JOptionPane.YES_NO_OPTION);
                    if (a != JOptionPane.YES_OPTION) System.exit(0);
                    SchemaInstaller.install(ctx.db, false);
                }
                ctx.clock.setSpeed(ctx.db.read(Settings::load).speed());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, com.portflow.util.Database.friendly(e), "PortFlow cannot start", JOptionPane.ERROR_MESSAGE);
                System.exit(2);
            }
            new MainFrame(ctx).setVisible(true);
        });
    }
}
