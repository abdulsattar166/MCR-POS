package com.restaurantpos.enums;

public enum OrderType {
    DINE_IN("Dine-In"), TAKEAWAY("Takeaway"), DELIVERY("Delivery"), PICKUP("Pickup / Pre-Order"),
    SIDE_ORDER("Side Order / No Table");

    private final String label;
    OrderType(String label) { this.label = label; }
    public String label() { return label; }
}
