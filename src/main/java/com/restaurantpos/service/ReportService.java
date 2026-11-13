package com.restaurantpos.service;

import com.restaurantpos.dao.ReportRepository;
import com.restaurantpos.model.SystemModels.DashboardStats;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportData.Column;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReportService {
    private final ReportRepository repo;

    public ReportService(ReportRepository repo) { this.repo = repo; }

    public record ReportDefinition(String key, String label, boolean range) {}

    public static final List<ReportDefinition> DEFINITIONS = List.of(
            new ReportDefinition("SALES_DAILY", "Daily Sales Summary", true),
            new ReportDefinition("SALES_BY_CASHIER", "Sales by Cashier", true),
            new ReportDefinition("SALES_BY_SHIFT", "Sales by Shift", true),
            new ReportDefinition("SALES_BY_TYPE", "Sales by Order Type", true),
            new ReportDefinition("SALES_BY_PAYMENT", "Sales by Payment Method", true),
            new ReportDefinition("PRODUCT_PERFORMANCE", "Product Performance", true),
            new ReportDefinition("CATEGORY_PERFORMANCE", "Category Performance", true),
            new ReportDefinition("TAX", "Tax Summary", true),
            new ReportDefinition("DISCOUNTS", "Discounts & Promotions", true),
            new ReportDefinition("REFUNDS", "Refund Register", true),
            new ReportDefinition("CANCELLATIONS", "Cancellations", true),
            new ReportDefinition("CASHIER_PERFORMANCE", "Cashier Performance", true),
            new ReportDefinition("SHIFT_DISCREPANCY", "Cash Discrepancies", true),
            new ReportDefinition("PURCHASES", "Purchase Register", true),
            new ReportDefinition("EXPENSES", "Expense Register", true),
            new ReportDefinition("INVENTORY_VALUATION", "Inventory Valuation", false),
            new ReportDefinition("STOCK_MOVEMENTS", "Stock Movements", true)
    );

    public DashboardStats dashboard() { return repo.dashboard(LocalDate.now()); }

    public List<Object[]> dailySales(LocalDate from, LocalDate to) { return repo.salesByDay(from, to); }
    public Map<String, BigDecimal> dailySalesMap(LocalDate from, LocalDate to) { return repo.dailySalesMap(from, to); }
    public Map<String, BigDecimal> paymentDistribution(LocalDate from, LocalDate to) { return repo.paymentsMap(from, to); }
    public List<Object[]> productPerformance(LocalDate from, LocalDate to, int limit) { return repo.productPerformance(from, to, limit); }
    public List<Object[]> categoryPerformance(LocalDate from, LocalDate to) { return repo.categoryPerformance(from, to); }
    public List<Object[]> recentActivity(int limit) { return repo.recentActivity(limit); }

    public ReportData build(String key, LocalDate from, LocalDate to) {
        String period = from == null ? "All records" : from + " to " + to;
        return switch (key) {
            case "SALES_DAILY" -> {
                List<Object[]> rows = new ArrayList<>();
                for (Object[] r : repo.salesByDay(from, to)) {
                    BigDecimal net = Money.sub(Money.sub((BigDecimal) r[2], (BigDecimal) r[3]), (BigDecimal) r[4]);
                    rows.add(new Object[]{r[0], r[1], r[2], r[3], r[4], net});
                }
                yield new ReportData(key, "Daily Sales Summary", period, List.of(
                        Column.date("Date", 12), Column.integer("Orders", 8), Column.money("Gross Sales", 14),
                        Column.money("Discounts", 13), Column.money("Refunds", 12), Column.money("Net Sales", 14)), rows);
            }
            case "SALES_BY_CASHIER" -> new ReportData(key, "Sales by Cashier", period, List.of(
                    Column.text("Cashier", 22), Column.integer("Orders", 8), Column.money("Gross Sales", 14),
                    Column.money("Discounts", 13), Column.money("Collected", 14)), repo.salesByCashier(from, to));
            case "SALES_BY_SHIFT" -> new ReportData(key, "Sales by Shift", period, List.of(
                    Column.integer("Shift", 7), Column.text("Cashier", 20), Column.dateTime("Opened", 16),
                    Column.dateTime("Closed", 16), Column.money("Opening Cash", 13), Column.money("Sales", 13),
                    Column.money("Counted", 13), Column.money("Difference", 12), Column.text("Status", 9)),
                    repo.salesByShift(from, to));
            case "SALES_BY_TYPE" -> new ReportData(key, "Sales by Order Type", period, List.of(
                    Column.text("Order Type", 20), Column.integer("Orders", 8), Column.money("Gross Sales", 14)),
                    repo.salesByType(from, to));
            case "SALES_BY_PAYMENT" -> new ReportData(key, "Sales by Payment Method", period, List.of(
                    Column.text("Payment Method", 22), Column.integer("Transactions", 12), Column.money("Amount", 14),
                    Column.money("Refunded", 13)), repo.salesByPaymentMethod(from, to));
            case "PRODUCT_PERFORMANCE" -> {
                List<Object[]> rows = new ArrayList<>();
                for (Object[] r : repo.productPerformance(from, to, 500)) {
                    BigDecimal revenue = (BigDecimal) r[3];
                    BigDecimal cost = (BigDecimal) r[4];
                    rows.add(new Object[]{r[0], r[1], r[2], revenue, cost, Money.sub(revenue, cost)});
                }
                yield new ReportData(key, "Product Performance", period, List.of(
                        Column.text("Product", 28), Column.text("SKU", 12), Column.integer("Qty Sold", 9),
                        Column.money("Revenue", 14), Column.money("Cost of Goods", 14), Column.money("Gross Profit", 14)), rows);
            }
            case "CATEGORY_PERFORMANCE" -> new ReportData(key, "Category Performance", period, List.of(
                    Column.text("Category", 22), Column.integer("Qty Sold", 9), Column.money("Revenue", 14)),
                    repo.categoryPerformance(from, to));
            case "TAX" -> new ReportData(key, "Tax Summary", period, List.of(
                    Column.date("Date", 12), Column.money("Tax Collected", 14), Column.money("Gross Sales", 14)),
                    repo.taxSummary(from, to));
            case "DISCOUNTS" -> new ReportData(key, "Discounts by Cashier", period, List.of(
                    Column.text("Cashier", 22), Column.money("Orders Gross", 14),
                    Column.money("Discounts", 13), Column.money("Net Sales", 14)), discountRows(from, to));
            case "REFUNDS" -> new ReportData(key, "Refund Register", period, List.of(
                    Column.integer("Refund #", 8), Column.text("Order", 18), Column.text("Method", 15),
                    Column.money("Amount", 13), Column.text("Reason", 30), Column.text("Approved By", 18),
                    Column.text("Recorded By", 18), Column.dateTime("Date", 16)), repo.refundsReport(from, to));
            case "CANCELLATIONS" -> new ReportData(key, "Cancellations", period, List.of(
                    Column.text("Order", 18), Column.text("Type", 15), Column.text("Reason", 30),
                    Column.text("Cashier", 20), Column.dateTime("Created", 16), Column.money("Value", 13)),
                    repo.cancellations(from, to));
            case "CASHIER_PERFORMANCE" -> new ReportData(key, "Cashier Performance", period, List.of(
                    Column.text("Cashier", 22), Column.integer("Orders", 8), Column.money("Sales", 14),
                    Column.money("Discounts", 13), Column.integer("Cancellations", 13), Column.money("Refunds", 13)),
                    repo.cashierPerformance(from, to));
            case "SHIFT_DISCREPANCY" -> new ReportData(key, "Cash Discrepancies", period, List.of(
                    Column.integer("Shift", 7), Column.text("Cashier", 20), Column.dateTime("Opened", 16),
                    Column.dateTime("Closed", 16), Column.money("Expected", 13), Column.money("Counted", 13),
                    Column.money("Difference", 13)), repo.shiftDiscrepancies(from, to));
            case "PURCHASES" -> {
                List<Object[]> rows = new ArrayList<>();
                for (Object[] r : repo.purchasesReport(from, to)) {
                    BigDecimal total = (BigDecimal) r[4];
                    BigDecimal paid = (BigDecimal) r[5];
                    rows.add(new Object[]{r[0], r[1], r[2], r[3], total, paid, Money.sub(total, paid)});
                }
                yield new ReportData(key, "Purchase Register", period, List.of(
                        Column.text("PO Number", 16), Column.text("Supplier", 24), Column.text("Status", 11),
                        Column.date("Order Date", 12), Column.money("Total", 13), Column.money("Paid", 13),
                        Column.money("Balance", 13)), rows);
            }
            case "EXPENSES" -> new ReportData(key, "Expense Register", period, List.of(
                    Column.date("Date", 12), Column.text("Category", 20), Column.money("Amount", 13),
                    Column.text("Method", 14), Column.text("Description", 36), Column.text("Status", 11)),
                    repo.expensesReport(from, to));
            case "INVENTORY_VALUATION" -> new ReportData(key, "Inventory Valuation", "Current stock", List.of(
                    Column.text("Ingredient", 26), Column.text("Unit", 8), Column.integer("Stock", 10),
                    Column.money("Average Cost", 13), Column.money("Stock Value", 14), Column.integer("Minimum", 9)),
                    repo.inventoryValuation());
            case "STOCK_MOVEMENTS" -> new ReportData(key, "Stock Movements", period, List.of(
                    Column.dateTime("Date", 16), Column.text("Ingredient", 22), Column.text("Type", 18),
                    Column.integer("Quantity", 10), Column.money("Unit Cost", 12), Column.text("Reference", 14),
                    Column.text("Reference ID", 12), Column.text("By", 16)), repo.stockMovements(from, to));
            default -> throw new com.restaurantpos.exception.AppException("Unknown report: " + key);
        };
    }

    private List<Object[]> discountRows(LocalDate from, LocalDate to) {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] r : repo.salesByCashier(from, to)) {
            BigDecimal gross = (BigDecimal) r[2];
            BigDecimal disc = (BigDecimal) r[3];
            rows.add(new Object[]{r[0], gross, disc, Money.sub(gross, disc)});
        }
        return rows;
    }
}
