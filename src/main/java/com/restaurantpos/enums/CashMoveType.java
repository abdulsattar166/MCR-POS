package com.restaurantpos.enums;

public enum CashMoveType { CASH_IN("Cash In"), CASH_OUT("Cash Out"), SALE("Cash Sale"), REFUND("Cash Refund");
    private final String label;
    CashMoveType(String label) { this.label = label; }
    public String label() { return label; }
}
