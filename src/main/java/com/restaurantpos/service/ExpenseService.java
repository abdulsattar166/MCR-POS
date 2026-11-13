package com.restaurantpos.service;

import com.restaurantpos.dao.FinanceRepository;
import com.restaurantpos.enums.Role;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Inventory.Expense;
import com.restaurantpos.model.Inventory.ExpenseCategory;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ExpenseService {
    private final FinanceRepository finance;
    private final AuditService audit;

    public ExpenseService(FinanceRepository finance, AuditService audit) {
        this.finance = finance;
        this.audit = audit;
    }

    public List<ExpenseCategory> categories() { return finance.expenseCategories(); }
    public List<Expense> list(LocalDate from, LocalDate to, Long categoryId) { return finance.expenses(from, to, categoryId); }
    public BigDecimal total(LocalDate from, LocalDate to) { return finance.totalExpenses(from, to); }

    public long saveCategory(String name, Session session) {
        Access.requireManager(session);
        Validators.required(name, "Expense category");
        long id = finance.saveExpenseCategory(name.trim());
        audit.log("EXPENSE_CATEGORY_CREATED", "expense_categories", id, name);
        return id;
    }

    public long add(long categoryId, BigDecimal amount, LocalDate date, String method, String description,
                    String responsible, String invoicePath, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.INVENTORY, Role.CASHIER);
        if (categoryId <= 0) throw new AppException("Select an expense category.");
        if (amount == null || amount.signum() <= 0) throw new AppException("Expense amount must be greater than zero.");
        if (date == null) throw new AppException("Select an expense date.");
        String cleanMethod = (method == null || method.isBlank()) ? "Cash" : method.trim();
        String cleanDesc = description == null ? "" : description.trim();
        String cleanResp = (responsible == null || responsible.isBlank())
                ? (session.user() != null ? session.user().fullName() : session.username())
                : responsible.trim();
        long id = finance.addExpense(categoryId, amount, date, cleanMethod, cleanDesc, cleanResp, invoicePath,
                "APPROVED", session.username());
        audit.log("EXPENSE_CREATED", "expenses", id,
                "[" + session.role().name() + ":" + session.username() + "] " + Money.plain(amount) + " — " + cleanDesc);
        return id;
    }

    public void update(long id, long categoryId, BigDecimal amount, LocalDate date, String method, String description,
                       String responsible, String status, Session session) {
        Access.requireManager(session);
        finance.updateExpense(id, categoryId, amount, date, method, description, responsible, status, session.username());
        audit.log("EXPENSE_UPDATED", "expenses", id, Money.plain(amount) + " — " + description);
    }

    public void delete(long id, Session session) {
        Access.require(session, Role.OWNER);
        finance.deleteExpense(id);
        audit.log("EXPENSE_DELETED", "expenses", id, "");
    }
}
