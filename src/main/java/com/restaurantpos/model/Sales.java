package com.restaurantpos.model;

import com.restaurantpos.enums.*;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class Sales {
    private Sales() {}

    public record Order(long id, String orderNo, OrderType type, OrderStatus status, Long tableId, String tableNo,
                        Long waiterId, String waiterName, Long customerId,
                        String customerName, String customerPhone, String deliveryAddress, Long cashierId, String cashierName,
                        Long shiftId, int guestCount, String notes, BigDecimal subtotal, BigDecimal discountTotal,
                        BigDecimal taxTotal, BigDecimal serviceCharge, BigDecimal deliveryCharge, BigDecimal total,
                        BigDecimal paidTotal, BigDecimal changeDue, BigDecimal creditAmount, PaymentStatus paymentStatus, KitchenStatus kitchenStatus,
                        boolean held, String tokenNo, String cancelReason, LocalDateTime createdAt, LocalDateTime completedAt) {
        public Order(long id, String orderNo, OrderType type, OrderStatus status, Long tableId, String tableNo,
                     Long waiterId, String waiterName,
                     String customerName, String customerPhone, String deliveryAddress, Long cashierId, String cashierName,
                     Long shiftId, int guestCount, String notes, BigDecimal subtotal, BigDecimal discountTotal,
                     BigDecimal taxTotal, BigDecimal serviceCharge, BigDecimal deliveryCharge, BigDecimal total,
                     BigDecimal paidTotal, BigDecimal changeDue, PaymentStatus paymentStatus, KitchenStatus kitchenStatus,
                     boolean held, String tokenNo, String cancelReason, LocalDateTime createdAt, LocalDateTime completedAt) {
            this(id, orderNo, type, status, tableId, tableNo, waiterId, waiterName, null, customerName, customerPhone,
                 deliveryAddress, cashierId, cashierName, shiftId, guestCount, notes, subtotal, discountTotal, taxTotal,
                 serviceCharge, deliveryCharge, total, paidTotal, changeDue, Money.ZERO, paymentStatus, kitchenStatus,
                 held, tokenNo, cancelReason, createdAt, completedAt);
        }
        public BigDecimal balance() { return Money.maxZero(Money.sub(total, Money.add(paidTotal, Money.nz(creditAmount)))); }
        public boolean isOpen() { return status != OrderStatus.COMPLETED && status != OrderStatus.CANCELLED; }
        public boolean isDineIn() { return type == OrderType.DINE_IN; }
    }

    public record OrderItem(long id, long orderId, Long productId, Long variantId, String name, String sku,
                            BigDecimal unitPrice, BigDecimal cost, int qty, String addons, String instructions,
                            BigDecimal discount, BigDecimal lineTotal, String status) {
        public BigDecimal gross() { return Money.mul(unitPrice, BigDecimal.valueOf(qty)); }
    }

    public record Payment(long id, long orderId, Long shiftId, PaymentMethod method, BigDecimal amount, BigDecimal tendered,
                          BigDecimal changeDue, String reference, String status, boolean confirmed, String cashierName,
                          LocalDateTime createdAt, BigDecimal refundedAmount, String notes) {
        public BigDecimal refundable() { return Money.maxZero(Money.sub(amount, refundedAmount)); }
    }

    public record Refund(long id, long paymentId, long orderId, BigDecimal amount, String reason, String approvedBy,
                         String createdBy, LocalDateTime createdAt) {}

    public record Shift(long id, long userId, String userName, LocalDateTime openedAt, LocalDateTime closedAt,
                        BigDecimal openingCash, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal difference,
                        ShiftStatus status, String notes, String approvedBy,
                        String shiftName, String scheduledStart, String scheduledEnd) {
        public Shift(long id, long userId, String userName, LocalDateTime openedAt, LocalDateTime closedAt,
                     BigDecimal openingCash, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal difference,
                     ShiftStatus status, String notes, String approvedBy) {
            this(id, userId, userName, openedAt, closedAt, openingCash, expectedCash, countedCash, difference,
                 status, notes, approvedBy, "General Shift", null, null);
        }
        public boolean isOpen() { return status == ShiftStatus.OPEN; }

        public java.time.Duration duration() {
            LocalDateTime end = closedAt != null ? closedAt : LocalDateTime.now();
            return openedAt != null ? java.time.Duration.between(openedAt, end) : java.time.Duration.ZERO;
        }

        public String formattedDuration() {
            java.time.Duration d = duration();
            long hours = d.toHours();
            long minutes = d.toMinutesPart();
            return String.format("%dh %02dm", hours, minutes);
        }
    }

    public record ShiftSchedule(long id, String name, String startTime, String endTime,
                                String workingDays, int graceMinutes, boolean active) {
        @Override public String toString() { return name + " (" + startTime + " – " + endTime + ")"; }
    }

    public record CashMovement(long id, long shiftId, CashMoveType type, BigDecimal amount, String reason,
                               String userName, LocalDateTime createdAt) {}

    public record KitchenTicket(long id, long orderId, String orderNo, String ticketNo, String station,
                                KitchenStatus status, LocalDateTime createdAt, LocalDateTime readyAt,
                                LocalDateTime completedAt, String notes, OrderType orderType, String tableNo) {}

    public record KitchenTicketItem(long id, long ticketId, long orderItemId, String name, int qty, String instructions, String status) {}
}
