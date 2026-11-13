package com.restaurantpos.enums;

public enum PaymentMethod {
    CASH("Cash"), CARD("Debit / Credit Card"), EASYPAISA("Easypaisa"), JAZZCASH("JazzCash"),
    BANK_TRANSFER("Bank Transfer"), CREDIT("Credit / Pay Later");

    private final String label;
    PaymentMethod(String label) { this.label = label; }
    public String label() { return label; }
    public boolean isDigital() { return this != CASH; }
}
