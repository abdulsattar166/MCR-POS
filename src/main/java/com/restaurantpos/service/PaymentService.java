package com.restaurantpos.service;

import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.OrderRepository;
import com.restaurantpos.enums.*;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Sales.*;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.util.List;

public final class PaymentService {
    private final OrderRepository orders;
    private final OrderService orderService;
    private final ShiftService shifts;
    private final SettingsService settings;
    private final AuditService audit;
    private final Database db = Database.get();
    private final com.restaurantpos.dao.InventoryRepository inventory = new com.restaurantpos.dao.InventoryRepository();

    public PaymentService(OrderRepository orders, OrderService orderService, ShiftService shifts,
                          SettingsService settings, AuditService audit) {
        this.orders = orders;
        this.orderService = orderService;
        this.shifts = shifts;
        this.settings = settings;
        this.audit = audit;
    }

    public record PaymentInput(PaymentMethod method, BigDecimal amount, BigDecimal tendered, String reference, String notes) {}
    public record CheckoutResult(long orderId, String orderNo, BigDecimal total, BigDecimal paid, BigDecimal change) {}

    public CheckoutResult checkout(Cart cart, List<PaymentInput> inputs, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        if (inputs == null || inputs.isEmpty()) throw new AppException("Record at least one payment.");
        Shift currentShift = shifts.current(session.userId());
        if (currentShift == null && session.role() == Role.CASHIER) {
            currentShift = shifts.ensureActiveShiftForCashier(session.user(), session);
        }
        final Shift shift = currentShift;
        cart.shiftId = shift == null ? null : shift.id();
        orderService.validate(cart, session);
        Cart.Totals totals = orderService.quote(cart);

        BigDecimal sum = Money.ZERO;
        BigDecimal changeTotal = Money.ZERO;
        int nonCash = 0;
        for (PaymentInput p : inputs) {
            if (p.amount() == null || p.amount().signum() <= 0) throw new AppException("Each payment amount must be greater than zero.");
            if (p.method() == null) throw new AppException("Select a payment method.");
            sum = sum.add(p.amount());
            if (p.method() == PaymentMethod.CASH) {
                BigDecimal tendered = p.tendered() == null ? p.amount() : p.tendered();
                if (tendered.compareTo(p.amount()) < 0) throw new AppException("Cash tendered is less than the amount applied.");
                changeTotal = changeTotal.add(tendered.subtract(p.amount()));
            } else {
                nonCash++;
                if (p.method() == PaymentMethod.EASYPAISA || p.method() == PaymentMethod.JAZZCASH
                        || p.method() == PaymentMethod.BANK_TRANSFER) {
                    if (p.reference() == null || p.reference().isBlank()) {
                        throw new AppException("Enter the " + p.method().label() + " transaction reference.");
                    }
                }
            }
        }
        if (sum.compareTo(totals.total) > 0) throw new AppException("Payments exceed the payable amount of " + Money.fmt(totals.total) + ".");
        boolean fullyPaid = sum.compareTo(totals.total) >= 0;
        if (!fullyPaid) {
            boolean credit = inputs.stream().anyMatch(p -> p.method() == PaymentMethod.CREDIT);
            if (!credit && !settings.allowPartialPayment()) {
                throw new AppException("Payment is short by " + Money.fmt(Money.sub(totals.total, sum))
                        + ". Record the balance as a Credit payment or collect the full amount.");
            }
        }

        final BigDecimal paid = sum;
        final BigDecimal change = changeTotal;
        final BigDecimal total = totals.total;
        final boolean hadDigital = nonCash > 0;
        return db.tx(c -> {
            long orderId = orderService.createOrder(cart, false, session);
            for (PaymentInput p : inputs) {
                BigDecimal tendered = p.method() == PaymentMethod.CASH
                        ? (p.tendered() == null ? p.amount() : p.tendered()) : p.amount();
                BigDecimal changeFor = p.method() == PaymentMethod.CASH ? Money.maxZero(tendered.subtract(p.amount())) : Money.ZERO;
                orders.addPayment(orderId, cart.shiftId, p.method(), p.amount(), tendered, changeFor,
                        p.reference() == null ? "" : p.reference().trim(), "COMPLETED", true,
                        session.userId(), p.notes() == null ? "" : p.notes());
            }
            PaymentStatus ps = fullyPaid ? PaymentStatus.PAID : PaymentStatus.PARTIAL;
            OrderStatus os = fullyPaid ? OrderStatus.CONFIRMED : OrderStatus.PENDING_PAYMENT;
            orders.updateTotals(orderId, totals.subtotal, totals.discount, totals.tax, totals.service, totals.delivery,
                    total, os, ps);
            orders.setPaidTotals(orderId, paid, change);
            orders.addEvent(orderId, session.userId(), "PAYMENT",
                    Money.plain(paid) + " received via " + inputs.size() + " payment(s)");
            audit.log("PAYMENT_RECORDED", "orders", orderId,
                    orderService.find(orderId).orderNo() + " — " + Money.plain(paid) + " / " + ps.label());
            if (hadDigital) {
                audit.notify("PAYMENT", "Digital payment recorded",
                        "Order " + orderService.find(orderId).orderNo() + " includes manually confirmed digital payment.", "INFO");
            }
            if (ps == PaymentStatus.PAID) {
                inventory.consumeForOrder(orderId, session.username());
            }
            Order order = orders.find(orderId);
            return new CheckoutResult(orderId, order.orderNo(), total, paid, change);
        });
    }

