package com.restaurantpos.dao;

import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Inventory.CustomerLedgerSummary;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class CustomerLedgerRepository {
    private final Database db = Database.get();

    private Customer mapCustomer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Customer(rs.getLong("id"), rs.getString("name"), rs.getString("phone"),
                rs.getString("address"), rs.getString("email"), rs.getString("notes"),
                Rows.dt(rs, "created_at"));
    }

    private CustomerLedgerEntry mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerLedgerEntry(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                Rows.longOrNull(rs, "order_id"),
                rs.getString("order_no"),
                rs.getString("entry_type"),
                Rows.bd(rs, "amount"),
                Rows.bd(rs, "signed_delta"),
                Rows.bd(rs, "balance_after"),
                rs.getString("payment_method"),
                rs.getString("reference"),
                rs.getString("description"),
                rs.getLong("staff_id"),
                rs.getString("staff_name"),
                Rows.longOrNull(rs, "shift_id"),
                Rows.dt(rs, "created_at")
        );
    }

    public Customer findCustomer(long id) {
        return db.queryOne("SELECT * FROM customers WHERE id=?", this::mapCustomer, id);
    }

    public Customer findCustomerByPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return db.queryOne("SELECT * FROM customers WHERE TRIM(phone)=? COLLATE NOCASE LIMIT 1",
                this::mapCustomer, phone.trim());
    }

    public Customer findCustomerByName(String name) {
        if (name == null || name.isBlank()) return null;
        return db.queryOne("SELECT * FROM customers WHERE TRIM(name)=? COLLATE NOCASE LIMIT 1",
                this::mapCustomer, name.trim());
    }

    public List<Customer> searchCustomers(String query) {
        if (query == null || query.isBlank()) {
            return db.query("SELECT * FROM customers ORDER BY name", this::mapCustomer);
        }
        String q = "%" + query.trim() + "%";
        return db.query("SELECT * FROM customers WHERE name LIKE ? OR phone LIKE ? ORDER BY name",
                this::mapCustomer, q, q);
    }

    public List<CustomerLedgerSummary> listSummaries(String search) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.*,
                       COALESCE(SUM(CASE WHEN l.entry_type='CREDIT_SALE' THEN l.amount ELSE 0 END), 0) AS total_credit,
                       COALESCE(SUM(CASE WHEN l.entry_type='PAYMENT' THEN l.amount ELSE 0 END), 0) AS total_received,
                       COALESCE(SUM(l.signed_delta), 0) AS current_balance
                FROM customers c
                LEFT JOIN customer_ledger l ON l.customer_id = c.id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            sql.append(" AND (c.name LIKE ? OR c.phone LIKE ?)");
            String q = "%" + search.trim() + "%";
            args.add(q);
            args.add(q);
        }
        sql.append(" GROUP BY c.id ORDER BY current_balance DESC, c.name ASC");
        return db.query(sql.toString(), rs -> new CustomerLedgerSummary(
                mapCustomer(rs),
                Money.nz(Rows.bd(rs, "total_credit")),
                Money.nz(Rows.bd(rs, "total_received")),
                Money.nz(Rows.bd(rs, "current_balance"))
        ), args.toArray());
    }

    public CustomerLedgerSummary summary(long customerId) {
        Customer c = findCustomer(customerId);
        if (c == null) return null;
        BigDecimal totalCredit = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM customer_ledger WHERE customer_id=? AND entry_type='CREDIT_SALE'", customerId));
        BigDecimal totalReceived = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM customer_ledger WHERE customer_id=? AND entry_type='PAYMENT'", customerId));
        BigDecimal currentBalance = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(signed_delta),0) FROM customer_ledger WHERE customer_id=?", customerId));
        return new CustomerLedgerSummary(c, totalCredit, totalReceived, currentBalance);
    }

    public BigDecimal balance(long customerId) {
        return Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(signed_delta),0) FROM customer_ledger WHERE customer_id=?", customerId));
    }

    public BigDecimal creditedForOrder(long orderId) {
        return Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM customer_ledger WHERE order_id=? AND entry_type='CREDIT_SALE'", orderId));
    }

    public List<CustomerLedgerEntry> entries(long customerId) {
        return db.query("""
                SELECT l.*, o.order_no, COALESCE(u.full_name, 'Staff #' || l.staff_id) AS staff_name
                FROM customer_ledger l
                LEFT JOIN orders o ON o.id = l.order_id
                LEFT JOIN users u ON u.id = l.staff_id
                WHERE l.customer_id = ?
                ORDER BY l.id DESC
                """, this::mapEntry, customerId);
    }

    public long insertEntry(long customerId, Long orderId, String entryType, BigDecimal amount, BigDecimal signedDelta,
                            BigDecimal balanceAfter, String paymentMethod, String reference, String description,
                            long staffId, Long shiftId) {
        return db.insert("""
                INSERT INTO customer_ledger(customer_id, order_id, entry_type, amount, signed_delta, balance_after,
                                            payment_method, reference, description, staff_id, shift_id, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, customerId, orderId, entryType, amount, signedDelta, balanceAfter,
                paymentMethod, reference, description, staffId, shiftId, LocalDateTime.now());
    }
}
