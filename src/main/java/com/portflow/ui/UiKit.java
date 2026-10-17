package com.portflow.ui;

import com.portflow.util.Database;
import com.portflow.util.TableData;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Shared look-and-feel helpers. Background work always goes through SwingWorker; components are only touched on the EDT. */
final class UiKit {
    private UiKit() {}
    static final Color NAVY = new Color(0x0B3C5D), TEAL = new Color(0x1D7874), BG = new Color(0xF4F6F8), CARD = Color.WHITE, GRID = new Color(0xDDE3E8);
    static final Color GOOD = new Color(0x2E7D32), WARN = new Color(0xB26A00), BAD = new Color(0xC62828), INFO = new Color(0x1565C0);
    static final Font BASE = new Font(Font.SANS_SERIF, Font.PLAIN, 13), BOLD = BASE.deriveFont(Font.BOLD), TITLE = BASE.deriveFont(Font.BOLD, 18f);

    /** Runs work off the EDT, then delivers the result (or a friendly error) back on the EDT. */
    static <T> void async(Callable<T> work, Consumer<T> ok, Consumer<String> err) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                try { ok.accept(get()); }
                catch (java.util.concurrent.ExecutionException e) { err.accept(Database.friendly(e.getCause())); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }.execute();
    }

    static JLabel title(String t) { JLabel l = new JLabel(t); l.setFont(TITLE); l.setForeground(NAVY); l.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0)); return l; }
    static JButton button(String text, Runnable r) { JButton b = new JButton(text); b.setFont(BASE); b.addActionListener(e -> r.run()); return b; }
    static JPanel page(String heading) {
        JPanel p = new JPanel(new BorderLayout(8, 8)); p.setBackground(BG); p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16)); p.add(title(heading), BorderLayout.NORTH); return p;
    }
    static JPanel flow(Component... cs) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4)); p.setOpaque(false); for (Component c : cs) p.add(c); return p; }
    static void error(Component parent, String msg) { JOptionPane.showMessageDialog(parent, msg, "PortFlow", JOptionPane.ERROR_MESSAGE); }
    static void info(Component parent, String msg) { JOptionPane.showMessageDialog(parent, msg, "PortFlow", JOptionPane.INFORMATION_MESSAGE); }
    static boolean confirm(Component parent, String msg) { return JOptionPane.showConfirmDialog(parent, msg, "PortFlow", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }

    /** Table showing a TableData snapshot with status colouring. */
    static final class DataTable extends JScrollPane {
        final JTable table = new JTable();
        private TableData data = new TableData(java.util.List.of(), java.util.List.of());
        DataTable() {
            super();
            setViewportView(table);
            table.setFont(BASE); table.setRowHeight(24); table.setGridColor(GRID); table.setFillsViewportHeight(true);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.getTableHeader().setFont(BOLD);
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                    Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                    comp.setForeground(sel ? Color.WHITE : color(String.valueOf(v)));
                    if (!sel) comp.setBackground(r % 2 == 0 ? Color.WHITE : new Color(0xF8FAFB));
                    return comp;
                }
            });
            setBorder(BorderFactory.createLineBorder(GRID));
        }
        static Color color(String v) {
            return switch (v) {
                case "COMPLETED", "AVAILABLE", "PASS", "RELEASED" -> GOOD;
                case "RUNNING", "ACTIVE" -> INFO;
                case "WAITING_FOR_RESOURCE", "READY", "HELD", "MAINTENANCE", "QUARANTINED" -> WARN;
                case "BLOCKED", "FAILED", "EXPIRED", "UNAVAILABLE", "REVOKED", "REJECTED", "FAIL", "CANCELLED" -> BAD;
                default -> Color.DARK_GRAY;
            };
        }
        void setData(TableData d) {
            int sel = table.getSelectedRow();
            Object selKey = sel >= 0 && !data.rows().isEmpty() && sel < data.rows().size() ? data.rows().get(sel).get(0) : null;
            data = d;
            Object[][] rows = new Object[d.rows().size()][];
            for (int i = 0; i < rows.length; i++) rows[i] = d.rows().get(i).toArray();
            table.setModel(new DefaultTableModel(rows, d.columns().toArray()) { @Override public boolean isCellEditable(int r, int c) { return false; } });
            for (int c = 0; c < table.getColumnCount(); c++) {
                int w = Math.max(70, table.getColumnName(c).length() * 10 + 24);
                for (int r = 0; r < Math.min(rows.length, 50); r++) w = Math.max(w, Math.min(420, String.valueOf(rows[r][c]).length() * 9 + 16));
                table.getColumnModel().getColumn(c).setPreferredWidth(w);
            }
            int total = 0; for (int c = 0; c < table.getColumnCount(); c++) total += table.getColumnModel().getColumn(c).getPreferredWidth();
            table.setAutoResizeMode(total < getViewport().getWidth() ? JTable.AUTO_RESIZE_ALL_COLUMNS : JTable.AUTO_RESIZE_OFF);
            if (selKey != null) for (int r = 0; r < rows.length; r++) if (selKey.equals(rows[r][0])) { table.setRowSelectionInterval(r, r); break; }
        }
        /** Value of the given column in the selected row, or null. */
        String selected(int col) { int r = table.getSelectedRow(); return r < 0 || r >= data.rows().size() || data.rows().get(r).get(col) == null ? null : String.valueOf(data.rows().get(r).get(col)); }
        Long selectedId() { String s = selected(0); try { return s == null ? null : Long.valueOf(s); } catch (NumberFormatException e) { return null; } }
    }

    /** A page that can refresh itself; refreshes never overlap (a slow database cannot pile up queries). */
    abstract static class RefreshablePanel extends JPanel {
        private volatile boolean busy;
        final MainFrame main;
        RefreshablePanel(MainFrame main) { this.main = main; setLayout(new BorderLayout(8, 8)); setBackground(BG); setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16)); }
        abstract void load() throws Exception;      // runs OFF the EDT; must return data via fields/applyLoaded
        abstract void apply();                      // runs ON the EDT after load()
        final void refresh() {
            if (busy) return;
            busy = true;
            async(() -> { load(); return Boolean.TRUE; }, ok -> { busy = false; apply(); main.setStatus(null); }, msg -> { busy = false; main.setStatus(msg); });
        }
    }

    static final java.time.format.DateTimeFormatter FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static java.time.LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.time.LocalDateTime.parse(s.trim(), FMT); } catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Date/time must look like 2026-03-01 14:30 (got '" + s + "')"); }
    }
    static String fmt(java.time.LocalDateTime t) { return t == null ? "" : FMT.format(t); }
    static JTextField field(int cols) { JTextField f = new JTextField(cols); f.setFont(BASE); return f; }
    static void addRow(JPanel p, int row, String label, Component c) {
        GridBagConstraints g = new GridBagConstraints(); g.gridy = row; g.insets = new Insets(3, 4, 3, 8); g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; JLabel l = new JLabel(label); l.setFont(BASE); p.add(l, g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1; p.add(c, g);
    }
}
