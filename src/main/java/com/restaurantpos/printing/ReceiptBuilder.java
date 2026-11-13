package com.restaurantpos.printing;

import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.service.SettingsService;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ReceiptBuilder {
    private static final int WIDTH = 48;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a");
    private final SettingsService settings;

    public ReceiptBuilder(SettingsService settings) { this.settings = settings; }

    public String customerReceipt(Order order, List<OrderItem> items, List<Payment> payments, String sequenceId) {
        return customerReceipt(order, items, payments, sequenceId, false);
    }

    public String customerReceipt(Order order, List<OrderItem> items, List<Payment> payments, String sequenceId, boolean reprint) {
        StringBuilder b = new StringBuilder();
        String header = settings.receiptHeader();
        for (String line : header.split("\\R")) center(b, line);
        String addr = settings.restaurantAddress();
        if (addr != null && !addr.isBlank() && !header.toLowerCase().contains(addr.toLowerCase())) {
            center(b, addr);
        }
        divider(b, '=');
        if (order.paymentStatus() == com.restaurantpos.enums.PaymentStatus.PAID) {
            center(b, "PAID CUSTOMER RECEIPT");
            divider(b, '-');
        }
        if (reprint) {
            center(b, "*** REPRINT / DUPLICATE RECEIPT ***");
            b.append(pair("Receipt Status", "REPRINT (Original Transaction)")).append('\n');
            divider(b, '-');
        }
        b.append(pair("Receipt", sequenceId)).append('\n');
        b.append(pair("Order", order.orderNo())).append('\n');
        b.append(pair("Order Date", order.createdAt() == null ? "" : order.createdAt().format(TIME))).append('\n');
        if (order.completedAt() != null) {
            b.append(pair("Completed", order.completedAt().format(TIME))).append('\n');
        }
        b.append(pair("Cashier", order.cashierName())).append('\n');
        b.append(pair("Type", order.type().label())).append('\n');
        if (order.tableNo() != null) b.append(pair("Table", order.tableNo())).append('\n');
        if (order.tokenNo() != null) b.append(pair("Token", order.tokenNo())).append('\n');
        if (order.customerName() != null && !order.customerName().isBlank()) b.append(pair("Customer", order.customerName())).append('\n');
        divider(b, '-');
        b.append(String.format("%-20s %3s %10s %11s%n", "ITEM", "QTY", "PRICE", "TOTAL"));
        divider(b, '-');
        for (OrderItem i : items) {
            b.append(String.format("%-20s %3d %10s %11s%n", clip(i.name(), 20), i.qty(), Money.plain(i.unitPrice()), Money.plain(i.lineTotal())));
            if (i.addons() != null && !i.addons().isBlank()) b.append("  + ").append(clip(i.addons(), 43)).append('\n');
            if (i.instructions() != null && !i.instructions().isBlank()) b.append("  * ").append(clip(i.instructions(), 43)).append('\n');
        }
        divider(b, '-');
        b.append(pair("Subtotal", Money.plain(order.subtotal()))).append('\n');
        if (Money.nz(order.discountTotal()).signum() > 0) b.append(pair("Discounts", "-" + Money.plain(order.discountTotal()))).append('\n');
        if (Money.nz(order.taxTotal()).signum() > 0) b.append(pair("Tax", Money.plain(order.taxTotal()))).append('\n');
        if (Money.nz(order.serviceCharge()).signum() > 0) b.append(pair("Service charge", Money.plain(order.serviceCharge()))).append('\n');
        if (Money.nz(order.deliveryCharge()).signum() > 0) b.append(pair("Delivery", Money.plain(order.deliveryCharge()))).append('\n');
        divider(b, '=');
        b.append(pair("TOTAL  PKR", Money.plain(order.total()))).append('\n');
        b.append(pair("Paid", Money.plain(order.paidTotal()))).append('\n');
        if (Money.nz(order.creditAmount()).signum() > 0) {
            b.append(pair("On Account (Ledger)", Money.plain(order.creditAmount()))).append('\n');
        }
        if (Money.nz(order.changeDue()).signum() > 0) b.append(pair("Change", Money.plain(order.changeDue()))).append('\n');
        BigDecimal balance = order.balance();
        if (balance.signum() > 0) {
            b.append(pair("Balance due", Money.plain(balance))).append('\n');
        }
        if (payments != null && !payments.isEmpty()) {
            divider(b, '-');
            for (Payment p : payments) {
                b.append(pair(p.method().label(), Money.plain(p.amount()))).append('\n');
                if (p.reference() != null && !p.reference().isBlank()) b.append("  Ref: ").append(clip(p.reference(), 41)).append('\n');
            }
        }
        divider(b, '=');
        if (order.paymentStatus() == com.restaurantpos.enums.PaymentStatus.PAID) {
            center(b, "*** PAID ***");
        } else if (order.paymentStatus() == com.restaurantpos.enums.PaymentStatus.ON_ACCOUNT) {
            center(b, "*** ON ACCOUNT — CUSTOMER LEDGER ***");
            center(b, "Charged to Customer Credit Ledger");
        } else {
            center(b, "*** UNPAID — PAYMENT PENDING ***");
            center(b, "Payment has not yet been received.");
            center(b, "Please pay at the cashier counter.");
        }
        divider(b, '=');
        for (String line : settings.receiptFooter().split("\\R")) center(b, line);
        b.append('\n');
        center(b, "Powered by ZS Sattar");
        b.append('\n');
        return b.toString();
    }

    public String ledgerPaymentReceipt(String customerName, String customerPhone, CustomerLedgerEntry entry) {
        return ledgerReceipt(customerName, customerPhone, "", entry, false);
    }

    public String ledgerReceipt(String customerName, String customerPhone, String customerAddress,
                                CustomerLedgerEntry entry, boolean reprint) {
        boolean isCredit = "CREDIT_SALE".equalsIgnoreCase(entry.entryType());
        BigDecimal prevBalance = Money.nz(entry.balanceAfter()).subtract(Money.nz(entry.signedDelta()));

        StringBuilder b = new StringBuilder();
        center(b, settings.restaurantName().toUpperCase());
        String addr = settings.restaurantAddress();
        if (addr != null && !addr.isBlank()) center(b, addr);
        String phone = settings.restaurantPhone();
        if (phone != null && !phone.isBlank()) center(b, "Tel: " + phone);
        center(b, "CUSTOMER LEDGER RECEIPT");
        divider(b, '=');
        if (reprint) {
            center(b, "*** REPRINT / DUPLICATE RECEIPT ***");
            divider(b, '-');
        }
        b.append(pair("Receipt #", "CLR-" + entry.id())).append('\n');
        b.append(pair("Date & Time", entry.createdAt() == null ? "" : entry.createdAt().format(TIME))).append('\n');
        b.append(pair("Customer", clip(customerName == null ? "-" : customerName, 34))).append('\n');
        if (customerPhone != null && !customerPhone.isBlank()) b.append(pair("Phone", customerPhone)).append('\n');
        if (customerAddress != null && !customerAddress.isBlank()) b.append(pair("Address", clip(customerAddress, 34))).append('\n');
        if (entry.orderNo() != null && !entry.orderNo().isBlank()) b.append(pair("Related Order #", entry.orderNo())).append('\n');
        if (entry.staffName() != null && !entry.staffName().isBlank()) b.append(pair("Handled By", clip(entry.staffName(), 32))).append('\n');
        divider(b, '-');
        b.append(pair("Transaction Type", isCredit ? "Credit Added (+)" : "Payment Received (-)")).append('\n');
        if (!isCredit) {
            b.append(pair("Payment Method", entry.paymentMethod() == null || entry.paymentMethod().isBlank() ? "Cash" : entry.paymentMethod())).append('\n');
        }
        if (entry.reference() != null && !entry.reference().isBlank()) b.append(pair("Reference", entry.reference())).append('\n');
        b.append(pair("Previous Balance", Money.fmt(prevBalance))).append('\n');
        b.append(pair(isCredit ? "Credit Added" : "Amount Received", Money.fmt(entry.amount()))).append('\n');
        b.append(pair("Remaining Balance", Money.fmt(entry.balanceAfter()))).append('\n');
        b.append(pair("Status", isCredit ? "CHARGED TO LEDGER" : "PAYMENT CONFIRMED")).append('\n');
        if (entry.description() != null && !entry.description().isBlank()) {
            divider(b, '-');
            b.append("Note: ").append(clip(entry.description(), 41)).append('\n');
        }
        divider(b, '=');
        for (String line : settings.receiptFooter().split("\\R")) {
            if (!line.isBlank()) center(b, line.trim());
        }
        b.append('\n');
        center(b, "Powered by ZS Sattar");
        b.append('\n');
        return b.toString();
    }

    private static void center(StringBuilder b, String text) {
        String t = clip(text == null ? "" : text, WIDTH);
        int pad = Math.max(0, (WIDTH - t.length()) / 2);
        b.append(" ".repeat(pad)).append(t).append('\n');
    }

    private static void divider(StringBuilder b, char ch) { b.append(String.valueOf(ch).repeat(WIDTH)).append('\n'); }

    private static String pair(String left, String right) {
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        int dots = Math.max(1, WIDTH - l.length() - r.length());
        return l + " ".repeat(dots) + r;
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
}
