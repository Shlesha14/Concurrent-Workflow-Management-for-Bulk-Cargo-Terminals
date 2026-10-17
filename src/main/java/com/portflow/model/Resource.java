package com.portflow.model;

import java.math.BigDecimal;
import java.util.Set;

public record Resource(int id, String name, String typeCode, BigDecimal capacity, String status, String location,
                       Double tempMinC, Double tempMaxC, Set<String> capabilities, String capacityModel) {
    public boolean isQuantityModel() { return "QUANTITY".equals(capacityModel); }
}
