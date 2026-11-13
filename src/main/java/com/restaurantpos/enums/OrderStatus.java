package com.restaurantpos.enums;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    DRAFT("Draft"), PENDING_PAYMENT("Pending Payment"), CONFIRMED("Confirmed"), PREPARING("Preparing"),
    READY("Ready"), SERVED("Served"), COMPLETED("Completed"), CANCELLED("Cancelled");

    private final String label;
    OrderStatus(String label) { this.label = label; }
    public String label() { return label; }

    public Set<OrderStatus> editableStates() { return EnumSet.of(DRAFT, PENDING_PAYMENT, CONFIRMED); }
}
