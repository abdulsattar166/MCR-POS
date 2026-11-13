package com.restaurantpos.dao;

import com.restaurantpos.enums.CashMoveType;
import com.restaurantpos.enums.ShiftStatus;
import com.restaurantpos.model.Inventory.*;
import com.restaurantpos.model.Sales.*;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class FinanceRepository {
    private final Database db = Database.get();

    private Shift mapShift(java.sql.ResultSet rs) throws java.sql.SQLException {
        String sName = null;
        String sStart = null;
        String sEnd = null;
        try { sName = rs.getString("shift_name"); } catch (Exception ignored) {}
        try { sStart = rs.getString("scheduled_start"); } catch (Exception ignored) {}
        try { sEnd = rs.getString("scheduled_end"); } catch (Exception ignored) {}
        return new Shift(rs.getLong("id"), rs.getLong("user_id"), rs.getString("user_name"),
                Rows.dt(rs, "opened_at"), Rows.dt(rs, "closed_at"), Rows.bd(rs, "opening_cash"),
                Rows.bdNullable(rs, "expected_cash"), Rows.bdNullable(rs, "counted_cash"),
                Rows.bdNullable(rs, "difference"), ShiftStatus.valueOf(rs.getString("status")), rs.getString("notes"),
                rs.getString("approved_by"), sName == null ? "General Shift" : sName, sStart, sEnd);
    }

    private static final String SHIFT_SELECT = """
            SELECT s.*, COALESCE(u.full_name, 'Staff #' || s.user_id) AS user_name FROM shifts s LEFT JOIN users u ON u.id=s.user_id
            """;

    public Shift openShift(long userId, BigDecimal openingCash) {
        return openShift(userId, openingCash, "General Shift", null, null);
    }

    public Shift openShift(long userId, BigDecimal openingCash, String shiftName, String scheduledStart, String scheduledEnd) {
        Shift existing = findOpenByUser(userId);
        if (existing != null) return existing;
        long id = db.insert("""
                INSERT INTO shifts(user_id,opened_at,opening_cash,status,shift_name,scheduled_start,scheduled_end)
                VALUES(?,?,?,'OPEN',?,?,?)
                """, userId, LocalDateTime.now(), openingCash,
                shiftName == null ? "General Shift" : shiftName, scheduledStart, scheduledEnd);
        return find(id);
    }

    public List<ShiftSchedule> shiftSchedules(boolean activeOnly) {
        String sql = "SELECT * FROM shift_schedules" + (activeOnly ? " WHERE active=1" : "") + " ORDER BY id";
        return db.query(sql, rs -> new ShiftSchedule(rs.getLong("id"), rs.getString("name"),
                rs.getString("start_time"), rs.getString("end_time"), rs.getString("working_days"),
                rs.getInt("grace_minutes"), Rows.bool(rs, "active")));
    }

    public long saveShiftSchedule(ShiftSchedule s) {
        if (s.id() == 0) {
            return db.insert("""
                    INSERT INTO shift_schedules(name, start_time, end_time, working_days, grace_minutes, active)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """, s.name(), s.startTime(), s.endTime(), s.workingDays(), s.graceMinutes(), s.active());
        }
        db.update("""
                UPDATE shift_schedules SET name=?, start_time=?, end_time=?, working_days=?, grace_minutes=?, active=?
                WHERE id=?
                """, s.name(), s.startTime(), s.endTime(), s.workingDays(), s.graceMinutes(), s.active(), s.id());
        return s.id();
    }

    public void deleteShiftSchedule(long id) {
        db.update("DELETE FROM shift_schedules WHERE id=?", id);
    }

    public Shift findOpenByUser(long userId) {
        return db.queryOne(SHIFT_SELECT + " WHERE s.user_id=? AND s.status='OPEN' ORDER BY s.id DESC LIMIT 1",
                this::mapShift, userId);
    }

    public Shift find(long id) {
        return db.queryOne(SHIFT_SELECT + " WHERE s.id=?", this::mapShift, id);
    }

    public List<Shift> shifts(LocalDate from, LocalDate to, Long userId) {
        StringBuilder sql = new StringBuilder(SHIFT_SELECT).append(" WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (from != null) { sql.append(" AND date(s.opened_at)>=?"); args.add(from.toString()); }
        if (to != null) { sql.append(" AND date(s.opened_at)<=?"); args.add(to.toString()); }
        if (userId != null) { sql.append(" AND s.user_id=?"); args.add(userId); }
        sql.append(" ORDER BY s.id DESC");
        return db.query(sql.toString(), this::mapShift, args.toArray());
    }

    public BigDecimal cashSales(long shiftId) {
        BigDecimal orderCash = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND method='CASH' AND status='COMPLETED'", shiftId));
        BigDecimal ledgerCash = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM customer_ledger WHERE shift_id=? AND entry_type='PAYMENT' AND payment_method='CASH'", shiftId));
        return Money.add(orderCash, ledgerCash);
    }

    public BigDecimal cashRefunds(long shiftId) {
        return Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(r.amount),0) FROM refunds r JOIN payments p ON p.id=r.payment_id
                WHERE p.shift_id=? AND p.method='CASH'
                """, shiftId));
    }

    public BigDecimal cashIn(long shiftId) {
        return Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM cash_movements WHERE shift_id=? AND type='CASH_IN'", shiftId));
    }

    public BigDecimal cashOut(long shiftId) {
        return Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM cash_movements WHERE shift_id=? AND type='CASH_OUT'", shiftId));
    }

    public BigDecimal expectedCash(Shift shift) {
        if (shift == null) return Money.ZERO;
        return Money.add(Money.add(shift.openingCash(), cashSales(shift.id())),
                Money.sub(cashIn(shift.id()), Money.add(cashRefunds(shift.id()), cashOut(shift.id()))));
    }

    public void closeShift(long shiftId, BigDecimal countedCash, String notes, String approvedBy) {
        Shift shift = find(shiftId);
        if (shift == null || !shift.isOpen()) throw new com.restaurantpos.exception.AppException("Shift is not open.");
        BigDecimal expected = expectedCash(shift);
        BigDecimal diff = Money.sub(countedCash, expected);
        db.update("""
                UPDATE shifts SET closed_at=?, expected_cash=?, counted_cash=?, difference=?, status='CLOSED', notes=?, approved_by=?
                WHERE id=? AND status='OPEN'
                """, LocalDateTime.now(), expected, countedCash, diff, notes, approvedBy, shiftId);
    }

    public long addCashMovement(long shiftId, CashMoveType type, BigDecimal amount, String reason, long userId) {
        return db.insert("INSERT INTO cash_movements(shift_id,type,amount,reason,user_id,created_at) VALUES(?,?,?,?,?,?)",
                shiftId, type.name(), amount, reason, userId, LocalDateTime.now());
    }

    public List<CashMovement> cashMovements(long shiftId) {
        return db.query("""
                SELECT c.*, u.full_name AS user_name FROM cash_movements c JOIN users u ON u.id=c.user_id
                WHERE c.shift_id=? ORDER BY c.id
                """, rs -> new CashMovement(rs.getLong("id"), rs.getLong("shift_id"),
                CashMoveType.valueOf(rs.getString("type")), Rows.bd(rs, "amount"), rs.getString("reason"),
                rs.getString("user_name"), Rows.dt(rs, "created_at")), shiftId);
    }

    public BigDecimal shiftSales(long shiftId) {
        BigDecimal orderSales = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM payments WHERE shift_id=? AND method<>'CREDIT' AND status='COMPLETED'", shiftId));
        BigDecimal ledgerCollected = Money.nz(db.scalarDecimal(
                "SELECT COALESCE(SUM(amount),0) FROM customer_ledger WHERE shift_id=? AND entry_type='PAYMENT'", shiftId));
        return Money.add(orderSales, ledgerCollected);
    }

    public int shiftOrderCount(long shiftId) {
        return (int) db.scalarLong("SELECT COUNT(DISTINCT order_id) FROM payments WHERE shift_id=?", shiftId);
    }

    // ---------- expenses ----------
    public List<ExpenseCategory> expenseCategories() {
        return db.query("SELECT * FROM expense_categories ORDER BY name",
                rs -> new ExpenseCategory(rs.getLong("id"), rs.getString("name")));
    }

    public long saveExpenseCategory(String name) {
        return db.insert("INSERT OR IGNORE INTO expense_categories(name) VALUES(?)", name);
    }

    public List<Expense> expenses(LocalDate from, LocalDate to, Long categoryId) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.*, c.name AS category_name FROM expenses e JOIN expense_categories c ON c.id=e.category_id WHERE 1=1
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (from != null) { sql.append(" AND e.expense_date>=?"); args.add(from.toString()); }
        if (to != null) { sql.append(" AND e.expense_date<=?"); args.add(to.toString()); }
        if (categoryId != null) { sql.append(" AND e.category_id=?"); args.add(categoryId); }
        sql.append(" ORDER BY e.expense_date DESC, e.id DESC");
        return db.query(sql.toString(), rs -> new Expense(rs.getLong("id"), rs.getLong("category_id"),
                rs.getString("category_name"), Rows.bd(rs, "amount"), Rows.date(rs, "expense_date"),
                rs.getString("payment_method"), rs.getString("description"), rs.getString("responsible"),
                rs.getString("invoice_path"), rs.getString("status"), rs.getString("approved_by"),
                Rows.dt(rs, "created_at")), args.toArray());
    }

    public long addExpense(long categoryId, BigDecimal amount, LocalDate date, String method, String description,
                           String responsible, String invoicePath, String status, String approvedBy) {
        return db.insert("""
                INSERT INTO expenses(category_id,amount,expense_date,payment_method,description,responsible,invoice_path,
                status,approved_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, categoryId, amount, date, method, description, responsible, invoicePath, status, approvedBy, LocalDateTime.now());
    }

    public void updateExpense(long id, long categoryId, BigDecimal amount, LocalDate date, String method,
                              String description, String responsible, String status, String approvedBy) {
        db.update("""
                UPDATE expenses SET category_id=?, amount=?, expense_date=?, payment_method=?, description=?, responsible=?,
                status=?, approved_by=? WHERE id=?
                """, categoryId, amount, date, method, description, responsible, status, approvedBy, id);
    }

    public void deleteExpense(long id) { db.update("DELETE FROM expenses WHERE id=?", id); }

    public BigDecimal totalExpenses(LocalDate from, LocalDate to) {
        return Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date BETWEEN ? AND ? AND status='APPROVED'",
                from.toString(), to.toString()));
    }
}
