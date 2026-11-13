package com.restaurantpos.enums;

public enum PaymentStatus {
    UNPAID("Unpaid"), PARTIAL("Partially Paid"), ON_ACCOUNT("Credit / On Account"), PAID("Paid"),
    REFUNDED("Refunded"), PARTIALLY_REFUNDED("Partially Refunded");
    private final String label;
    PaymentStatus(String label) { this.label = label; }
    public String label() { return label; }
}
