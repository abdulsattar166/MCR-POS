package com.restaurantpos.enums;

public enum Role {
    OWNER, MANAGER, CASHIER, INVENTORY;

    public String label() {
        return switch (this) {
            case OWNER -> "Owner";
            case MANAGER -> "Manager";
            case CASHIER -> "Cashier";
            case INVENTORY -> "Inventory Staff";
        };
    }
}
