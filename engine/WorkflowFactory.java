package edu.portflow.engine;

import edu.portflow.model.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkflowFactory {
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000L);
    public List<WorkflowTask> create(Cargo cargo) {
        List<WorkflowTask> tasks = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        long unload = ids.incrementAndGet();
        tasks.add(task(unload,cargo,"Unload cargo","UNLOAD",cargo.category()==CargoCategory.PRODUCE || cargo.category()==CargoCategory.FROZEN_FOOD ? "FORKLIFT":"CRANE",
                cargo.profile().storageClass(),Math.max(1,(int)Math.ceil(cargo.quantity()/50.0)),Set.of(),now));
        long previous = unload;
        if (cargo.profile().requiresInspection()) {
            long inspection=ids.incrementAndGet();
            tasks.add(task(inspection,cargo,"Inspect cargo","INSPECTION","INSPECTION_STATION","GENERAL",2,Set.of(previous),now));
            previous=inspection;
        }
        String storageType = switch(cargo.category()) {
            case PRODUCE, CHILLED_FOOD -> "COLD_STORAGE";
            case FROZEN_FOOD -> "COLD_STORAGE";
            case ELECTRONICS -> "PROTECTED_STORAGE";
            case COAL -> "CONVEYOR";
            case CEMENT -> "DRY_STORAGE";
            default -> "DRY_STORAGE";
        };
        long store=ids.incrementAndGet();
        tasks.add(task(store,cargo,"Transfer to compatible storage","STORE",storageType,cargo.profile().storageClass(),
                Math.max(1,(int)Math.ceil(cargo.quantity()/100.0)),Set.of(previous),now));
        long dispatch=ids.incrementAndGet();
        String dispatchResource = cargo.category()==CargoCategory.COAL ? "CONVEYOR" : "FORKLIFT";
        tasks.add(task(dispatch,cargo,"Dispatch cargo","DISPATCH",dispatchResource,"GENERAL",2,Set.of(store),now));
        return tasks;
    }
    private WorkflowTask task(long id,Cargo c,String name,String type,String resourceType,String storageClass,int mins,Set<Long> deps,LocalDateTime now) {
        return new WorkflowTask(id,c.id(),name,type,c.priority(),mins,now,c.deadlineTime(),c.expiryTime(),
                resourceType,storageClass,Math.max(1,c.quantity()),deps);
    }
}
