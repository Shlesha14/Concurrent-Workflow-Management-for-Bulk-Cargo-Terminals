package com.portflow.ui;

import com.portflow.model.Cargo;
import com.portflow.model.CargoProfile;
import com.portflow.service.CargoService;
import com.portflow.service.CargoValidator;
import com.portflow.service.ServiceException;
import com.portflow.service.WorkflowService;
import com.portflow.util.TableData;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;

/** Cargo registration (profile-aware form), search, edit, and workflow creation. */
final class CargoPanel extends UiKit.RefreshablePanel {
    private final JTextField name = UiKit.field(18), qty = UiKit.field(8), arrival = UiKit.field(14), made = UiKit.field(14), expiry = UiKit.field(14), deadline = UiKit.field(14),
        handling = UiKit.field(22), extra = UiKit.field(22), search = UiKit.field(14);
    private final JComboBox<String> category = new JComboBox<>(), unit = new JComboBox<>(CargoValidator.UNITS.stream().sorted().toArray(String[]::new));
    private final JSpinner priority = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
    private final JTextArea profileInfo = new JTextArea(6, 30);
    private final UiKit.DataTable table = new UiKit.DataTable();
    private volatile List<CargoProfile> profiles = List.of(); private volatile TableData data;
    private Long editingId;

    CargoPanel(MainFrame m) {
        super(m);
        add(UiKit.title("Cargo Management"), BorderLayout.NORTH);
        JPanel form = new JPanel(new GridBagLayout()); form.setBackground(UiKit.CARD); form.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UiKit.GRID), BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        int r = 0;
        UiKit.addRow(form, r++, "Cargo name *", name); UiKit.addRow(form, r++, "Category (profile) *", category);
        JPanel q = UiKit.flow(qty, unit); UiKit.addRow(form, r++, "Quantity / unit *", q);
        UiKit.addRow(form, r++, "Priority (1-5) *", priority); UiKit.addRow(form, r++, "Arrival (yyyy-MM-dd HH:mm) *", arrival);
        UiKit.addRow(form, r++, "Manufactured / produced", made); UiKit.addRow(form, r++, "Expiry", expiry); UiKit.addRow(form, r++, "Completion deadline", deadline);
        UiKit.addRow(form, r++, "Special handling", handling); UiKit.addRow(form, r++, "Extra information", extra);
        profileInfo.setEditable(false); profileInfo.setLineWrap(true); profileInfo.setWrapStyleWord(true); profileInfo.setFont(UiKit.BASE); profileInfo.setBackground(new Color(0xF1F7F7));
        profileInfo.setBorder(BorderFactory.createTitledBorder("Handling requirements of the selected category"));
        JPanel buttons = UiKit.flow(UiKit.button("Register", this::register), UiKit.button("Save changes", this::saveEdit), UiKit.button("Clear", this::clear));
        JPanel left = new JPanel(new BorderLayout(0, 6)); left.setOpaque(false); left.add(form, BorderLayout.NORTH);
        left.add(profileInfo, BorderLayout.CENTER); left.add(buttons, BorderLayout.SOUTH); left.setPreferredSize(new Dimension(470, 0));
        category.addActionListener(e -> onCategory());
        JPanel right = new JPanel(new BorderLayout(0, 6)); right.setOpaque(false);
        right.add(UiKit.flow(new JLabel("Search:"), search, UiKit.button("Go", this::refresh), UiKit.button("Edit selected", this::loadSelected), UiKit.button("Create workflow for selected", this::createWorkflow)), BorderLayout.NORTH);
        right.add(table, BorderLayout.CENTER);
        add(left, BorderLayout.WEST); add(right, BorderLayout.CENTER);
        arrival.setText(UiKit.fmt(main.ctx.now()));
    }

    private CargoProfile profile() { String c = (String) category.getSelectedItem(); return profiles.stream().filter(p -> p.code().equals(c)).findFirst().orElse(null); }

    private void onCategory() {
        CargoProfile p = profile(); if (p == null) return;
        StringBuilder sb = new StringBuilder(p.displayName() + " (" + p.category() + ")\n");
        sb.append(p.requiresExpiry() ? "Manufacturing and expiry times are REQUIRED.\n" : "Expiry is optional.\n");
        if (p.hasTemperatureRange()) sb.append("Storage must stay within ").append(p.tempMinC()).append(" to ").append(p.tempMaxC()).append(" C (simulated).\n");
        sb.append("Max waiting for a resource: ").append(p.maxWaitMin()).append(" min. If already expired: ").append(p.expiredPolicy()).append(".\nWorkflow: ");
        p.steps().forEach(s -> sb.append(s.code()).append(s.dependsOn().isEmpty() ? "" : "(after " + String.join("+", s.dependsOn()) + ")").append("  "));
        sb.append("\nSimplification: ").append(p.simplificationNote());
        profileInfo.setText(sb.toString());
        if (p.requiresExpiry() && made.getText().isBlank() && expiry.getText().isBlank()) {
            made.setText(UiKit.fmt(main.ctx.now().minusHours(2))); expiry.setText(UiKit.fmt(main.ctx.now().plusHours(24)));
        }
    }

    private Cargo read(Long id) {
        BigDecimal q; try { q = new BigDecimal(qty.getText().trim()); } catch (NumberFormatException e) { q = null; }
        return new Cargo(id, name.getText(), (String) category.getSelectedItem(), q, (String) unit.getSelectedItem(), UiKit.parse(arrival.getText()), UiKit.parse(made.getText()), UiKit.parse(expiry.getText()),
            (Integer) priority.getValue(), UiKit.parse(deadline.getText()), handling.getText().isBlank() ? null : handling.getText(), extra.getText().isBlank() ? null : extra.getText(), "REGISTERED", null, null);
    }

    private void register() {
        Cargo c; try { c = read(null); } catch (IllegalArgumentException e) { UiKit.error(this, e.getMessage()); return; }
        UiKit.async(() -> main.ctx.cargo.register(c), this::showResult, msg -> UiKit.error(this, msg));
    }
    private void saveEdit() {
        if (editingId == null) { UiKit.info(this, "Select a cargo row and press 'Edit selected' first."); return; }
        Cargo c; try { c = read(editingId); } catch (IllegalArgumentException e) { UiKit.error(this, e.getMessage()); return; }
        UiKit.async(() -> main.ctx.cargo.update(c), this::showResult, msg -> UiKit.error(this, msg));
    }
    private void showResult(CargoService.Registration r) {
        if (r.cargoId() == null) {
            StringBuilder sb = new StringBuilder(r.message()).append("\n");
            r.issues().forEach(i -> sb.append("\n- ").append(i.kind() == CargoValidator.Kind.MISSING ? "Missing: " : "Invalid: ").append(i.message()));
            UiKit.error(this, sb.toString());
        } else {
            UiKit.info(this, "Cargo #" + r.cargoId() + ": " + r.status() + "\n" + r.message());
            if ("REGISTERED".equals(r.status())) clear();
        }
        refresh();
    }
    private void clear() { editingId = null; name.setText(""); qty.setText(""); made.setText(""); expiry.setText(""); deadline.setText(""); handling.setText(""); extra.setText(""); arrival.setText(UiKit.fmt(main.ctx.now())); onCategory(); }

    private void loadSelected() {
        Long id = table.selectedId(); if (id == null) { UiKit.info(this, "Select a cargo row first."); return; }
        UiKit.async(() -> main.ctx.cargo.find(id), o -> o.ifPresent(c -> {
            editingId = c.id(); name.setText(c.name()); category.setSelectedItem(c.profileCode()); qty.setText(c.quantity().toPlainString()); unit.setSelectedItem(c.unit()); priority.setValue(c.priority());
            arrival.setText(UiKit.fmt(c.arrivalTime())); made.setText(UiKit.fmt(c.manufacturedAt())); expiry.setText(UiKit.fmt(c.expiresAt())); deadline.setText(UiKit.fmt(c.deadlineAt()));
            handling.setText(c.specialHandling() == null ? "" : c.specialHandling()); extra.setText(c.extraInfo() == null ? "" : c.extraInfo());
        }), msg -> UiKit.error(this, msg));
    }

    private void createWorkflow() {
        Long id = table.selectedId(); if (id == null) { UiKit.info(this, "Select a cargo row first."); return; }
        UiKit.async(() -> { try { return main.ctx.workflows.createWorkflow(id); } catch (ServiceException e) { return e; } },
            res -> {
                if (res instanceof WorkflowService.Created c) UiKit.info(this, "Workflow #" + c.workflowId() + " created with " + c.taskCount() + " tasks." + (c.warnings().isEmpty() ? "" : "\n\nWarnings (these tasks will be blocked until fixed):\n- " + String.join("\n- ", c.warnings())));
                else { ServiceException e = (ServiceException) res; UiKit.error(this, e.getMessage() + (e.details().isEmpty() ? "" : "\n" + e.details())); }
                refresh();
            }, msg -> UiKit.error(this, msg));
    }

    @Override void load() throws Exception { profiles = main.ctx.cargo.profiles(); data = main.ctx.reports.cargo(search.getText()); }
    @Override void apply() {
        if (category.getItemCount() == 0) { profiles.forEach(p -> category.addItem(p.code())); onCategory(); }
        table.setData(data);
    }
}
