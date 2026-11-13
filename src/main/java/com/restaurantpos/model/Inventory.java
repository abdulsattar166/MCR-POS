package com.restaurantpos.model;

import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class Inventory {
    private Inventory() {}

    public record Ingredient(long id, String sku, String name, String unit, BigDecimal stockQty, BigDecimal minQty,
                             BigDecimal maxQty, BigDecimal avgCost, boolean active) {
        public BigDecimal value() { return Money.mul(stockQty, avgCost); }
        public boolean low() { return stockQty.compareTo(minQty) <= 0; }
        public boolean out() { return stockQty.signum() <= 0; }
        @Override public String toString() { return name + " (" + unit + ")"; }
    }

    public record Supplier(long id, String name, String contact, String phone, String email, String address,
                           String terms, boolean active) {
        @Override public String toString() { return name; }
    }

    public record PurchaseOrder(long id, String poNo, long supplierId, String supplierName, String status,
                                LocalDate orderDate, LocalDate expectedDate, BigDecimal subtotal, BigDecimal tax,
                                BigDecimal total, String notes, String createdBy) {}

    public record PurchaseLine(long id, long poId, long ingredientId, String ingredientName, BigDecimal qty,
                               BigDecimal unitCost, BigDecimal receivedQty) {
        public BigDecimal lineTotal() { return Money.mul(qty, unitCost); }
    }

    public record StockMovement(long id, long ingredientId, String ingredientName, String type, BigDecimal qty,
                                BigDecimal unitCost, String refType, Long refId, String notes, String createdBy,
                                LocalDateTime createdAt) {}

    public record RecipeLine(long id, long productId, String productName, long ingredientId, String ingredientName,
                             BigDecimal qty, String unit, BigDecimal unitCost) {
        public BigDecimal cost() { return Money.mul(qty, unitCost); }
    }

    public record ExpenseCategory(long id, String name) {
        @Override public String toString() { return name; }
    }

    public record Expense(long id, long categoryId, String categoryName, BigDecimal amount, LocalDate expenseDate,
                          String paymentMethod, String description, String responsible, String invoicePath,
                          String status, String approvedBy, LocalDateTime createdAt) {}

    public record Customer(long id, String name, String phone, String address, String email, String notes,
                           LocalDateTime createdAt) {
        @Override public String toString() { return name + (phone == null || phone.isBlank() ? "" : " — " + phone); }
    }

    public record CustomerLedgerEntry(long id, long customerId, Long orderId, String orderNo, String entryType,
                                      BigDecimal amount, BigDecimal signedDelta, BigDecimal balanceAfter,
                                      String paymentMethod, String reference, String description,
                                      long staffId, String staffName, Long shiftId, LocalDateTime createdAt) {}

    public record CustomerLedgerSummary(Customer customer, BigDecimal totalCredit, BigDecimal totalReceived,
                                        BigDecimal currentBalance) {}
}
