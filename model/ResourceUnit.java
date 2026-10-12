package edu.portflow.model;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class ResourceUnit {
    private final long id;
    private final String name;
    private final String type;
    private final double capacity;
    private final String capacityUnit;
    private final String storageClass;
    private final Set<String> capabilities;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public ResourceUnit(long id, String name, String type, double capacity, String capacityUnit,
                        String storageClass, Set<String> capabilities) {
        if (capacity <= 0) throw new IllegalArgumentException("Resource capacity must be positive.");
        this.id=id; this.name=name; this.type=type; this.capacity=capacity;
        this.capacityUnit=capacityUnit; this.storageClass=storageClass;
        this.capabilities=Set.copyOf(capabilities);
    }
    public long id(){return id;} public String name(){return name;} public String type(){return type;}
    public double capacity(){return capacity;} public String capacityUnit(){return capacityUnit;}
    public String storageClass(){return storageClass;} public Set<String> capabilities(){return capabilities;}
    public boolean isAvailable(){return available.get();}
    public void setAvailable(boolean value){available.set(value);}
    public boolean supports(String capability){return capabilities.contains(capability);}
}
