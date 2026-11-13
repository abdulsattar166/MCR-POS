package com.restaurantpos.ui;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.ui.views.*;
import javafx.scene.Node;

public final class ViewFactory {
    private ViewFactory() {}

    public static Node create(String key, AppContext ctx, Navigator nav) {
        return switch (key) {
            case "dashboard" -> ctx.canManage() ? new DashboardView(ctx, nav) : new CashierDashboardView(ctx, nav);
            case "pos" -> new PosView(ctx, nav);
            case "draft-orders" -> new OrdersView(ctx, nav, OrdersView.ViewMode.DRAFT);
            case "complete-orders" -> new OrdersView(ctx, nav, OrdersView.ViewMode.COMPLETE);
            case "floors" -> new FloorView(ctx, nav);
            case "orders" -> new OrdersView(ctx, nav);
            case "shift" -> new ShiftView(ctx, nav);
            case "menu" -> new MenuAdminView(ctx, nav);
            case "inventory" -> new InventoryView(ctx, nav);
            case "purchasing" -> new PurchasingView(ctx, nav);
            case "payments" -> new PaymentsView(ctx, nav);
            case "customer-ledger" -> new CustomerLedgerView(ctx, nav);
            case "expenses" -> new ExpensesView(ctx, nav);
            case "staff" -> new StaffView(ctx, nav);
            case "reports" -> new ReportsView(ctx, nav);
            case "receipts" -> new ReceiptsView(ctx, nav);
            case "notifications" -> new NotificationsView(ctx, nav);
            case "audit" -> new AuditView(ctx, nav);
            case "backup" -> new BackupView(ctx, nav);
            case "settings" -> new SettingsView(ctx, nav);
            default -> throw new AppException("Unknown screen: " + key);
        };
    }
}
