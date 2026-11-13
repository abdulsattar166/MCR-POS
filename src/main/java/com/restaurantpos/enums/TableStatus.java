package com.restaurantpos.enums;

public enum TableStatus { AVAILABLE("Available"), OCCUPIED("Occupied"), RESERVED("Reserved"), CLEANING("Cleaning"), OUT_OF_SERVICE("Out of Service");
    private final String label;
    TableStatus(String label) { this.label = label; }
    public String label() { return label; }
}
