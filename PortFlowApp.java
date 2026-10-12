package edu.portflow;

import edu.portflow.engine.*;
import edu.portflow.model.*;
import edu.portflow.service.ProfileCatalog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class PortFlowApp {
    private final JFrame frame = new JFrame("PortFlow | Concurrent Cargo Workflow Management");
    private final ProfileCatalog profiles = new ProfileCatalog();
    private final ResourceManager resourceManager = new ResourceManager();
    private final AtomicLong cargoIds = new AtomicLong(System.currentTimeMillis() * 100L);
    private final WorkflowFactory workflowFactory = new WorkflowFactory();
    private final DefaultTableModel cargoModel = model("ID","Cargo","Category","Qty","Unit","Expiry","Priority");
    private final DefaultTableModel taskModel = model("Task ID","Cargo ID","Task","Type","Status","Priority","Worker","Dependencies");
    private final DefaultTableModel resourceModel = model("ID","Resource","Type","Capacity","Unit","Storage","Status","Used");
    private final DefaultListModel<String> eventModel = new DefaultListModel<>();
    private final JLabel dbStatus = new JLabel();
    private final JLabel summary = new JLabel("No cargo registered yet.");
    private WorkflowEngine engine;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PortFlowApp().show());
    }
    private void show() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch(Exception ignored) {}
        engine = new WorkflowEngine(4,resourceManager,event -> SwingUtilities.invokeLater(() -> {
            eventModel.add(0,event.toString());
            if(eventModel.size()>500) eventModel.remove(eventModel.size()-1);
            refreshTasks();
        }));
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1100,720));
        frame.setSize(1280,820);
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0,12));
        root.setBorder(new EmptyBorder(16,18,16,18));
        root.setBackground(new Color(244,247,250));
        root.add(header(),BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dashboard",dashboard());
        tabs.addTab("Cargo Intake",cargoPanel());
        tabs.addTab("Execution Monitor",taskPanel());
        tabs.addTab("Resources",resourcePanel());
        tabs.addTab("Activity Log",eventPanel());
        root.add(tabs,BorderLayout.CENTER);
        frame.setContentPane(root);
        refreshResources();
        refreshTasks();
        dbStatus.setText(edu.portflow.db.Database.canConnect() ? "MySQL connected" : "MySQL not connected — demo mode");
        frame.setVisible(true);
    }
    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false);
        JPanel brand=new JPanel(new GridLayout(2,1)); brand.setOpaque(false);
        JLabel title=new JLabel("PORTFLOW"); title.setFont(new Font("SansSerif",Font.BOLD,25)); title.setForeground(new Color(24,49,77));
        JLabel subtitle=new JLabel("Concurrent workflow management for cargo terminals"); subtitle.setForeground(new Color(90,105,120));
        brand.add(title); brand.add(subtitle); p.add(brand,BorderLayout.WEST);
        dbStatus.setOpaque(true); dbStatus.setBackground(new Color(229,238,245)); dbStatus.setBorder(new EmptyBorder(8,12,8,12));
        p.add(dbStatus,BorderLayout.EAST); return p;
    }
    private JPanel dashboard() {
        JPanel p=new JPanel(new BorderLayout(12,12)); p.setBorder(new EmptyBorder(16,12,12,12)); p.setBackground(Color.WHITE);
        JPanel cards=new JPanel(new GridLayout(1,4,12,12)); cards.setOpaque(false);
        cards.add(metric("Cargo records",()->String.valueOf(engine.cargos().size())));
        cards.add(metric("Workflow tasks",()->String.valueOf(engine.tasks().size())));
        cards.add(metric("Completed tasks",()->String.valueOf(engine.tasks().stream().filter(t->t.status()==TaskStatus.COMPLETED).count())));
        cards.add(metric("Blocked / failed",()->String.valueOf(engine.tasks().stream().filter(t->t.status()==TaskStatus.BLOCKED||t.status()==TaskStatus.FAILED||t.status()==TaskStatus.EXPIRED).count())));
        p.add(cards,BorderLayout.NORTH);
        JPanel center=new JPanel(new BorderLayout(8,8)); center.setOpaque(false);
        JLabel h=new JLabel("Operational overview"); h.setFont(new Font("SansSerif",Font.BOLD,18));
        JTextArea note=new JTextArea("PortFlow creates dependency-aware workflows from cargo handling profiles. Independent tasks can run concurrently in a bounded worker pool, while shared resources are reserved under synchronization. Cargo rules and sensor-like readings in this prototype are simulated.\\n\\nQuick start: open Cargo Intake, add cargo, then start the workflow from Execution Monitor. Use Run Demo Scenario to populate several cargo categories.");
        note.setLineWrap(true); note.setWrapStyleWord(true); note.setEditable(false); note.setBackground(new Color(248,250,252)); note.setBorder(new EmptyBorder(14,14,14,14));
        center.add(h,BorderLayout.NORTH); center.add(note,BorderLayout.CENTER); center.add(summary,BorderLayout.SOUTH);
        p.add(center,BorderLayout.CENTER); return p;
    }
    private JPanel metric(String name,java.util.function.Supplier<String> value) {
        JPanel c=new JPanel(new BorderLayout(4,8)); c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220,228,235)),new EmptyBorder(14,14,14,14))); c.setBackground(new Color(250,252,254));
        JLabel n=new JLabel(name); n.setForeground(new Color(90,105,120));
        JLabel v=new JLabel(value.get()); v.setFont(new Font("SansSerif",Font.BOLD,26)); v.setForeground(new Color(26,67,100));
        c.add(n,BorderLayout.NORTH); c.add(v,BorderLayout.CENTER);
        javax.swing.Timer timer=new javax.swing.Timer(1200,e->v.setText(value.get())); timer.start();
        return c;
    }
    private JPanel cargoPanel() {
        JPanel p=new JPanel(new BorderLayout(12,12)); p.setBorder(new EmptyBorder(14,12,12,12)); p.setBackground(Color.WHITE);
        JPanel form=new JPanel(new GridBagLayout()); form.setBorder(BorderFactory.createTitledBorder("Register cargo and generate workflow"));
        GridBagConstraints g=new GridBagConstraints(); g.insets=new Insets(5,6,5,6); g.fill=GridBagConstraints.HORIZONTAL;
        JTextField name=new JTextField("Tomato shipment",18), quantity=new JTextField("120",8), unit=new JTextField("KG",8);
        JComboBox<CargoCategory> category=new JComboBox<>(CargoCategory.values());
        JComboBox<Integer> priority=new JComboBox<>(new Integer[]{1,2,3,4,5}); priority.setSelectedItem(4);
        JCheckBox expiry=new JCheckBox("Expiry applies",true);
        JTextField expiryHours=new JTextField("18",6), deadlineHours=new JTextField("2",6), manufacturedHours=new JTextField("",6);
        JTextArea notes=new JTextArea("Example cargo profile",2,20);
        addField(form,g,0,"Cargo name",name); addField(form,g,1,"Category",category); addField(form,g,2,"Quantity",quantity);
        addField(form,g,3,"Unit (KG/TONNE/BOX/BAG)",unit); addField(form,g,4,"Priority (1–5)",priority);
        addField(form,g,5,"Expiry in hours",expiryHours); addField(form,g,6,"Deadline in hours",deadlineHours);
        addField(form,g,7,"Manufactured hours ago (optional)",manufacturedHours);
        addField(form,g,8,"Notes",new JScrollPane(notes));
        g.gridx=0;g.gridy=9;g.gridwidth=2;form.add(expiry,g);
        JButton add=new JButton("Register Cargo + Create Workflow"); add.setBackground(new Color(31,84,121)); add.setForeground(Color.WHITE);
        g.gridy=10;form.add(add,g);
        add.addActionListener(e->{
            try {
                String n=name.getText().trim(); if(n.isEmpty()) throw new IllegalArgumentException("Cargo name is required.");
                double q=Double.parseDouble(quantity.getText().trim()); if(q<=0)throw new IllegalArgumentException("Quantity must be positive.");
                CargoCategory cat=(CargoCategory)category.getSelectedItem();
                int pr=(Integer)priority.getSelectedItem(); LocalDateTime now=LocalDateTime.now();
                LocalDateTime exp=expiry.isSelected()?now.plusHours(Long.parseLong(expiryHours.getText().trim())):null;
                LocalDateTime dl=deadlineHours.getText().trim().isBlank()?null:now.plusHours(Long.parseLong(deadlineHours.getText().trim()));
                LocalDateTime manufactured=manufacturedHours.getText().trim().isBlank()?null:now.minusHours(Long.parseLong(manufacturedHours.getText().trim()));
                Cargo c=new Cargo(cargoIds.incrementAndGet(),n,cat,q,unit.getText().trim().toUpperCase(),now,manufactured,exp,dl,pr,profiles.get(cat),notes.getText().trim());
                List<WorkflowTask> tasks=workflowFactory.create(c);
                engine.registerCargo(c,tasks);
                cargoModel.addRow(new Object[]{c.id(),c.name(),c.category(),c.quantity(),c.unit(),c.expiryTime()==null?"—":c.expiryTime(),c.priority()});
                refreshTasks(); refreshResources(); summary.setText("Created "+tasks.size()+" tasks for "+c.name()+".");
                JOptionPane.showMessageDialog(frame,"Cargo registered and workflow created.\\nTasks: "+tasks.size()+"\\nProfile: "+c.profile().displayName(),"PortFlow",JOptionPane.INFORMATION_MESSAGE);
            } catch(Exception ex){JOptionPane.showMessageDialog(frame,ex.getMessage(),"Invalid cargo details",JOptionPane.WARNING_MESSAGE);}
        });
        p.add(form,BorderLayout.WEST); p.add(new JScrollPane(new JTable(cargoModel)),BorderLayout.CENTER); return p;
    }
    private JPanel taskPanel() {
        JPanel p=new JPanel(new BorderLayout(10,10)); p.setBorder(new EmptyBorder(14,12,12,12)); p.setBackground(Color.WHITE);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT)); actions.setOpaque(false);
        JComboBox<SchedulingPolicy> policy=new JComboBox<>(SchedulingPolicy.values());
        JButton run=new JButton("Start / Resume Ready Tasks"), demo=new JButton("Run Demo Scenario"), refresh=new JButton("Refresh");
        actions.add(new JLabel("Scheduling policy:")); actions.add(policy); actions.add(run); actions.add(demo); actions.add(refresh);
        run.addActionListener(e->{
            if(engine.tasks().isEmpty()){JOptionPane.showMessageDialog(frame,"Register cargo first.");return;}
            try { engine.start((SchedulingPolicy)policy.getSelectedItem()); summary.setText("Execution requested using "+policy.getSelectedItem()); }
            catch(Exception ex){JOptionPane.showMessageDialog(frame,ex.getMessage(),"Execution error",JOptionPane.ERROR_MESSAGE);}
        });
        refresh.addActionListener(e->{refreshTasks();refreshResources();});
        demo.addActionListener(e->addDemoCargo());
        p.add(actions,BorderLayout.NORTH); p.add(new JScrollPane(new JTable(taskModel)),BorderLayout.CENTER);
        JLabel hint=new JLabel("Tasks with unfinished dependencies remain waiting. Inspect Activity Log for resource acquisition/release and failure reasons.");
        p.add(hint,BorderLayout.SOUTH); return p;
    }
    private JPanel resourcePanel() {
        JPanel p=new JPanel(new BorderLayout(10,10)); p.setBorder(new EmptyBorder(14,12,12,12)); p.setBackground(Color.WHITE);
        JButton toggle=new JButton("Toggle selected resource availability"); JTable table=new JTable(resourceModel);
        toggle.addActionListener(e->{int row=table.getSelectedRow(); if(row<0)return; long id=Long.parseLong(resourceModel.getValueAt(row,0).toString());
            ResourceUnit r=resourceManager.all().stream().filter(x->x.id()==id).findFirst().orElse(null);
            if(r!=null)resourceManager.setAvailable(id,!r.isAvailable()); refreshResources();});
        JPanel top=new JPanel(new FlowLayout(FlowLayout.LEFT)); top.setOpaque(false);top.add(toggle);
        p.add(top,BorderLayout.NORTH);p.add(new JScrollPane(table),BorderLayout.CENTER);return p;
    }
    private JPanel eventPanel() {
        JPanel p=new JPanel(new BorderLayout(8,8));p.setBorder(new EmptyBorder(14,12,12,12));p.setBackground(Color.WHITE);
        JButton clear=new JButton("Clear view (database history is not deleted)");
        clear.addActionListener(e->eventModel.clear());
        p.add(clear,BorderLayout.NORTH);p.add(new JScrollPane(new JList<>(eventModel)),BorderLayout.CENTER);return p;
    }
    private void addDemoCargo() {
        Object[][] data={{"Tomatoes demo",CargoCategory.PRODUCE,120.0,"KG",5,12},{"Frozen dessert demo",CargoCategory.FROZEN_FOOD,80.0,"KG",5,24},
                {"Laptop shipment demo",CargoCategory.ELECTRONICS,20.0,"BOX",3,0},{"Coal lot demo",CargoCategory.COAL,40.0,"TONNE",2,0},
                {"Cement bags demo",CargoCategory.CEMENT,100.0,"BAG",2,0}};
        for(Object[] d:data) {
            try {
                LocalDateTime now=LocalDateTime.now(); CargoCategory cat=(CargoCategory)d[1]; int hrs=(int)d[5];
                Cargo c=new Cargo(cargoIds.incrementAndGet(),(String)d[0],cat,(double)d[2],(String)d[3],now,null,hrs>0?now.plusHours(hrs):null,
                        now.plusHours(4),(int)d[4],profiles.get(cat),"Generated demonstration scenario");
                List<WorkflowTask> tasks=workflowFactory.create(c);engine.registerCargo(c,tasks);
                cargoModel.addRow(new Object[]{c.id(),c.name(),c.category(),c.quantity(),c.unit(),c.expiryTime()==null?"—":c.expiryTime(),c.priority()});
            } catch(Exception ex){eventModel.addElement("Demo scenario warning: "+ex.getMessage());}
        }
        refreshTasks();refreshResources();summary.setText("Demo scenario added. Choose a policy and start execution.");
    }
    private void refreshTasks() {
        if(taskModel==null)return;
        taskModel.setRowCount(0);
        for(WorkflowTask t:engine.tasks())taskModel.addRow(new Object[]{t.id(),t.cargoId(),t.name(),t.taskType(),t.status(),t.priority(),t.workerName()==null?"—":t.workerName(),t.dependencies()});
    }
    private void refreshResources() {
        resourceModel.setRowCount(0);
        for(ResourceUnit r:resourceManager.all())resourceModel.addRow(new Object[]{r.id(),r.name(),r.type(),r.capacity(),r.capacityUnit(),r.storageClass(),r.isAvailable()?"AVAILABLE":"UNAVAILABLE",resourceManager.used(r.id())});
    }
    private static DefaultTableModel model(String... cols){return new DefaultTableModel(cols,0){@Override public boolean isCellEditable(int r,int c){return false;}};}
    private static void addField(JPanel p,GridBagConstraints g,int row,String label,Component component){
        g.gridwidth=1;g.gridx=0;g.gridy=row;p.add(new JLabel(label),g);g.gridx=1;p.add(component,g);
    }
}
