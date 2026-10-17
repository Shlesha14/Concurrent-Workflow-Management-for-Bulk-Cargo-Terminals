package com.portflow.ui;

import com.portflow.TestSupport;
import com.portflow.config.PortFlowContext;
import com.portflow.simulation.DemoData;
import com.portflow.simulation.SimClock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opens the real main window with live data, visits every screen and (optionally) saves screenshots to -Dportflow.screens=dir. Skipped on headless machines. */
class UiSmokeTest {
    @Test void everyScreenOpensAndRefreshesAgainstRealData() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display (run under xvfb-run on a server)");
        PortFlowContext ctx = TestSupport.fresh(SimClock.scaled(TestSupport.T0, 1500));
        DemoData.addTerminalResources(ctx);
        var tom = ctx.cargo.register(TestSupport.draft("Tomatoes", "TOMATO", 40, 4, ctx.now(), ctx.now().minusHours(3), ctx.now().plusHours(30), null));
        ctx.workflows.createWorkflow(tom.cargoId());
        TestSupport.cargoWithWorkflow(ctx, "Steam coal", "COAL", 1500, 3, null);
        ctx.cargo.register(TestSupport.draft("Expired milk", "CHILLED_FOOD", 10, 3, ctx.now(), ctx.now().minusDays(5), ctx.now().minusDays(1), null));
        ctx.engine.start();
        String dir = System.getProperty("portflow.screens");
        MainFrame[] holder = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> { holder[0] = new MainFrame(ctx); holder[0].setVisible(true); });
        MainFrame f = holder[0];
        Field pf = MainFrame.class.getDeclaredField("panels"); pf.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, UiKit.RefreshablePanel> panels = (Map<String, UiKit.RefreshablePanel>) pf.get(f);
        Thread.sleep(2500);
        for (String name : panels.keySet()) {
            SwingUtilities.invokeAndWait(() -> f.show(name));
            Thread.sleep(1500);
            if (dir != null) {
                new File(dir).mkdirs();
                BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                SwingUtilities.invokeAndWait(() -> { Graphics2D g = img.createGraphics(); f.paint(g); g.dispose(); });
                ImageIO.write(img, "png", new File(dir, name.replaceAll("[^A-Za-z]", "_") + ".png"));
            }
        }
        assertNull(ctx.engine.lastError(), "engine reported: " + ctx.engine.lastError());
        assertTrue(TestSupport.count(ctx, "SELECT COUNT(*) FROM task WHERE state='COMPLETED'") > 0, "engine executed tasks while the UI was open");
        SwingUtilities.invokeAndWait(f::dispose);
        ctx.close();
    }
}
