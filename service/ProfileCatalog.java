package edu.portflow.service;

import edu.portflow.model.*;
import java.util.EnumMap;
import java.util.Map;

public final class ProfileCatalog {
    private final Map<CargoCategory, HandlingProfile> profiles = new EnumMap<>(CargoCategory.class);
    public ProfileCatalog() {
        profiles.put(CargoCategory.PRODUCE, new HandlingProfile("PRODUCE","Fresh produce","CHILLED",2.0,8.0,30,"PERISHABLE",true));
        profiles.put(CargoCategory.FROZEN_FOOD, new HandlingProfile("FROZEN","Frozen food","FROZEN",-25.0,-18.0,10,"COLD_CHAIN",false));
        profiles.put(CargoCategory.CHILLED_FOOD, new HandlingProfile("CHILLED_FOOD","Chilled food","CHILLED",0.0,5.0,15,"COLD_CHAIN",false));
        profiles.put(CargoCategory.ELECTRONICS, new HandlingProfile("ELECTRONICS","Electronics","PROTECTED",null,null,180,"FRAGILE",true));
        profiles.put(CargoCategory.COAL, new HandlingProfile("COAL","Coal / dry bulk","DRY_BULK",null,null,null,"BULK",false));
        profiles.put(CargoCategory.CEMENT, new HandlingProfile("CEMENT","Cement / construction","DRY",null,null,240,"MOISTURE_SENSITIVE",false));
        profiles.put(CargoCategory.GENERAL, new HandlingProfile("GENERAL","General cargo","GENERAL",null,null,null,"STANDARD",false));
    }
    public HandlingProfile get(CargoCategory category) { return profiles.get(category); }
    public Map<CargoCategory, HandlingProfile> all() { return Map.copyOf(profiles); }
}
