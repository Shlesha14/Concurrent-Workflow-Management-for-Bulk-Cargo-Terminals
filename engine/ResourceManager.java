package edu.portflow.engine;

import edu.portflow.model.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class ResourceManager {
    private final List<ResourceUnit> resources = new ArrayList<>();
    private final Map<Long, Double> usedCapacity = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock(true);

    public ResourceManager() {
        resources.add(new ResourceUnit(201,"Crane-C1","CRANE",100,"TONNE","GENERAL",Set.of("COAL","CEMENT","GENERAL","ELECTRONICS")));
        resources.add(new ResourceUnit(202,"Forklift-F1","FORKLIFT",1000,"KG","GENERAL",Set.of("PRODUCE","FROZEN_FOOD","CHILLED_FOOD","ELECTRONICS","CEMENT","GENERAL")));
        resources.add(new ResourceUnit(203,"ColdRoom-CR1","COLD_STORAGE",500,"KG","CHILLED",Set.of("PRODUCE","CHILLED_FOOD")));
        resources.add(new ResourceUnit(204,"Freezer-FR1","COLD_STORAGE",300,"KG","FROZEN",Set.of("FROZEN_FOOD")));
        resources.add(new ResourceUnit(205,"ProtectedStore-PS1","PROTECTED_STORAGE",400,"BOX","PROTECTED",Set.of("ELECTRONICS")));
        resources.add(new ResourceUnit(206,"Conveyor-CV1","CONVEYOR",100,"TONNE","DRY_BULK",Set.of("COAL")));
        resources.add(new ResourceUnit(207,"DryStore-DS1","DRY_STORAGE",1000,"BAG","DRY",Set.of("CEMENT","GENERAL")));
        resources.add(new ResourceUnit(208,"Inspection-INS1","INSPECTION_STATION",1,"TASK","GENERAL",Set.of("PRODUCE","ELECTRONICS")));
    }
    public List<ResourceUnit> all() { return List.copyOf(resources); }
    public ResourceUnit acquire(WorkflowTask task, CargoCategory category) {
        lock.lock();
        try {
            for (ResourceUnit r : resources) {
                if (!r.type().equals(task.requiredResourceType()) || !r.isAvailable()) continue;
                if (!r.supports(category.name())) continue;
                double current = usedCapacity.getOrDefault(r.id(),0.0);
                // Inspection stations are exclusive; storage/handling capacity is represented as cargo units in this simulation.
                double requested = task.taskType().equals("INSPECT") ? 1 : task.requiredCapacity();
                if (requested > r.capacity() || current + requested > r.capacity()) continue;
                usedCapacity.put(r.id(),current+requested);
                return new ResourceUnitLease(r,requested,this);
            }
            return null;
        } finally { lock.unlock(); }
    }
    private void release(long id,double amount) {
        lock.lock();
        try { usedCapacity.compute(id,(k,v)->Math.max(0,(v==null?0:v)-amount)); }
        finally { lock.unlock(); }
    }
    private static final class ResourceUnitLease extends ResourceUnit {
        private final ResourceUnit original; private final double amount; private final ResourceManager owner;
        ResourceUnitLease(ResourceUnit original,double amount,ResourceManager owner) {
            super(original.id(),original.name(),original.type(),original.capacity(),original.capacityUnit(),original.storageClass(),original.capabilities());
            this.original=original; this.amount=amount; this.owner=owner;
        }
        @Override public String name(){return original.name();}
        @Override public void setAvailable(boolean v){original.setAvailable(v);}
        @Override public boolean isAvailable(){return original.isAvailable();}
        public void close(){owner.release(id(),amount);}
    }
    public void release(ResourceUnit lease) {
        if (lease instanceof ResourceUnitLease l) l.close();
    }
    public void setAvailable(long id, boolean available) {
        lock.lock(); try { resources.stream().filter(r->r.id()==id).findFirst().ifPresent(r->r.setAvailable(available)); }
        finally { lock.unlock(); }
    }
    public double used(long id) { lock.lock(); try { return usedCapacity.getOrDefault(id,0.0); } finally {lock.unlock();} }
}
