package com.restaurantpos.app;

import com.restaurantpos.backup.BackupService;
import com.restaurantpos.dao.*;
import com.restaurantpos.drive.DriveService;
import com.restaurantpos.printing.ReceiptPrinter;
import com.restaurantpos.reporting.ReportExporter;
import com.restaurantpos.security.Session;
import com.restaurantpos.service.*;

public final class AppContext {
    private static AppContext instance;

    public static synchronized void resetForTesting() { instance = null; }

    private final Session session = new Session();
    private final UserRepository users = new UserRepository();
    private final MenuRepository menuRepo = new MenuRepository();
    private final OrderRepository orderRepo = new OrderRepository();
    private final FloorRepository floorRepo = new FloorRepository();
    private final FinanceRepository financeRepo = new FinanceRepository();
    private final InventoryRepository inventoryRepo = new InventoryRepository();
    private final ReportRepository reportRepo = new ReportRepository();
    private final SystemRepository systemRepo = new SystemRepository();
    private final CustomerLedgerRepository ledgerRepo = new CustomerLedgerRepository();

    private final SettingsService settings;
    private final AuditService audit;
    private final AuthService auth;
    private final MenuService menu;
    private final OrderService orders;
    private final ShiftService shifts;
    private final PaymentService payments;
    private final TableService tables;
    private final InventoryService inventory;
    private final PurchaseService purchasing;
    private final ExpenseService expenses;
    private final EmployeeService employees;
    private final CustomerLedgerService customerLedger;
    private final ReportService reports;
    private final ReportExporter exporter;
    private final DriveService drive;
    private final BackupService backups;
    private final ReceiptPrinter printer;

    private AppContext() {
        settings = new SettingsService(systemRepo);
        audit = new AuditService(systemRepo, session);
        auth = new AuthService(users, systemRepo, session);
        menu = new MenuService(menuRepo, audit);
        orders = new OrderService(orderRepo, floorRepo, inventoryRepo, settings, audit);
        shifts = new ShiftService(financeRepo, audit);
        payments = new PaymentService(orderRepo, orders, shifts, settings, audit);
        tables = new TableService(floorRepo, orderRepo, audit);
        inventory = new InventoryService(inventoryRepo, audit);
        purchasing = new PurchaseService(inventoryRepo, audit);
        expenses = new ExpenseService(financeRepo, audit);
        employees = new EmployeeService(users, audit);
        customerLedger = new CustomerLedgerService(ledgerRepo, menuRepo, orderRepo, floorRepo, shifts, audit);
        reports = new ReportService(reportRepo);
        exporter = new ReportExporter(audit, settings);
        drive = new DriveService(settings);
        backups = new BackupService(settings, reports, exporter, drive, systemRepo, audit);
        printer = new ReceiptPrinter(systemRepo, orderRepo, settings, audit);
    }

    public static synchronized AppContext get() {
        if (instance == null) {
            Bootstrap.initialize();
            instance = new AppContext();
        }
        return instance;
    }

    public Session session() { return session; }
    public UserRepository users() { return users; }
    public MenuRepository menuRepo() { return menuRepo; }
    public OrderRepository orderRepo() { return orderRepo; }
    public FloorRepository floorRepo() { return floorRepo; }
    public FinanceRepository financeRepo() { return financeRepo; }
    public InventoryRepository inventoryRepo() { return inventoryRepo; }
    public ReportRepository reportRepo() { return reportRepo; }
    public SystemRepository systemRepo() { return systemRepo; }
    public SettingsService settings() { return settings; }
    public AuditService audit() { return audit; }
    public AuthService auth() { return auth; }
    public MenuService menu() { return menu; }
    public OrderService orders() { return orders; }
    public ShiftService shifts() { return shifts; }
    public PaymentService payments() { return payments; }
    public TableService tables() { return tables; }
    public InventoryService inventory() { return inventory; }
    public PurchaseService purchasing() { return purchasing; }
    public ExpenseService expenses() { return expenses; }
    public EmployeeService employees() { return employees; }
    public CustomerLedgerRepository ledgerRepo() { return ledgerRepo; }
    public CustomerLedgerService customerLedger() { return customerLedger; }
    public ReportService reports() { return reports; }
    public ReportExporter exporter() { return exporter; }
    public DriveService drive() { return drive; }
    public BackupService backups() { return backups; }
    public ReceiptPrinter printer() { return printer; }
    public com.restaurantpos.printing.ReceiptPrintingService receiptService() { return printer; }

    public boolean isOwner() { return session.isOwner(); }
    public boolean canManage() { return session.canManage(); }
}
