package com.restaurantpos.enums;

public enum StockMoveType { PURCHASE("Purchase Receipt"), SALE_CONSUMPTION("Sale Consumption"), WASTAGE("Wastage"),
    ADJUSTMENT("Adjustment"), RETURN("Supplier Return"), OPENING("Opening Stock"), COUNT("Stock Count");
    private final String label;
    StockMoveType(String label) { this.label = label; }
    public String label() { return label; }
}
