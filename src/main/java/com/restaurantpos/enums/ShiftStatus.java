package com.restaurantpos.enums;

public enum ShiftStatus { OPEN("Open"), CLOSED("Closed") ;
    private final String label;
    ShiftStatus(String label) { this.label = label; }
    public String label() { return label; }
}