    public CheckoutResult recordBalancePayment(long orderId, List<PaymentInput> inputs, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (order.status() == OrderStatus.CANCELLED) throw new AppException("A cancelled order cannot take payment.");
        if (order.paymentStatus() == PaymentStatus.PAID) throw new AppException("This order is already fully paid.");
        List<OrderItem> orderItems = orders.items(orderId);
        if (orderItems.isEmpty()) {
            throw new AppException("Cannot complete payment for an empty order. Add at least one item first.");
        }
        BigDecimal alreadyPaid = orders.totalPaid(orderId);
        if (alreadyPaid.compareTo(order.total()) > 0) {
            throw new AppException("Amount already paid (" + Money.fmt(alreadyPaid) + ") exceeds updated order total ("
                    + Money.fmt(order.total()) + "). Process a refund or adjustment before completing.");
        }
        BigDecimal balance = Money.maxZero(Money.sub(order.total(), alreadyPaid));
        if (balance.signum() <= 0) throw new AppException("This order has no outstanding balance.");
        if (inputs == null || inputs.isEmpty()) throw new AppException("Record at least one payment.");
        Shift currentShift = shifts.current(session.userId());
        if (currentShift == null && session.role() == Role.CASHIER) {
            currentShift = shifts.ensureActiveShiftForCashier(session.user(), session);
        }
        final Shift shift = currentShift;

        BigDecimal sum = Money.ZERO;
        BigDecimal change = Money.ZERO;
        for (PaymentInput p : inputs) {
            if (p.amount() == null || p.amount().signum() <= 0) throw new AppException("Payment amount must be greater than zero.");
            if (p.amount().compareTo(balance) > 0) throw new AppException("Payment exceeds the outstanding balance of " + Money.fmt(balance) + ".");
            if (p.method() == PaymentMethod.CASH) {
                BigDecimal tendered = p.tendered() == null ? p.amount() : p.tendered();
                if (tendered.compareTo(p.amount()) < 0) throw new AppException("Cash received is less than the applied amount.");
                change = change.add(tendered.subtract(p.amount()));
            } else if ((p.method() == PaymentMethod.EASYPAISA || p.method() == PaymentMethod.JAZZCASH
                    || p.method() == PaymentMethod.BANK_TRANSFER) && (p.reference() == null || p.reference().isBlank())) {
                throw new AppException("Enter the " + p.method().label() + " transaction reference.");
            }
            sum = sum.add(p.amount());
        }
        if (sum.compareTo(balance) < 0 && !inputs.stream().anyMatch(p -> p.method() == PaymentMethod.CREDIT)
                && !settings.allowPartialPayment()) {
            throw new AppException("Payment is short by " + Money.fmt(Money.sub(balance, sum)) + ".");
        }
        BigDecimal paidNow = sum;
        BigDecimal changeNow = change;
        return db.tx(c -> {
            for (PaymentInput p : inputs) {
                BigDecimal tendered = p.method() == PaymentMethod.CASH ? (p.tendered() == null ? p.amount() : p.tendered()) : p.amount();
                BigDecimal ch = p.method() == PaymentMethod.CASH ? Money.maxZero(tendered.subtract(p.amount())) : Money.ZERO;
                orders.addPayment(orderId, shift == null ? null : shift.id(), p.method(), p.amount(), tendered, ch,
                        p.reference() == null ? "" : p.reference().trim(), "COMPLETED", true, session.userId(),
                        p.notes() == null ? "" : p.notes());
            }
            BigDecimal totalPaid = orders.totalPaid(orderId);
            boolean fullyPaid = totalPaid.compareTo(order.total()) >= 0;
            PaymentStatus status = fullyPaid ? PaymentStatus.PAID
                    : totalPaid.signum() > 0 ? PaymentStatus.PARTIAL : PaymentStatus.UNPAID;
            BigDecimal totalChange = Money.add(order.changeDue(), changeNow);
            orders.setPaidTotals(orderId, totalPaid, totalChange);
            orders.updatePaymentStatus(orderId, status);
            if (fullyPaid) {
                OrderStatus newStatus = order.status() == OrderStatus.CANCELLED
                        ? OrderStatus.CANCELLED : OrderStatus.COMPLETED;
                orders.updateStatus(orderId, newStatus, order.kitchenStatus(), status);
                if (order.tableId() != null && !orders.hasOtherOpenOrderForTable(order.tableId(), orderId)) {
                    db.update("UPDATE dining_tables SET status='AVAILABLE' WHERE id=?", order.tableId());
                }
                inventory.consumeForOrder(orderId, session.username());
            }
            orders.addEvent(orderId, session.userId(), "PAYMENT_BALANCE", Money.plain(paidNow) + " balance payment");
            audit.log("PAYMENT_BALANCE_RECORDED", "orders", orderId, order.orderNo() + " — " + Money.plain(paidNow));
            return new CheckoutResult(orderId, order.orderNo(), order.total(), totalPaid, totalChange);
        });
    }

