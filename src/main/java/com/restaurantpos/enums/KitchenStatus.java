package com.restaurantpos.enums;

public enum KitchenStatus { NONE("Not Sent"), QUEUED("Received"), PREPARING("Preparing"), READY("Ready"), COMPLETED("Completed");
    private final String label;
    KitchenStatus(String label) { this.label = label; }
    public String label() { return label; }
}
