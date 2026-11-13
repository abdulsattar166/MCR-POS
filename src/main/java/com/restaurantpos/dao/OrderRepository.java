package com.restaurantpos.dao;

import com.restaurantpos.enums.*;
import com.restaurantpos.model.Sales.*;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class OrderRepository {
    private final Database db = Database.get();

    public record NewOrder(String orderNo, OrderType type, Long tableId, Long waiterId, Long customerId, long cashierId, Long shiftId,
                           int guestCount, String customerName, String customerPhone, String deliveryAddress,
                           String notes, String tokenNo, boolean held, BigDecimal deliveryCharge) {}
    public record NewItem(long orderId, Long productId, Long variantId, String name, String sku, BigDecimal unitPrice,
                          BigDecimal cost, int qty, String addons, String instructions, BigDecimal discount, BigDecimal lineTotal) {}
    public record OrderEvent(long id, long orderId, Long userId, String action, String details, LocalDateTime createdAt) {}

    private Order mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal creditAmt = Money.ZERO;
        try { creditAmt = Rows.bd(rs, "credit_amount"); } catch (Exception ignored) {}
        return new Order(rs.getLong("id"), rs.getString("order_no"), OrderType.valueOf(rs.getString("type")),
                OrderStatus.valueOf(rs.getString("status")), Rows.longOrNull(rs, "table_id"), rs.getString("table_no"),
                Rows.longOrNull(rs, "waiter_id"), rs.getString("waiter_name"), Rows.longOrNull(rs, "customer_id"),
                rs.getString("customer_name"), rs.getString("customer_phone"), rs.getString("delivery_address"),
                rs.getLong("cashier_id"), rs.getString("cashier_name"), Rows.longOrNull(rs, "shift_id"),
                rs.getInt("guest_count"), rs.getString("notes"), Rows.bd(rs, "subtotal"), Rows.bd(rs, "discount_total"),
                Rows.bd(rs, "tax_total"), Rows.bd(rs, "service_charge"), Rows.bd(rs, "delivery_charge"),
                Rows.bd(rs, "total"), Rows.bd(rs, "paid_total"), Rows.bd(rs, "change_due"), creditAmt,
                PaymentStatus.valueOf(rs.getString("payment_status")), KitchenStatus.valueOf(rs.getString("kitchen_status")),
                Rows.bool(rs, "held"), rs.getString("token_no"), rs.getString("cancel_reason"),
                Rows.dt(rs, "created_at"), Rows.dt(rs, "completed_at"));
    }

    private static final String ORDER_SELECT = """
            SELECT o.*, t.table_no, u.full_name AS cashier_name,
                   CASE
                       WHEN o.waiter_id IS NULL THEN NULL
                       WHEN w.waiter_no IS NOT NULL AND w.waiter_no > 0 THEN 'Waiter ' || w.waiter_no
                       ELSE 'Waiter ' || o.waiter_id
                   END AS waiter_name
            FROM orders o
            LEFT JOIN dining_tables t ON t.id=o.table_id
            LEFT JOIN users u ON u.id=o.cashier_id
            LEFT JOIN users w ON w.id=o.waiter_id
            """;

    public long create(NewOrder n) {
        LocalDateTime now = LocalDateTime.now();
        return db.insert("""
                INSERT INTO orders(order_no,type,status,table_id,waiter_id,customer_id,cashier_id,shift_id,guest_count,customer_name,
                customer_phone,delivery_address,notes,delivery_charge,held,token_no,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, n.orderNo(), n.type().name(), OrderStatus.DRAFT.name(), n.tableId(), n.waiterId(), n.customerId(), n.cashierId(),
                n.shiftId(), n.guestCount(), n.customerName(), n.customerPhone(), n.deliveryAddress(), n.notes(),
                n.deliveryCharge(), n.held(), n.tokenNo(), now, now);
    }

    public void updateTotals(long id, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal service,
                             BigDecimal delivery, BigDecimal total, OrderStatus status, PaymentStatus paymentStatus) {
        db.update("""
                UPDATE orders SET subtotal=?, discount_total=?, tax_total=?, service_charge=?, delivery_charge=?,
                total=?, status=?, payment_status=?, updated_at=? WHERE id=?
                """, subtotal, discount, tax, service, delivery, total, status.name(), paymentStatus.name(),
                LocalDateTime.now(), id);
    }

    public void updateTotalsOnly(long id, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal service,
                                 BigDecimal delivery, BigDecimal total) {
        db.update("""
                UPDATE orders SET subtotal=?, discount_total=?, tax_total=?, service_charge=?, delivery_charge=?,
                total=?, updated_at=? WHERE id=?
                """, subtotal, discount, tax, service, delivery, total, LocalDateTime.now(), id);
    }

    public void updateStatus(long id, OrderStatus status, KitchenStatus kitchen, PaymentStatus payment) {
        db.update("UPDATE orders SET status=?, kitchen_status=?, payment_status=?, updated_at=?, completed_at=? WHERE id=?",
                status.name(), kitchen.name(), payment.name(), LocalDateTime.now(),
                status == OrderStatus.COMPLETED ? LocalDateTime.now() : null, id);
    }

    public void updateKitchenStatus(long id, KitchenStatus status) {
        db.update("UPDATE orders SET kitchen_status=?, updated_at=? WHERE id=?", status.name(), LocalDateTime.now(), id);
    }

    public void setHeld(long id, boolean held) {
        db.update("UPDATE orders SET held=?, updated_at=? WHERE id=?", held, LocalDateTime.now(), id);
    }

    public void close(long id, OrderStatus status, String cancelReason, PaymentStatus paymentStatus) {
        db.update("UPDATE orders SET status=?, cancel_reason=?, payment_status=?, updated_at=?, completed_at=? WHERE id=?",
                status.name(), cancelReason, paymentStatus.name(), LocalDateTime.now(),
                status == OrderStatus.CANCELLED ? LocalDateTime.now() : null, id);
    }

    public void setTable(long id, Long tableId) {
        db.update("UPDATE orders SET table_id=?, updated_at=? WHERE id=?", tableId, LocalDateTime.now(), id);
    }

    public void moveItems(long sourceOrderId, long targetOrderId) {
        db.update("UPDATE order_items SET order_id=? WHERE order_id=?", targetOrderId, sourceOrderId);
    }

    public boolean hasOtherOpenOrderForTable(long tableId, Long excludeOrderId) {
        long count = excludeOrderId == null
                ? db.scalarLong("SELECT COUNT(*) FROM orders WHERE table_id=? AND status NOT IN ('COMPLETED','CANCELLED')", tableId)
                : db.scalarLong("SELECT COUNT(*) FROM orders WHERE table_id=? AND id<>? AND status NOT IN ('COMPLETED','CANCELLED')", tableId, excludeOrderId);
        return count > 0;
    }

    public void setCustomer(long id, Long customerId, String name, String phone, String address) {
        db.update("UPDATE orders SET customer_id=?, customer_name=?, customer_phone=?, delivery_address=?, updated_at=? WHERE id=?",
                customerId, name, phone, address, LocalDateTime.now(), id);
    }

    public long addItem(NewItem i) {
        return db.insert("""
                INSERT INTO order_items(order_id,product_id,variant_id,name,sku,unit_price,cost,qty,addons,instructions,
                discount,line_total,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, i.orderId(), i.productId(), i.variantId(), i.name(), i.sku(), i.unitPrice(), i.cost(), i.qty(),
                i.addons(), i.instructions(), i.discount(), i.lineTotal(), "ACTIVE", LocalDateTime.now());
    }

    public void updateItem(long id, int qty, BigDecimal discount, BigDecimal lineTotal) {
        db.update("UPDATE order_items SET qty=?, discount=?, line_total=? WHERE id=?", qty, discount, lineTotal, id);
    }

    public void voidItem(long id) {
        db.update("UPDATE order_items SET status='VOID' WHERE id=?", id);
    }

    public Order find(long id) {
        return db.queryOne(ORDER_SELECT + " WHERE o.id=?", this::mapOrder, id);
    }

    public Order findByNo(String orderNo) {
        return db.queryOne(ORDER_SELECT + " WHERE o.order_no=?", this::mapOrder, orderNo);
    }

    public List<Order> list(java.time.LocalDate from, java.time.LocalDate to, OrderType type, OrderStatus status,
                            Long cashierId, String paymentStatus, String search, int limit) {
        StringBuilder sql = new StringBuilder(ORDER_SELECT).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null) { sql.append(" AND date(o.created_at) >= ?"); args.add(from.toString()); }
        if (to != null) { sql.append(" AND date(o.created_at) <= ?"); args.add(to.toString()); }
        if (type != null) { sql.append(" AND o.type=?"); args.add(type.name()); }
        if (status != null) { sql.append(" AND o.status=?"); args.add(status.name()); }
        if (cashierId != null) { sql.append(" AND o.cashier_id=?"); args.add(cashierId); }
        if (paymentStatus != null && !paymentStatus.isBlank()) { sql.append(" AND o.payment_status=?"); args.add(paymentStatus); }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR o.customer_name LIKE ? OR o.customer_phone LIKE ? OR t.table_no LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q); args.add(q); args.add(q); args.add(q);
        }
        sql.append(" ORDER BY o.id DESC LIMIT ?");
        args.add(limit);
        return db.query(sql.toString(), this::mapOrder, args.toArray());
    }

    public List<Order> heldOrders(Long cashierId) {
        if (cashierId == null) return db.query(ORDER_SELECT + " WHERE o.held=1 AND o.status NOT IN ('COMPLETED','CANCELLED') ORDER BY o.id DESC", this::mapOrder);
        return db.query(ORDER_SELECT + " WHERE o.held=1 AND o.status NOT IN ('COMPLETED','CANCELLED') AND o.cashier_id=? ORDER BY o.id DESC",
                this::mapOrder, cashierId);
    }

    public record OrderStats(
            long totalOrders,
            long draftOrders,
            long completedOrders,
            BigDecimal completedSales,
            BigDecimal paidAmount,
            BigDecimal outstandingBalance
    ) {}

    public OrderStats summaryStats(java.time.LocalDate from, java.time.LocalDate to) {
        StringBuilder where = new StringBuilder(" WHERE status <> 'CANCELLED'");
        List<Object> args = new ArrayList<>();
        if (from != null) { where.append(" AND date(created_at) >= ?"); args.add(from.toString()); }
        if (to != null) { where.append(" AND date(created_at) <= ?"); args.add(to.toString()); }
        String sql = """
                SELECT
                    COUNT(*) AS total_cnt,
                    COALESCE(SUM(CASE WHEN payment_status IN ('UNPAID','PARTIAL') AND status NOT IN ('COMPLETED','CANCELLED') THEN 1 ELSE 0 END), 0) AS draft_cnt,
                    COALESCE(SUM(CASE WHEN status='COMPLETED' OR payment_status IN ('PAID','ON_ACCOUNT') THEN 1 ELSE 0 END), 0) AS complete_cnt,
                    COALESCE(SUM(CASE WHEN status='COMPLETED' OR payment_status IN ('PAID','ON_ACCOUNT') THEN total ELSE 0 END), 0) AS complete_sales,
                    COALESCE(SUM(paid_total), 0) AS paid_sum,
                    COALESCE(SUM(CASE WHEN payment_status IN ('UNPAID','PARTIAL') AND status NOT IN ('COMPLETED','CANCELLED') THEN MAX(0, total - paid_total - credit_amount) ELSE 0 END), 0) AS balance_sum
                FROM orders
                """ + where;
        OrderStats stats = db.queryOne(sql, rs -> new OrderStats(
                rs.getLong("total_cnt"),
                rs.getLong("draft_cnt"),
                rs.getLong("complete_cnt"),
                Money.nz(rs.getBigDecimal("complete_sales")),
                Money.nz(rs.getBigDecimal("paid_sum")),
                Money.nz(rs.getBigDecimal("balance_sum"))
        ), args.toArray());
        return stats != null ? stats : new OrderStats(0, 0, 0, Money.ZERO, Money.ZERO, Money.ZERO);
    }

    public java.util.Map<Long, String> itemSummaries(List<Long> orderIds) {
        java.util.Map<Long, String> map = new java.util.HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) return map;
        String placeholders = orderIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT order_id, qty, name FROM order_items WHERE status<>'VOID' AND order_id IN (" + placeholders + ") ORDER BY id";
        java.util.Map<Long, List<String>> grouped = new java.util.HashMap<>();
        db.query(sql, rs -> {
            long oid = rs.getLong("order_id");
            grouped.computeIfAbsent(oid, k -> new ArrayList<>()).add(rs.getInt("qty") + "x " + rs.getString("name"));
            return null;
        }, orderIds.toArray());
        grouped.forEach((k, v) -> map.put(k, v.size() + " item(s): " + String.join(", ", v)));
        return map;
    }

    public java.util.Map<Long, String> paymentMethods(List<Long> orderIds) {
        java.util.Map<Long, String> map = new java.util.HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) return map;
        String placeholders = orderIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "SELECT DISTINCT order_id, method FROM payments WHERE status='COMPLETED' AND order_id IN (" + placeholders + ") ORDER BY id";
        java.util.Map<Long, List<String>> grouped = new java.util.HashMap<>();
        db.query(sql, rs -> {
            long oid = rs.getLong("order_id");
            String m = rs.getString("method");
            if (m != null && !m.isBlank()) {
                try { m = PaymentMethod.valueOf(m).label(); } catch (Exception ignored) {}
                grouped.computeIfAbsent(oid, k -> new ArrayList<>()).add(m);
            }
            return null;
        }, orderIds.toArray());
        grouped.forEach((k, v) -> map.put(k, String.join(" + ", v)));
        return map;
    }

    public List<Order> draftOrders(String search) {
        StringBuilder sql = new StringBuilder(ORDER_SELECT).append(" WHERE o.payment_status IN ('UNPAID','PARTIAL') AND o.status NOT IN ('COMPLETED','CANCELLED')");
        List<Object> args = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR o.customer_name LIKE ? OR o.customer_phone LIKE ? OR t.table_no LIKE ? OR ('Waiter ' || COALESCE(w.waiter_no, w.id)) LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q); args.add(q); args.add(q); args.add(q); args.add(q);
        }
        sql.append(" ORDER BY o.id DESC LIMIT 500");
        return db.query(sql.toString(), this::mapOrder, args.toArray());
    }

    public List<Order> completeOrders(java.time.LocalDate from, java.time.LocalDate to, String search, int limit) {
        StringBuilder sql = new StringBuilder(ORDER_SELECT).append(" WHERE (o.status = 'COMPLETED' OR o.payment_status IN ('PAID','ON_ACCOUNT')) AND o.status <> 'CANCELLED'");
        List<Object> args = new ArrayList<>();
        if (from != null) { sql.append(" AND date(o.created_at) >= ?"); args.add(from.toString()); }
        if (to != null) { sql.append(" AND date(o.created_at) <= ?"); args.add(to.toString()); }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR o.customer_name LIKE ? OR o.customer_phone LIKE ? OR t.table_no LIKE ? OR ('Waiter ' || COALESCE(w.waiter_no, w.id)) LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q); args.add(q); args.add(q); args.add(q); args.add(q);
        }
        sql.append(" ORDER BY o.id DESC LIMIT ?");
        args.add(limit > 0 ? limit : 500);
        return db.query(sql.toString(), this::mapOrder, args.toArray());
    }

    public List<Order> openOrders() {
        return db.query(ORDER_SELECT + " WHERE o.status NOT IN ('COMPLETED','CANCELLED') ORDER BY o.id DESC", this::mapOrder);
    }

    public Order openOrderForTable(long tableId) {
        return db.queryOne(ORDER_SELECT + " WHERE o.table_id=? AND o.status NOT IN ('COMPLETED','CANCELLED') ORDER BY o.id DESC LIMIT 1",
                this::mapOrder, tableId);
    }

    public List<OrderItem> items(long orderId) {
        return db.query("SELECT * FROM order_items WHERE order_id=? AND status<>'VOID' ORDER BY id",
                rs -> new OrderItem(rs.getLong("id"), rs.getLong("order_id"), Rows.longOrNull(rs, "product_id"),
                        Rows.longOrNull(rs, "variant_id"), rs.getString("name"), rs.getString("sku"),
                        Rows.bd(rs, "unit_price"), Rows.bd(rs, "cost"), rs.getInt("qty"), rs.getString("addons"),
                        rs.getString("instructions"), Rows.bd(rs, "discount"), Rows.bd(rs, "line_total"),
                        rs.getString("status")), orderId);
    }

    public boolean productHasOrders(long productId) {
        long count = db.scalarLong("SELECT COUNT(*) FROM order_items WHERE product_id=? LIMIT 1", productId);
        return count > 0;
    }

    public long addEvent(long orderId, Long userId, String action, String details) {
        return db.insert("INSERT INTO order_events(order_id,user_id,action,details,created_at) VALUES(?,?,?,?,?)",
                orderId, userId, action, details, LocalDateTime.now());
    }

    public List<OrderEvent> events(long orderId) {
        return db.query("SELECT * FROM order_events WHERE order_id=? ORDER BY id", rs -> new OrderEvent(rs.getLong("id"),
                rs.getLong("order_id"), Rows.longOrNull(rs, "user_id"), rs.getString("action"),
                rs.getString("details"), Rows.dt(rs, "created_at")), orderId);
    }

    // ---------- payments ----------
    private Payment mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Payment(rs.getLong("id"), rs.getLong("order_id"), Rows.longOrNull(rs, "shift_id"),
                PaymentMethod.valueOf(rs.getString("method")), Rows.bd(rs, "amount"), Rows.bd(rs, "tendered"),
                Rows.bd(rs, "change_due"), rs.getString("reference"), rs.getString("status"),
                Rows.bool(rs, "confirmed"), rs.getString("cashier_name"), Rows.dt(rs, "created_at"),
                Rows.bd(rs, "refunded_amount"), rs.getString("notes"));
    }

    public long addPayment(long orderId, Long shiftId, PaymentMethod method, BigDecimal amount, BigDecimal tendered,
                           BigDecimal change, String reference, String status, boolean confirmed, long userId, String notes) {
        return db.insert("""
                INSERT INTO payments(order_id,shift_id,method,amount,tendered,change_due,reference,status,confirmed,
                created_by,created_at,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, orderId, shiftId, method.name(), amount, tendered, change, reference, status, confirmed,
                userId, LocalDateTime.now(), notes);
    }

    public List<Payment> payments(long orderId) {
        return db.query("""
                SELECT p.*, u.full_name AS cashier_name FROM payments p LEFT JOIN users u ON u.id=p.created_by
                WHERE p.order_id=? ORDER BY p.id
                """, this::mapPayment, orderId);
    }

    public Payment payment(long id) {
        return db.queryOne("""
                SELECT p.*, u.full_name AS cashier_name FROM payments p LEFT JOIN users u ON u.id=p.created_by WHERE p.id=?
                """, this::mapPayment, id);
    }

    public void addRefundedAmount(long paymentId, BigDecimal amount) {
        db.update("UPDATE payments SET refunded_amount = refunded_amount + ? WHERE id=?", amount, paymentId);
    }

    public long addRefund(long paymentId, long orderId, BigDecimal amount, String reason, String approvedBy, String createdBy) {
        return db.insert("INSERT INTO refunds(payment_id,order_id,amount,reason,approved_by,created_by,created_at) VALUES(?,?,?,?,?,?,?)",
                paymentId, orderId, amount, reason, approvedBy, createdBy, LocalDateTime.now());
    }

    public List<Refund> refunds(long orderId) {
        return db.query("SELECT * FROM refunds WHERE order_id=? ORDER BY id", rs -> new Refund(rs.getLong("id"),
                rs.getLong("payment_id"), rs.getLong("order_id"), Rows.bd(rs, "amount"), rs.getString("reason"),
                rs.getString("approved_by"), rs.getString("created_by"), Rows.dt(rs, "created_at")), orderId);
    }

    public void updatePaymentStatus(long orderId, PaymentStatus status) {
        db.update("UPDATE orders SET payment_status=?, updated_at=? WHERE id=?", status.name(), LocalDateTime.now(), orderId);
    }

    public void setPaidTotals(long orderId, BigDecimal paid, BigDecimal change) {
        db.update("UPDATE orders SET paid_total=?, change_due=?, updated_at=? WHERE id=?", paid, change, LocalDateTime.now(), orderId);
    }

    public void setCreditAmount(long orderId, BigDecimal creditAmount) {
        db.update("UPDATE orders SET credit_amount=?, updated_at=? WHERE id=?", Money.nz(creditAmount), LocalDateTime.now(), orderId);
    }

    public BigDecimal totalPaid(long orderId) {
        return Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id=? AND status='COMPLETED'", orderId));
    }

    public BigDecimal totalRefunded(long orderId) {
        return Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM refunds WHERE order_id=?", orderId));
    }

    // ---------- kitchen ----------
    public long createTicket(long orderId, String ticketNo, String station, String notes, KitchenStatus status) {
        return db.insert("INSERT INTO kitchen_tickets(order_id,ticket_no,station,status,notes,created_at) VALUES(?,?,?,?,?,?)",
                orderId, ticketNo, station, status.name(), notes, LocalDateTime.now());
    }

    public void addTicketItem(long ticketId, long orderItemId, String name, int qty, String instructions) {
        db.insert("INSERT INTO kitchen_ticket_items(ticket_id,order_item_id,name,qty,instructions,status) VALUES(?,?,?,?,?,?)",
                ticketId, orderItemId, name, qty, instructions, KitchenStatus.QUEUED.name());
    }

    public List<KitchenTicket> tickets(boolean includeCompleted) {
        String where = includeCompleted ? "" : " WHERE k.status NOT IN ('COMPLETED')";
        return db.query("""
                SELECT k.*, o.order_no, o.type AS order_type, t.table_no FROM kitchen_tickets k
                JOIN orders o ON o.id=k.order_id LEFT JOIN dining_tables t ON t.id=o.table_id
                """ + where + " ORDER BY k.id DESC LIMIT 200",
                rs -> new KitchenTicket(rs.getLong("id"), rs.getLong("order_id"), rs.getString("order_no"),
                        rs.getString("ticket_no"), rs.getString("station"), KitchenStatus.valueOf(rs.getString("status")),
                        Rows.dt(rs, "created_at"), Rows.dt(rs, "ready_at"), Rows.dt(rs, "completed_at"),
                        rs.getString("notes"), OrderType.valueOf(rs.getString("order_type")), rs.getString("table_no")));
    }

    public List<KitchenTicketItem> ticketItems(long ticketId) {
        return db.query("SELECT * FROM kitchen_ticket_items WHERE ticket_id=? ORDER BY id",
                rs -> new KitchenTicketItem(rs.getLong("id"), rs.getLong("ticket_id"), rs.getLong("order_item_id"),
                        rs.getString("name"), rs.getInt("qty"), rs.getString("instructions"), rs.getString("status")), ticketId);
    }

    public void updateTicketStatus(long id, KitchenStatus status) {
        db.update("UPDATE kitchen_tickets SET status=?, ready_at=?, completed_at=? WHERE id=?",
                status.name(), status == KitchenStatus.READY ? LocalDateTime.now() : null,
                status == KitchenStatus.COMPLETED ? LocalDateTime.now() : null, id);
    }

    public void updateTicketItemStatus(long ticketId, KitchenStatus status) {
        db.update("UPDATE kitchen_ticket_items SET status=? WHERE ticket_id=?", status.name(), ticketId);
    }

    public long ordersTodayCount() {
        return db.scalarLong("SELECT COUNT(*) FROM orders WHERE date(created_at)=date('now','localtime') AND status<>'CANCELLED'");
    }

    public long nextSequence() {
        return db.scalarLong("SELECT COALESCE(MAX(id),0)+1 FROM orders");
    }
}