    public void refund(long paymentId, BigDecimal amount, String reason, Session session) {
        Access.requireManager(session);
        if (reason == null || reason.isBlank()) throw new AppException("A refund reason is required.");
        Payment payment = orders.payment(paymentId);
        if (payment == null) throw new AppException("Payment not found.");
        if (amount == null || amount.signum() <= 0) throw new AppException("Refund amount must be greater than zero.");
        BigDecimal refundable = payment.refundable();
        if (amount.compareTo(refundable) > 0) {
            throw new AppException("Refund exceeds the remaining refundable amount of " + Money.fmt(refundable) + ".");
        }
        db.txRun(c -> {
            orders.addRefund(paymentId, payment.orderId(), amount, reason.trim(), session.username(), session.username());
            orders.addRefundedAmount(paymentId, amount);
            BigDecimal totalPaid = orders.totalPaid(payment.orderId());
            BigDecimal refunded = orders.totalRefunded(payment.orderId());
            PaymentStatus status = refunded.compareTo(totalPaid) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            orders.updatePaymentStatus(payment.orderId(), status);
            orders.addEvent(payment.orderId(), session.userId(), "REFUND", Money.plain(amount) + " — " + reason);
            audit.log("REFUND_RECORDED", "payments", paymentId,
                    "Order #" + payment.orderId() + " refund " + Money.plain(amount) + " — " + reason);
            audit.notify("REFUND", "Refund recorded",
                    "Refund of " + Money.fmt(amount) + " recorded for order #" + payment.orderId() + ".", "WARNING");
        });
    }

    public BigDecimal refundedForOrder(long orderId) { return orders.totalRefunded(orderId); }
    public List<Payment> payments(long orderId) { return orders.payments(orderId); }
    public List<Refund> refunds(long orderId) { return orders.refunds(orderId); }
}
