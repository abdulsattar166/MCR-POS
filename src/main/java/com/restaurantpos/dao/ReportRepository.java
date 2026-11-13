package com.restaurantpos.dao;

import com.restaurantpos.model.SystemModels.DashboardStats;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReportRepository {
    private final Database db = Database.get();

    public DashboardStats dashboard(LocalDate day) {
        String d = day.toString();
        BigDecimal gross = Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(total),0) FROM orders WHERE date(created_at)=? AND status NOT IN ('CANCELLED','DRAFT')
                """, d));
        BigDecimal discounts = Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(discount_total),0) FROM orders WHERE date(created_at)=? AND status NOT IN ('CANCELLED','DRAFT')
                """, d));
        BigDecimal refunds = Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM refunds WHERE date(created_at)=?", d));
        BigDecimal net = Money.sub(Money.sub(gross, discounts), refunds);
        int orders = (int) db.scalarLong("""
                SELECT COUNT(*) FROM orders WHERE date(created_at)=? AND status NOT IN ('CANCELLED','DRAFT')
                """, d);
        BigDecimal avg = orders == 0 ? Money.ZERO : Money.div(gross, BigDecimal.valueOf(orders));
        BigDecimal cogs = Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(oi.cost*oi.qty),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE date(o.created_at)=? AND o.status NOT IN ('CANCELLED','DRAFT') AND oi.status='ACTIVE'
                """, d));
        BigDecimal expenses = Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date=? AND status='APPROVED'
                """, d));
        BigDecimal grossProfit = Money.sub(net, cogs);
        BigDecimal operatingProfit = Money.sub(grossProfit, expenses);
        int lowStock = (int) db.scalarLong("""
                SELECT COUNT(*) FROM ingredients WHERE active=1 AND stock_qty>0 AND stock_qty<=min_qty
                """);
        int outOfStock = (int) db.scalarLong("""
                SELECT COUNT(*) FROM ingredients WHERE active=1 AND stock_qty<=0
                """);
        int activeKitchen = (int) db.scalarLong("""
                SELECT COUNT(*) FROM orders WHERE status IN ('CONFIRMED','PREPARING','READY') AND kitchen_status<>'COMPLETED'
                """);
        int delayedKitchen = (int) db.scalarLong("""
                SELECT COUNT(*) FROM orders WHERE status IN ('CONFIRMED','PREPARING')
                AND datetime(created_at) < datetime('now','localtime','-20 minutes')
                """);
        int activeShifts = (int) db.scalarLong("SELECT COUNT(*) FROM shifts WHERE status='OPEN'");
        int pendingRefunds = 0;
        BigDecimal cashDiscrepancy = Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(ABS(difference)),0) FROM shifts
                WHERE status='CLOSED' AND date(closed_at)=? AND difference<>0
                """, d));
        return new DashboardStats(gross, net, orders, avg, grossProfit, operatingProfit, lowStock, outOfStock,
                activeKitchen, delayedKitchen, activeShifts, pendingRefunds, cashDiscrepancy);
    }

    public List<Object[]> salesByDay(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT date(o.created_at) d, COUNT(*) orders,
                       COALESCE(SUM(o.total),0) gross,
                       COALESCE(SUM(o.discount_total),0) discounts,
                       COALESCE((SELECT SUM(r.amount) FROM refunds r WHERE date(r.created_at)=date(o.created_at)),0) refunds
                FROM orders o WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT')
                GROUP BY date(o.created_at) ORDER BY d
                """, rs -> new Object[]{rs.getString("d"), rs.getLong("orders"), Rows.bd(rs, "gross"),
                Rows.bd(rs, "discounts"), Rows.bd(rs, "refunds")}, from.toString(), to.toString());
    }

    public List<Object[]> salesByCashier(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT u.full_name, COUNT(DISTINCT o.id) orders, COALESCE(SUM(o.total),0) gross,
                       COALESCE(SUM(o.discount_total),0) discounts, COALESCE(SUM(p.paid),0) collected
                FROM orders o JOIN users u ON u.id=o.cashier_id
                LEFT JOIN (SELECT order_id, SUM(amount) paid FROM payments WHERE status='COMPLETED' AND method<>'CREDIT' GROUP BY order_id) p
                  ON p.order_id=o.id
                WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT')
                GROUP BY u.id ORDER BY gross DESC
                """, rs -> new Object[]{rs.getString(1), rs.getLong(2), Rows.bd(rs, "gross"),
                Rows.bd(rs, "discounts"), Money.nz(Rows.bd(rs, "collected"))}, from.toString(), to.toString());
    }

    public List<Object[]> salesByShift(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT s.id, COALESCE(u.full_name, 'Staff #' || s.user_id), s.opened_at, s.closed_at, s.opening_cash,
                       (COALESCE((SELECT SUM(amount) FROM payments WHERE shift_id=s.id AND status='COMPLETED' AND method<>'CREDIT'),0)
                        + COALESCE((SELECT SUM(amount) FROM customer_ledger WHERE shift_id=s.id AND entry_type='PAYMENT'),0)) sales,
                       s.counted_cash, s.difference, s.status
                FROM shifts s LEFT JOIN users u ON u.id=s.user_id
                WHERE date(s.opened_at) BETWEEN ? AND ? ORDER BY s.id DESC
                """, rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                Rows.bd(rs, "opening_cash"), Rows.bd(rs, "sales"), Rows.bd(rs, "counted_cash"),
                Rows.bd(rs, "difference"), rs.getString(9)}, from.toString(), to.toString());
    }

    public List<Object[]> salesByType(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT o.type, COUNT(*) orders, COALESCE(SUM(o.total),0) gross
                FROM orders o WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT')
                GROUP BY o.type ORDER BY gross DESC
                """, rs -> new Object[]{rs.getString(1), rs.getLong(2), Rows.bd(rs, "gross")}, from.toString(), to.toString());
    }

    public List<Object[]> salesByPaymentMethod(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT p.method, COUNT(*) transactions, COALESCE(SUM(p.amount),0) amount,
                       COALESCE(SUM(p.refunded_amount),0) refunded
                FROM payments p WHERE date(p.created_at) BETWEEN ? AND ? AND p.status='COMPLETED'
                GROUP BY p.method ORDER BY amount DESC
                """, rs -> new Object[]{rs.getString(1), rs.getLong(2), Rows.bd(rs, "amount"), Rows.bd(rs, "refunded")},
                from.toString(), to.toString());
    }

    public List<Object[]> productPerformance(LocalDate from, LocalDate to, int limit) {
        return db.query("""
                SELECT oi.name, oi.sku, SUM(oi.qty) qty, COALESCE(SUM(oi.line_total),0) revenue,
                       COALESCE(SUM(oi.cost*oi.qty),0) cost
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT') AND oi.status='ACTIVE'
                GROUP BY oi.name, oi.sku ORDER BY revenue DESC LIMIT ?
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), Rows.bd(rs, "revenue"),
                Rows.bd(rs, "cost")}, from.toString(), to.toString(), limit);
    }

    public List<Object[]> categoryPerformance(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT c.name, SUM(oi.qty) qty, COALESCE(SUM(oi.line_total),0) revenue
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                JOIN products p ON p.id=oi.product_id JOIN categories c ON c.id=p.category_id
                WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT') AND oi.status='ACTIVE'
                GROUP BY c.id ORDER BY revenue DESC
                """, rs -> new Object[]{rs.getString(1), rs.getLong(2), Rows.bd(rs, "revenue")},
                from.toString(), to.toString());
    }

    public List<Object[]> taxSummary(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT date(o.created_at) d, COALESCE(SUM(o.tax_total),0) tax, COALESCE(SUM(o.total),0) gross
                FROM orders o WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT')
                GROUP BY date(o.created_at) ORDER BY d
                """, rs -> new Object[]{rs.getString(1), Rows.bd(rs, "tax"), Rows.bd(rs, "gross")},
                from.toString(), to.toString());
    }

    public java.util.List<Object[]> paymentTransactions(LocalDate from, LocalDate to, String method, Long cashierId, String search) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.created_at, p.order_id, o.order_no, u.full_name cashier, p.method, p.amount,
                       p.refunded_amount, p.reference, p.status, p.shift_id
                FROM payments p JOIN orders o ON o.id=p.order_id JOIN users u ON u.id=p.created_by
                WHERE date(p.created_at) BETWEEN ? AND ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(from.toString()); args.add(to.toString());
        if (method != null && !method.isBlank()) { sql.append(" AND p.method=?"); args.add(method); }
        if (cashierId != null) { sql.append(" AND p.created_by=?"); args.add(cashierId); }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (o.order_no LIKE ? OR p.reference LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q); args.add(q);
        }
        sql.append(" ORDER BY p.id DESC LIMIT 1000");
        return db.query(sql.toString(), rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getString(6), Rows.bd(rs, "amount"), Rows.bd(rs, "refunded_amount"),
                rs.getString(9), rs.getString(10), rs.getObject(11)}, args.toArray());
    }

    public List<Object[]> refundTransactions(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT r.id, r.created_at, o.order_no, p.method, r.amount, r.reason, r.approved_by, r.created_by
                FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=r.order_id
                WHERE date(r.created_at) BETWEEN ? AND ? ORDER BY r.id DESC
                """, rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                Rows.bd(rs, "amount"), rs.getString(6), rs.getString(7), rs.getString(8)},
                from.toString(), to.toString());
    }
    public List<Object[]> refundsReport(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT r.id, o.order_no, p.method, r.amount, r.reason, r.approved_by, r.created_by, r.created_at
                FROM refunds r JOIN payments p ON p.id=r.payment_id JOIN orders o ON o.id=r.order_id
                WHERE date(r.created_at) BETWEEN ? AND ? ORDER BY r.id DESC
                """, rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), Rows.bd(rs, "amount"),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)}, from.toString(), to.toString());
    }

    public List<Object[]> cancellations(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT o.order_no, o.type, o.cancel_reason, u.full_name, o.created_at, o.total
                FROM orders o JOIN users u ON u.id=o.cashier_id
                WHERE o.status='CANCELLED' AND date(o.created_at) BETWEEN ? AND ? ORDER BY o.id DESC
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), Rows.bd(rs, "total")}, from.toString(), to.toString());
    }

    public List<Object[]> purchasesReport(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT p.po_no, s.name supplier, p.status, p.order_date, p.total,
                       COALESCE((SELECT SUM(amount) FROM supplier_payments sp WHERE sp.po_id=p.id),0) paid
                FROM purchase_orders p JOIN suppliers s ON s.id=p.supplier_id
                WHERE p.order_date BETWEEN ? AND ? ORDER BY p.id DESC
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                Rows.bd(rs, "total"), Rows.bd(rs, "paid")}, from.toString(), to.toString());
    }

    public List<Object[]> inventoryValuation() {
        return db.query("""
                SELECT i.name, i.unit, i.stock_qty, i.avg_cost, (i.stock_qty*i.avg_cost) value, i.min_qty
                FROM ingredients i WHERE i.active=1 ORDER BY value DESC
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), Rows.bd(rs, "stock_qty"),
                Rows.bd(rs, "avg_cost"), Rows.bd(rs, "value"), Rows.bd(rs, "min_qty")});
    }

    public List<Object[]> stockMovements(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT m.created_at, i.name, m.type, m.qty, m.unit_cost, m.ref_type, m.ref_id, m.created_by
                FROM stock_movements m JOIN ingredients i ON i.id=m.ingredient_id
                WHERE date(m.created_at) BETWEEN ? AND ? ORDER BY m.id DESC
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), Rows.bd(rs, "qty"),
                Rows.bd(rs, "unit_cost"), rs.getString(6), rs.getObject(7), rs.getString(8)}, from.toString(), to.toString());
    }

    public List<Object[]> expensesReport(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT e.expense_date, c.name, e.amount, e.payment_method, e.description, e.status
                FROM expenses e JOIN expense_categories c ON c.id=e.category_id
                WHERE e.expense_date BETWEEN ? AND ? ORDER BY e.expense_date DESC, e.id DESC
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), Rows.bd(rs, "amount"),
                rs.getString(4), rs.getString(5), rs.getString(6)}, from.toString(), to.toString());
    }

    public List<Object[]> shiftDiscrepancies(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT s.id, COALESCE(u.full_name, 'Staff #' || s.user_id), s.opened_at, s.closed_at, s.expected_cash, s.counted_cash, s.difference
                FROM shifts s LEFT JOIN users u ON u.id=s.user_id
                WHERE s.status='CLOSED' AND date(s.closed_at) BETWEEN ? AND ? AND s.difference<>0
                ORDER BY ABS(s.difference) DESC
                """, rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                Rows.bd(rs, "expected_cash"), Rows.bd(rs, "counted_cash"), Rows.bd(rs, "difference")},
                from.toString(), to.toString());
    }

    public List<Object[]> cashierPerformance(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT u.full_name,
                       COUNT(DISTINCT o.id) orders,
                       COALESCE(SUM(o.total),0) sales,
                       COALESCE(SUM(o.discount_total),0) discounts,
                       (SELECT COUNT(*) FROM orders x WHERE x.cashier_id=u.id AND x.status='CANCELLED' AND date(x.created_at) BETWEEN ? AND ?) cancels,
                       (SELECT COALESCE(SUM(r.amount),0) FROM refunds r JOIN payments p ON p.id=r.payment_id WHERE p.created_by=u.id AND date(r.created_at) BETWEEN ? AND ?) refunds
                FROM orders o JOIN users u ON u.id=o.cashier_id
                WHERE date(o.created_at) BETWEEN ? AND ? AND o.status NOT IN ('CANCELLED','DRAFT')
                GROUP BY u.id ORDER BY sales DESC
                """, rs -> new Object[]{rs.getString(1), rs.getLong(2), Rows.bd(rs, "sales"),
                Rows.bd(rs, "discounts"), rs.getLong(5), Rows.bd(rs, "refunds")},
                from.toString(), to.toString(), from.toString(), to.toString(), from.toString(), to.toString());
    }

    public Map<String, BigDecimal> dailySalesMap(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        db.query("""
                SELECT date(created_at) d, COALESCE(SUM(total),0) total FROM orders
                WHERE date(created_at) BETWEEN ? AND ? AND status NOT IN ('CANCELLED','DRAFT')
                GROUP BY date(created_at) ORDER BY d
                """, rs -> map.put(rs.getString(1), Rows.bd(rs, "total")), from.toString(), to.toString());
        return map;
    }

    public Map<String, BigDecimal> paymentsMap(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        db.query("""
                SELECT method, COALESCE(SUM(amount),0) total FROM payments
                WHERE date(created_at) BETWEEN ? AND ? AND status='COMPLETED' GROUP BY method
                """, rs -> map.put(rs.getString(1), Rows.bd(rs, "total")), from.toString(), to.toString());
        return map;
    }

    public List<Object[]> recentActivity(int limit) {
        return db.query("""
                SELECT username, action, entity, details, created_at FROM audit_log ORDER BY id DESC LIMIT ?
                """, rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)}, limit);
    }
}
