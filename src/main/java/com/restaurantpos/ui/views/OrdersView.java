package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.dao.OrderRepository.OrderStats;
import com.restaurantpos.enums.*;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Sales.*;
import com.restaurantpos.model.SystemModels.PrintJob;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportExporter;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.OrderService.DraftChangeLine;
import com.restaurantpos.service.OrderService.DraftItemEdit;
import com.restaurantpos.service.OrderService.DraftSaveResult;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.UiState;
import com.restaurantpos.ui.components.AddDraftItemsDialog;
import com.restaurantpos.ui.components.ConfirmPaymentDialog;
import com.restaurantpos.ui.components.CustomerPickerDialog;
import com.restaurantpos.ui.components.ReceiptPreviewDialog;
import com.restaurantpos.ui.components.TablePicker;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class OrdersView extends AppView {

    public enum ViewMode {
        DRAFT("Draft Orders (Unpaid)", "fas-clipboard-list"),
        COMPLETE("Completed Orders (Paid)", "fas-check-double"),
        ALL("All Orders / History", "fas-history");

        private final String label;
        private final String icon;

        ViewMode(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() { return label; }
        public String icon() { return icon; }
    }

    private static final int PAGE_SIZE = 100;

    private ViewMode mode = ViewMode.DRAFT;
    private int currentPage = 0;
    private List<Order> allFilteredOrders = List.of();

    private final StackPane contentStack = new StackPane();
    private final VBox listContainer = new VBox(10);
    private final VBox detailPage = new VBox(12);

    private final HBox modeTabs = new HBox(8);
    private final Button draftTabBtn = new Button("Draft Orders (Unpaid)");
    private final Button completeTabBtn = new Button("Completed Orders (Paid)");
    private final Button allTabBtn = new Button("All Orders / History");

    private final FlowPane summaryBar = new FlowPane(10, 10);
    private final Label statTotalVal = Ui.label("0", "h2");
    private final Label statDraftVal = Ui.label("0", "h2");
    private final Label statCompleteVal = Ui.label("0", "h2");
    private final Label statSalesVal = Ui.label("PKR 0.00", "h2");
    private final Label statPaidVal = Ui.label("PKR 0.00", "h2");
    private final Label statBalanceVal = Ui.label("PKR 0.00", "h2");

    private final TextField search = Ui.textField("Search order #, customer, table...");
    private final DatePicker from = Ui.datePicker(LocalDate.now());
    private final DatePicker to = Ui.datePicker(LocalDate.now());
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final ComboBox<String> paymentFilter = new ComboBox<>();
    private final ComboBox<String> methodFilter = new ComboBox<>();
    private final ComboBox<User> cashierFilter = new ComboBox<>();
    private final FlowPane filterBar = new FlowPane(8, 8);
    private final FlowPane quickBar = new FlowPane(6, 6);
    private boolean currentShiftOnly = false;

    private final TableView<Order> table = Ui.table();
    private final Label count = Ui.muted("");
    private final Button prevPageBtn = Ui.ghost("Prev", "fas-chevron-left", () -> changePage(-1));
    private final Button nextPageBtn = Ui.ghost("Next", "fas-chevron-right", () -> changePage(1));
    private final Label pageLabel = Ui.muted("Page 1 of 1");
    private final ProgressIndicator exportProgress = new ProgressIndicator();

    private final Map<Long, String> itemSummaryCache = new ConcurrentHashMap<>();
    private final Map<Long, String> paymentMethodCache = new ConcurrentHashMap<>();
    private final Map<Long, String> productImageCache = new ConcurrentHashMap<>();
    private Long activeDetailOrderId = null;

    public OrdersView(AppContext ctx, Navigator nav) {
        this(ctx, nav, ViewMode.DRAFT);
    }

    public OrdersView(AppContext ctx, Navigator nav, ViewMode initialMode) {
        super(ctx, nav);
        this.mode = initialMode != null ? initialMode : ViewMode.DRAFT;

        exportProgress.setPrefSize(20, 20);
        exportProgress.setVisible(false);
        exportProgress.setManaged(false);

        getChildren().add(Ui.pageHeader("Orders & Payments",
                "Manage unpaid draft orders, complete counter payments, inspect order cards, and print receipts.",
                exportProgress,
                Ui.primary("New Order", "fas-plus", () -> nav.navigate("pos")),
                Ui.secondary("Export Excel", "fas-file-excel", this::exportExcel),
                Ui.secondary("Export PDF", "fas-file-pdf", this::exportPdf),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        buildSummaryCards();
        buildTabs();
        setupFilters();

        table.setPrefHeight(380);
        table.setPlaceholder(Ui.empty("No orders found", "Adjust filters or place a new order from POS."));
        table.setRowFactory(tv -> {
            TableRow<Order> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (!row.isEmpty() && ev.getClickCount() >= 1) {
                    // Open card on double-click or click when not clicking inside an inline button
                    if (ev.getTarget() instanceof Button || (ev.getTarget() instanceof Node n && isInsideButton(n))) {
                        return;
                    }
                    openOrderCard(row.getItem().id());
                }
            });
            return row;
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        HBox paginationBar = new HBox(8, prevPageBtn, pageLabel, nextPageBtn, Ui.spacer(), count);
        paginationBar.setAlignment(Pos.CENTER_LEFT);
        paginationBar.setPadding(new Insets(4, 2, 0, 2));

        listContainer.getChildren().addAll(summaryBar, modeTabs, filterBar, quickBar, table, paginationBar);
        VBox.setVgrow(listContainer, Priority.ALWAYS);

        detailPage.setPadding(new Insets(2));
        VBox.setVgrow(detailPage, Priority.ALWAYS);

        contentStack.getChildren().add(listContainer);
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        getChildren().add(contentStack);

        refresh();
    }

    private static boolean isInsideButton(Node node) {
        Node cur = node;
        while (cur != null) {
            if (cur instanceof ButtonBase) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private void buildSummaryCards() {
        summaryBar.getChildren().addAll(
                summaryCard("Total Orders", statTotalVal, "fas-receipt", "stat-icon-blue"),
                summaryCard("Draft Orders", statDraftVal, "fas-clipboard-list", "stat-icon-amber"),
                summaryCard("Completed Orders", statCompleteVal, "fas-check-double", "stat-icon-green"),
                summaryCard("Completed Sales", statSalesVal, "fas-coins", "stat-icon-accent"),
                summaryCard("Paid Amount", statPaidVal, "fas-wallet", "stat-icon-green"),
                summaryCard("Outstanding Balance", statBalanceVal, "fas-exclamation-circle", "stat-icon-amber")
        );
    }

    private VBox summaryCard(String title, Label valueLbl, String iconLiteral, String iconClass) {
        HBox top = new HBox(8, Ui.icon(iconLiteral, 14), Ui.muted(title));
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add(iconClass);
        valueLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        VBox card = new VBox(4, top, valueLbl);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(10, 14, 10, 14));
        card.setMinWidth(155);
        card.setPrefWidth(178);
        return card;
    }

    private void buildTabs() {
        modeTabs.setAlignment(Pos.CENTER_LEFT);
        modeTabs.setStyle("-fx-padding: 0 0 2 0;");

        styleTab(draftTabBtn, ViewMode.DRAFT);
        styleTab(completeTabBtn, ViewMode.COMPLETE);
        styleTab(allTabBtn, ViewMode.ALL);

        draftTabBtn.setOnAction(e -> setMode(ViewMode.DRAFT));
        completeTabBtn.setOnAction(e -> setMode(ViewMode.COMPLETE));
        allTabBtn.setOnAction(e -> setMode(ViewMode.ALL));

        modeTabs.getChildren().addAll(draftTabBtn, completeTabBtn, allTabBtn);
        updateTabStyles();
    }

    private void styleTab(Button btn, ViewMode vm) {
        btn.setGraphic(Ui.icon(vm.icon(), 14));
        btn.getStyleClass().add("secondary-button");
        btn.setPrefHeight(36);
        btn.setStyle("-fx-font-weight: bold; -fx-padding: 6 16;");
    }

    private void updateTabStyles() {
        long draftCount = ctx.orders().draftOrders("").size();
        draftTabBtn.setText("Draft Orders (Unpaid) (" + draftCount + ")");

        draftTabBtn.getStyleClass().removeAll("primary-button", "secondary-button");
        completeTabBtn.getStyleClass().removeAll("primary-button", "secondary-button");
        allTabBtn.getStyleClass().removeAll("primary-button", "secondary-button");

        draftTabBtn.getStyleClass().add(mode == ViewMode.DRAFT ? "primary-button" : "secondary-button");
        completeTabBtn.getStyleClass().add(mode == ViewMode.COMPLETE ? "primary-button" : "secondary-button");
        allTabBtn.getStyleClass().add(mode == ViewMode.ALL ? "primary-button" : "secondary-button");
    }

    private void setMode(ViewMode newMode) {
        if (this.mode == newMode) return;
        this.mode = newMode;
        this.currentPage = 0;
        updateTabStyles();
        updateFilterVisibility();
        configureTableColumns();
        load();
    }

    private void updateFilterVisibility() {
        boolean isAll = mode == ViewMode.ALL;
        boolean isDraft = mode == ViewMode.DRAFT;

        from.setVisible(!isDraft); from.setManaged(!isDraft);
        to.setVisible(!isDraft); to.setManaged(!isDraft);
        typeFilter.setVisible(isAll); typeFilter.setManaged(isAll);
        statusFilter.setVisible(isAll); statusFilter.setManaged(isAll);
        paymentFilter.setVisible(isAll); paymentFilter.setManaged(isAll);
        methodFilter.setVisible(mode == ViewMode.COMPLETE || isAll);
        methodFilter.setManaged(mode == ViewMode.COMPLETE || isAll);
        cashierFilter.setVisible(isAll); cashierFilter.setManaged(isAll);
    }

    private void setupFilters() {
        search.setPrefWidth(250);
        Ui.bindSearch(search, () -> { currentPage = 0; load(); });

        from.valueProperty().addListener((o, a, b) -> { currentShiftOnly = false; currentPage = 0; load(); });
        to.valueProperty().addListener((o, a, b) -> { currentShiftOnly = false; currentPage = 0; load(); });

        typeFilter.getItems().add("All types");
        for (OrderType t : OrderType.values()) typeFilter.getItems().add(t.label());
        typeFilter.getSelectionModel().selectFirst();
        typeFilter.setOnAction(e -> { currentPage = 0; load(); });

        statusFilter.getItems().add("All statuses");
        for (OrderStatus s : OrderStatus.values()) statusFilter.getItems().add(s.label());
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.setOnAction(e -> { currentPage = 0; load(); });

        paymentFilter.getItems().add("All payment statuses");
        for (PaymentStatus ps : PaymentStatus.values()) paymentFilter.getItems().add(ps.label());
        paymentFilter.getSelectionModel().selectFirst();
        paymentFilter.setOnAction(e -> { currentPage = 0; load(); });

        methodFilter.getItems().add("All payment methods");
        for (PaymentMethod pm : PaymentMethod.values()) methodFilter.getItems().add(pm.label());
        methodFilter.getSelectionModel().selectFirst();
        methodFilter.setOnAction(e -> { currentPage = 0; load(); });

        cashierFilter.getItems().add(null);
        cashierFilter.getItems().addAll(ctx.users().all());
        cashierFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(User u) { return u == null ? "All cashiers" : u.fullName(); }
            @Override public User fromString(String s) { return null; }
        });
        cashierFilter.setOnAction(e -> { currentPage = 0; load(); });

        filterBar.getChildren().addAll(search, from, to, typeFilter, statusFilter, paymentFilter, methodFilter, cashierFilter);

        quickBar.setAlignment(Pos.CENTER_LEFT);
        quickBar.getChildren().addAll(
                Ui.ghost("Today", "fas-calendar-day", () -> { currentShiftOnly = false; from.setValue(LocalDate.now()); to.setValue(LocalDate.now()); currentPage = 0; load(); }),
                Ui.ghost("Current Shift", "fas-user-clock", () -> { currentShiftOnly = true; from.setValue(LocalDate.now()); to.setValue(LocalDate.now()); currentPage = 0; load(); }),
                Ui.ghost("7 days", "fas-calendar-week", () -> { currentShiftOnly = false; from.setValue(LocalDate.now().minusDays(6)); to.setValue(LocalDate.now()); currentPage = 0; load(); }),
                Ui.ghost("This month", "fas-calendar-alt", () -> { currentShiftOnly = false; from.setValue(LocalDate.now().withDayOfMonth(1)); to.setValue(LocalDate.now()); currentPage = 0; load(); }),
                Ui.ghost("All dates", "fas-infinity", () -> { currentShiftOnly = false; from.setValue(null); to.setValue(null); currentPage = 0; load(); })
        );

        updateFilterVisibility();
        configureTableColumns();
    }

    private void configureTableColumns() {
        table.getColumns().clear();

        if (mode == ViewMode.DRAFT) {
            table.getColumns().addAll(
                    Ui.col("Order No.", Order::orderNo, 125),
                    Ui.col("Date/Time", o -> Ui.dateTime(o.createdAt()), 125),
                    Ui.col("Customer", o -> o.customerName() == null || o.customerName().isBlank() ? "Walk-in" : o.customerName(), 110),
                    Ui.col("Table", o -> o.tableNo() == null ? "—" : o.tableNo(), 65),
                    Ui.col("Type", o -> o.type().label(), 85),
                    Ui.col("Items", o -> itemSummaryCache.getOrDefault(o.id(), "—"), 175),
                    Ui.moneyCol("Total", Order::total, 88),
                    Ui.moneyCol("Paid", Order::paidTotal, 80),
                    Ui.moneyCol("Balance", Order::balance, 88),
                    statusBadgeCol("Payment", o -> o.paymentStatus().label(), 98),
                    statusBadgeCol("Status", o -> o.status().label(), 88),
                    draftActionCol()
            );
        } else if (mode == ViewMode.COMPLETE) {
            table.getColumns().addAll(
                    Ui.col("Order No.", Order::orderNo, 130),
                    Ui.col("Date/Time", o -> Ui.dateTime(o.completedAt() != null ? o.completedAt() : o.createdAt()), 130),
                    Ui.col("Customer", o -> o.customerName() == null || o.customerName().isBlank() ? "Walk-in" : o.customerName(), 115),
                    Ui.col("Table", o -> o.tableNo() == null ? "—" : o.tableNo(), 65),
                    Ui.col("Items", o -> itemSummaryCache.getOrDefault(o.id(), "—"), 160),
                    Ui.moneyCol("Total", Order::total, 92),
                    Ui.col("Payment Method", o -> paymentMethodCache.getOrDefault(o.id(), o.paymentStatus() == PaymentStatus.ON_ACCOUNT ? "Credit Ledger" : "—"), 120),
                    statusBadgeCol("Payment Status", o -> o.paymentStatus().label(), 105),
                    statusBadgeCol("Order Status", o -> o.status().label(), 95),
                    completeActionCol()
            );
        } else {
            table.getColumns().addAll(
                    Ui.col("Order No.", Order::orderNo, 130),
                    Ui.col("Date/Time", o -> Ui.dateTime(o.createdAt()), 125),
                    Ui.col("Type", o -> o.type().label(), 88),
                    Ui.col("Table", o -> o.tableNo() == null ? "—" : o.tableNo(), 60),
                    Ui.col("Customer", o -> o.customerName() == null || o.customerName().isBlank() ? "Walk-in" : o.customerName(), 115),
                    Ui.col("Items", o -> itemSummaryCache.getOrDefault(o.id(), "—"), 175),
                    Ui.moneyCol("Total", Order::total, 88),
                    Ui.moneyCol("Paid", Order::paidTotal, 80),
                    statusBadgeCol("Status", o -> o.status().label(), 95),
                    statusBadgeCol("Payment", o -> o.paymentStatus().label(), 100),
                    allActionCol()
            );
        }
    }

    private TableColumn<Order, String> statusBadgeCol(String title, java.util.function.Function<Order, String> fn, double width) {
        TableColumn<Order, String> col = Ui.col(title, fn, width);
        col.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String style = switch (item.toUpperCase(Locale.ROOT)) {
                    case "PAID", "COMPLETED" -> "primary";
                    case "PARTIAL", "ON ACCOUNT", "DRAFT", "PENDING PAYMENT" -> "accent";
                    default -> "muted";
                };
                setText(null);
                setGraphic(Ui.chip(item, style));
            }
        });
        return col;
    }

    private TableColumn<Order, Void> draftActionCol() {
        TableColumn<Order, Void> col = new TableColumn<>("Action");
        col.setPrefWidth(155);
        col.setSortable(false);
        col.setCellFactory(tc -> new TableCell<>() {
            private final Button openBtn = Ui.primary("Open / Pay", "fas-cash-register", () -> {
                Order o = getTableRow() == null ? null : getTableRow().getItem();
                if (o != null) openOrderCard(o.id());
            });
            private final Button printBillBtn = Ui.ghost("", "fas-print", () -> {
                Order o = getTableRow() == null ? null : getTableRow().getItem();
                if (o != null) printPaidCustomerReceipt(o, false);
            });
            private final HBox box = new HBox(6, openBtn, printBillBtn);
            {
                openBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11.5px;");
                printBillBtn.setTooltip(new Tooltip("Print Unpaid Customer Bill"));
                printBillBtn.setStyle("-fx-padding: 4 8;");
                box.setAlignment(Pos.CENTER_LEFT);
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
        return col;
    }

    private TableColumn<Order, Void> completeActionCol() {
        TableColumn<Order, Void> col = new TableColumn<>("Action");
        col.setPrefWidth(135);
        col.setSortable(false);
        col.setCellFactory(tc -> new TableCell<>() {
            private final Button viewBtn = Ui.secondary("View / Receipt", "fas-receipt", () -> {
                Order o = getTableRow() == null ? null : getTableRow().getItem();
                if (o != null) openOrderCard(o.id());
            });
            {
                viewBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11.5px;");
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : viewBtn);
            }
        });
        return col;
    }

    private TableColumn<Order, Void> allActionCol() {
        TableColumn<Order, Void> col = new TableColumn<>("Action");
        col.setPrefWidth(95);
        col.setSortable(false);
        col.setCellFactory(tc -> new TableCell<>() {
            private final Button viewBtn = Ui.ghost("Open", "fas-eye", () -> {
                Order o = getTableRow() == null ? null : getTableRow().getItem();
                if (o != null) openOrderCard(o.id());
            });
            {
                viewBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11.5px;");
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : viewBtn);
            }
        });
        return col;
    }

    @Override
    public void refresh() {
        if (UiState.pendingOrdersSearch != null) {
            search.setText(UiState.pendingOrdersSearch);
            UiState.pendingOrdersSearch = null;
        }
        updateSummaryCards();
        updateTabStyles();
        load();
        if (UiState.pendingOrderId != null) {
            long id = UiState.pendingOrderId;
            UiState.pendingOrderId = null;
            table.getItems().stream().filter(o -> o.id() == id).findFirst().ifPresent(o -> {
                table.getSelectionModel().select(o);
                table.scrollTo(o);
            });
        }
        if (activeDetailOrderId != null) {
            openOrderCard(activeDetailOrderId);
        }
    }

    private void updateSummaryCards() {
        LocalDate f = mode == ViewMode.DRAFT ? null : from.getValue();
        LocalDate t = mode == ViewMode.DRAFT ? null : to.getValue();
        OrderStats stats = ctx.orders().summaryStats(f, t);
        statTotalVal.setText(String.valueOf(stats.totalOrders()));
        statDraftVal.setText(String.valueOf(stats.draftOrders()));
        statCompleteVal.setText(String.valueOf(stats.completedOrders()));
        statSalesVal.setText(Money.fmt(stats.completedSales()));
        statPaidVal.setText(Money.fmt(stats.paidAmount()));
        statBalanceVal.setText(Money.fmt(stats.outstandingBalance()));
    }

    private void load() {
        List<Order> orders;
        String q = search.getText() == null ? "" : search.getText().trim();

        if (mode == ViewMode.DRAFT) {
            orders = ctx.orders().draftOrders(q);
        } else if (mode == ViewMode.COMPLETE) {
            orders = ctx.orders().completeOrders(from.getValue(), to.getValue(), q);
        } else {
            OrderType type = typeFilter.getSelectionModel().getSelectedIndex() <= 0 ? null
                    : OrderType.values()[typeFilter.getSelectionModel().getSelectedIndex() - 1];
            OrderStatus status = statusFilter.getSelectionModel().getSelectedIndex() <= 0 ? null
                    : OrderStatus.values()[statusFilter.getSelectionModel().getSelectedIndex() - 1];
            String payment = paymentFilter.getSelectionModel().getSelectedIndex() <= 0 ? null
                    : PaymentStatus.values()[paymentFilter.getSelectionModel().getSelectedIndex() - 1].name();
            User cashier = cashierFilter.getValue();
            orders = ctx.orders().list(from.getValue(), to.getValue(), type, status,
                    cashier == null ? null : cashier.id(), payment, q);
        }

        if (currentShiftOnly) {
            var curShift = ctx.shifts().current(ctx.session().userId());
            if (curShift != null) {
                orders = orders.stream().filter(o -> Objects.equals(o.shiftId(), curShift.id())).toList();
            }
        }

        List<Long> orderIds = orders.stream().map(Order::id).toList();
        itemSummaryCache.clear();
        itemSummaryCache.putAll(ctx.orders().itemSummaries(orderIds));
        paymentMethodCache.clear();
        paymentMethodCache.putAll(ctx.orders().paymentMethods(orderIds));

        int methodIdx = methodFilter.getSelectionModel().getSelectedIndex();
        if (methodIdx > 0 && methodIdx <= PaymentMethod.values().length) {
            String methodLabel = PaymentMethod.values()[methodIdx - 1].label();
            orders = orders.stream()
                    .filter(o -> paymentMethodCache.getOrDefault(o.id(), "").contains(methodLabel))
                    .toList();
        }

        this.allFilteredOrders = orders;
        renderPage();
        updateSummaryCards();
    }

    private void changePage(int delta) {
        int totalPages = Math.max(1, (int) Math.ceil((double) allFilteredOrders.size() / PAGE_SIZE));
        currentPage = Math.max(0, Math.min(totalPages - 1, currentPage + delta));
        renderPage();
    }

    private void renderPage() {
        int total = allFilteredOrders.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        if (currentPage >= totalPages) currentPage = totalPages - 1;
        int fromIdx = currentPage * PAGE_SIZE;
        int toIdx = Math.min(total, fromIdx + PAGE_SIZE);
        List<Order> pageSlice = fromIdx < toIdx ? allFilteredOrders.subList(fromIdx, toIdx) : List.of();

        table.getItems().setAll(pageSlice);
        prevPageBtn.setDisable(currentPage <= 0);
        nextPageBtn.setDisable(currentPage >= totalPages - 1);
        pageLabel.setText("Page " + (currentPage + 1) + " of " + totalPages);

        BigDecimal sum = allFilteredOrders.stream().map(Order::total).reduce(Money.ZERO, BigDecimal::add);
        count.setText(total + " order(s)  ·  " + Money.fmt(sum));
    }

    private void closeOrderCard() {
        activeDetailOrderId = null;
        contentStack.getChildren().setAll(listContainer);
        refresh();
    }

    private void openOrderCard(long orderId) {
        Order order = ctx.orders().find(orderId);
        if (order == null) {
            warn("Order could not be found.");
            closeOrderCard();
            return;
        }
        activeDetailOrderId = order.id();
        detailPage.getChildren().clear();

        String backLabel = switch (mode) {
            case DRAFT -> "Back to Draft Orders";
            case COMPLETE -> "Back to Completed Orders";
            case ALL -> "Back to Orders";
        };
        Button backBtn = Ui.secondary(backLabel, "fas-arrow-left", this::closeOrderCard);

        Label orderTitle = Ui.label(order.orderNo(), "h2");
        Label sub = Ui.muted(order.type().label() + "  ·  Created " + Ui.dateTime(order.createdAt())
                + (order.completedAt() != null ? "  ·  Completed " + Ui.dateTime(order.completedAt()) : ""));
        HBox topBar = new HBox(12,
                backBtn,
                new VBox(2, orderTitle, sub),
                Ui.spacer(),
                Ui.chip(order.status().label(), order.status() == OrderStatus.COMPLETED ? "primary" : "accent"),
                Ui.chip(order.paymentStatus().label(), order.paymentStatus() == PaymentStatus.PAID ? "primary" : "accent")
        );
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("card");
        topBar.setPadding(new Insets(12, 16, 12, 16));

        boolean isCompletedOrPaid = order.status() == OrderStatus.COMPLETED || order.paymentStatus() == PaymentStatus.PAID;
        boolean canEditDraft = !isCompletedOrPaid && order.isOpen();
        List<OrderItem> savedItems = ctx.orders().items(order.id());
        List<DraftItemEdit> workingItems = new ArrayList<>();
        for (OrderItem si : savedItems) {
            workingItems.add(DraftItemEdit.fromOrderItem(si));
        }
        final boolean[] draftDirty = {false};

        VBox financeCard = new VBox(8);
        financeCard.getStyleClass().add("card");
        financeCard.setPadding(new Insets(14));
        financeCard.setMinWidth(320);

        VBox itemsCard = new VBox(8);
        itemsCard.getStyleClass().add("card");
        itemsCard.setPadding(new Insets(14));

        Button saveEditsBtn = Ui.primary("Save Changes", "fas-save", null);
        Button cancelEditsBtn = Ui.ghost("Cancel Changes", "fas-undo", null);
        saveEditsBtn.setDisable(true);
        cancelEditsBtn.setDisable(true);

        Runnable[] refreshDraftUi = new Runnable[1];
        refreshDraftUi[0] = () -> {
            saveEditsBtn.setDisable(!draftDirty[0]);
            cancelEditsBtn.setDisable(!draftDirty[0]);

            // Recalculate Financial Summary live
            Cart.Totals previewTotals = draftDirty[0]
                    ? ctx.orders().quoteDraftPreview(order, workingItems)
                    : null;
            BigDecimal subAmt = previewTotals != null ? previewTotals.subtotal : order.subtotal();
            BigDecimal discAmt = previewTotals != null ? previewTotals.discount : order.discountTotal();
            BigDecimal taxAmt = previewTotals != null ? previewTotals.tax : order.taxTotal();
            BigDecimal svcAmt = previewTotals != null ? previewTotals.service : order.serviceCharge();
            BigDecimal delivAmt = previewTotals != null ? previewTotals.delivery : order.deliveryCharge();
            BigDecimal grandTotal = previewTotals != null ? previewTotals.total : order.total();
            BigDecimal paidAmt = Money.nz(order.paidTotal());
            BigDecimal creditAmt = Money.nz(order.creditAmount());
            BigDecimal outstanding = Money.maxZero(Money.sub(grandTotal, Money.add(paidAmt, creditAmt)));
            BigDecimal refundDue = Money.maxZero(Money.sub(paidAmt, grandTotal));

            financeCard.getChildren().setAll(Ui.h3(draftDirty[0] ? "Financial Summary (Unsaved Preview)" : "Financial Summary"));
            GridPane totals = new GridPane();
            totals.setHgap(24);
            totals.setVgap(5);
            int t = 0;
            addTotal(totals, t++, "Subtotal", subAmt);
            addTotal(totals, t++, "Discount", discAmt);
            addTotal(totals, t++, "Tax", taxAmt);
            if (Money.nz(svcAmt).signum() > 0) addTotal(totals, t++, "Service Charge", svcAmt);
            if (Money.nz(delivAmt).signum() > 0) addTotal(totals, t++, "Delivery Charge", delivAmt);
            addTotal(totals, t++, "Grand Total", grandTotal);
            addTotal(totals, t++, "Amount Paid", paidAmt);
            if (creditAmt.signum() > 0) addTotal(totals, t++, "On Account (Ledger)", creditAmt);
            if (Money.nz(order.changeDue()).signum() > 0) addTotal(totals, t++, "Change Due", order.changeDue());
            String methodsStr = paymentMethodCache.getOrDefault(order.id(),
                    order.paymentStatus() == PaymentStatus.ON_ACCOUNT ? "Customer Ledger" : "—");
            addFact(totals, t++, "Payment Method", methodsStr);
            if (outstanding.signum() > 0) {
                Label balVal = Ui.label(Money.fmt(outstanding), "total-amount");
                balVal.setStyle("-fx-text-fill: -pos-accent-dark; -fx-font-size: 16px; -fx-font-weight: bold;");
                totals.add(Ui.label("Outstanding Balance:", "field-label"), 0, t);
                totals.add(balVal, 1, t++);
            }
            if (refundDue.signum() > 0) {
                Label refVal = Ui.label(Money.fmt(refundDue), "total-amount");
                refVal.setStyle("-fx-text-fill: #B42318; -fx-font-size: 15px; -fx-font-weight: bold;");
                totals.add(Ui.label("Credit / Refund Required:", "field-label"), 0, t);
                totals.add(refVal, 1, t);
            }
            financeCard.getChildren().add(totals);

            // Render Order Items Card
            itemsCard.getChildren().clear();
            HBox itemsHeader = new HBox(10, Ui.h3("Order Items (" + workingItems.size() + ")"), Ui.spacer());
            itemsHeader.setAlignment(Pos.CENTER_LEFT);
            if (canEditDraft) {
                Button addItemsBtn = Ui.secondary("Add Items", "fas-plus-circle", () -> {
                    AddDraftItemsDialog dlg = new AddDraftItemsDialog(ctx, order.orderNo(), nav.window());
                    dlg.showAndWait().ifPresent(added -> {
                        if (added.isEmpty()) return;
                        for (DraftItemEdit ni : added) {
                            boolean merged = false;
                            for (int idx = 0; idx < workingItems.size(); idx++) {
                                DraftItemEdit cur = workingItems.get(idx);
                                if (Objects.equals(cur.productId(), ni.productId())
                                        && Objects.equals(cur.variantId(), ni.variantId())
                                        && Objects.equals(cur.addons() == null ? "" : cur.addons(), ni.addons() == null ? "" : ni.addons())
                                        && Objects.equals(cur.instructions() == null ? "" : cur.instructions(), ni.instructions() == null ? "" : ni.instructions())
                                        && Money.nz(cur.unitPrice()).compareTo(Money.nz(ni.unitPrice())) == 0) {
                                    workingItems.set(idx, new DraftItemEdit(
                                            cur.orderItemId(), cur.productId(), cur.variantId(), cur.name(), cur.sku(),
                                            cur.unitPrice(), cur.cost(), cur.qty() + ni.qty(),
                                            cur.addons(), cur.instructions(), cur.discount()));
                                    merged = true;
                                    break;
                                }
                            }
                            if (!merged) workingItems.add(ni);
                        }
                        draftDirty[0] = true;
                        refreshDraftUi[0].run();
                    });
                });
                itemsHeader.getChildren().addAll(addItemsBtn, saveEditsBtn, cancelEditsBtn);
            }
            itemsCard.getChildren().add(itemsHeader);

            if (workingItems.isEmpty()) {
                Label emptyWarn = Ui.label("This draft order has no items. Add at least one item before saving or completing payment.", "inline-error");
                emptyWarn.setVisible(true);
                emptyWarn.setManaged(true);
                itemsCard.getChildren().add(emptyWarn);
            } else {
                for (int i = 0; i < workingItems.size(); i++) {
                    final int idx = i;
                    DraftItemEdit item = workingItems.get(i);
                    itemsCard.getChildren().add(buildDraftItemRow(item, canEditDraft,
                            () -> { // Increase qty
                                workingItems.set(idx, new DraftItemEdit(
                                        item.orderItemId(), item.productId(), item.variantId(), item.name(), item.sku(),
                                        item.unitPrice(), item.cost(), item.qty() + 1,
                                        item.addons(), item.instructions(), item.discount()));
                                draftDirty[0] = true;
                                refreshDraftUi[0].run();
                            },
                            () -> { // Decrease qty
                                if (item.qty() <= 1) {
                                    if (Ui.confirm(nav.window(), "Remove Item",
                                            "Quantity will reach zero. Remove \"" + item.name() + "\" from order " + order.orderNo() + "?")) {
                                        workingItems.remove(idx);
                                        draftDirty[0] = true;
                                        refreshDraftUi[0].run();
                                    }
                                } else {
                                    workingItems.set(idx, new DraftItemEdit(
                                            item.orderItemId(), item.productId(), item.variantId(), item.name(), item.sku(),
                                            item.unitPrice(), item.cost(), item.qty() - 1,
                                            item.addons(), item.instructions(), item.discount()));
                                    draftDirty[0] = true;
                                    refreshDraftUi[0].run();
                                }
                            },
                            () -> { // Remove item
                                if (Ui.confirm(nav.window(), "Remove Item",
                                        "Remove \"" + item.name() + "\" (" + item.qty() + "x) from order " + order.orderNo() + "?")) {
                                    workingItems.remove(idx);
                                    draftDirty[0] = true;
                                    refreshDraftUi[0].run();
                                }
                            }));
                }
            }
        };

        saveEditsBtn.setOnAction(e -> {
            try {
                String snapshotToken = com.restaurantpos.service.OrderService.draftSnapshotToken(order, savedItems);
                DraftSaveResult res = ctx.orders().saveDraftEdits(order.id(), snapshotToken, workingItems, ctx.session());
                draftDirty[0] = false;
                toast("Draft order " + order.orderNo() + " saved. Total: " + Money.fmt(res.updatedOrder().total()));
                openOrderCard(order.id());
            } catch (Exception ex) {
                Ui.error(nav.window(), "Could Not Save Draft Changes", ex.getMessage());
                openOrderCard(order.id());
            }
        });

        cancelEditsBtn.setOnAction(e -> {
            draftDirty[0] = false;
            openOrderCard(order.id());
        });

        // Status-appropriate action bar (NO automatic customer receipt on opening draft!)
        FlowPane actions = new FlowPane(8, 8);
        BigDecimal balance = order.balance();

        if (canEditDraft) {
            Button payBtn = Ui.primary("Complete Order & Pay", "fas-cash-register", () -> {
                if (draftDirty[0]) {
                    warn("Save or cancel your pending item changes before completing payment.");
                    return;
                }
                if (workingItems.isEmpty()) {
                    warn("Cannot complete an empty order. Add at least one item first.");
                    return;
                }
                Order fresh = ctx.orders().find(order.id());
                if (fresh == null) { warn("Order not found."); return; }
                if (Money.nz(fresh.paidTotal()).compareTo(fresh.total()) > 0) {
                    warn("Amount already paid exceeds the updated order total. Process a refund before completing.");
                    return;
                }
                ConfirmPaymentDialog dialog = new ConfirmPaymentDialog(ctx, fresh, nav.window(), result -> {
                    toast("Order " + result.orderNo() + " payment recorded.");
                    Order updated = ctx.orders().find(order.id());
                    if (updated != null && updated.paymentStatus() == PaymentStatus.PAID) {
                        ReceiptPreviewDialog preview = new ReceiptPreviewDialog(ctx, updated, nav.window(), null, null);
                        preview.showAndWait();
                        nav.navigate("pos");
                    } else {
                        openOrderCard(order.id());
                    }
                }, customerId -> {
                    UiState.pendingCustomerId = customerId;
                    nav.navigate("customer-ledger");
                });
                dialog.showAndWait();
            });
            payBtn.setPrefHeight(38);
            actions.getChildren().add(payBtn);

            if (balance.signum() > 0) {
                Button ledgerBtn = Ui.secondary("Add to Customer Ledger", "fas-book", () -> {
                    if (draftDirty[0]) {
                        warn("Save or cancel your pending item changes before adding to the customer ledger.");
                        return;
                    }
                    if (workingItems.isEmpty()) {
                        warn("Cannot add an empty order to a customer ledger.");
                        return;
                    }
                    CustomerPickerDialog picker = new CustomerPickerDialog(ctx, nav.window(),
                            "Charge Order " + order.orderNo() + " (" + Money.fmt(balance) + ") to Customer Ledger", null);
                    picker.showAndWait().ifPresent(customer -> {
                        if (Ui.confirm(nav.window(), "Add to Customer Ledger",
                                "Record " + Money.fmt(balance) + " on " + customer.name() + "'s credit ledger?\n\n"
                                        + "Note: Records ON_ACCOUNT credit transaction without recording cash sales.")) {
                            try {
                                ctx.customerLedger().addOrderToLedger(customer.id(), order.id(), balance, "From Draft Orders", ctx.session());
                                toast("Added " + Money.fmt(balance) + " to " + customer.name() + "'s ledger.");
                                openOrderCard(order.id());
                            } catch (Exception ex) {
                                Ui.error(nav.window(), ex);
                            }
                        }
                    });
                });
                actions.getChildren().add(ledgerBtn);
            }
        }

        if (order.paymentStatus() == PaymentStatus.PAID) {
            actions.getChildren().add(Ui.primary("Preview Paid Receipt", "fas-eye", () -> {
                ReceiptPreviewDialog dialog = new ReceiptPreviewDialog(ctx, order, nav.window(), null, null);
                dialog.showAndWait();
            }));
            actions.getChildren().add(Ui.secondary("Print Receipt", "fas-print", () -> printPaidCustomerReceipt(order, false)));
            actions.getChildren().add(Ui.secondary("Reprint Receipt", "fas-redo", () -> printPaidCustomerReceipt(order, true)));
            actions.getChildren().add(Ui.ghost("Download 80mm PDF", "fas-file-pdf", () -> openPdf(order)));
        }

        if (order.status() != OrderStatus.CANCELLED && order.paymentStatus() != PaymentStatus.PAID) {
            actions.getChildren().add(Ui.secondary("Preview Unpaid Bill", "fas-eye", () -> {
                ReceiptPreviewDialog dialog = new ReceiptPreviewDialog(ctx, order, nav.window(), null, null);
                dialog.showAndWait();
            }));
            actions.getChildren().add(Ui.ghost("Print Unpaid Bill", "fas-print", () -> printPaidCustomerReceipt(order, false)));
        }

        if (canEditDraft) {
            actions.getChildren().add(Ui.secondary("Transfer Table", "fas-exchange-alt", () -> {
                TablePicker picker = new TablePicker(ctx, nav.window(), "Transfer order " + order.orderNo());
                picker.showAndWait().ifPresent(target -> {
                    try {
                        ctx.orders().transferTable(order.id(), target.id(), ctx.session());
                        toast("Order transferred.");
                        openOrderCard(order.id());
                    } catch (Exception e) {
                        Ui.error(nav.window(), e);
                    }
                });
            }));
            actions.getChildren().add(Ui.ghost("Cancel Order", "fas-times", () -> cancel(order)));
        }

        if (order.customerId() != null) {
            actions.getChildren().add(Ui.ghost("Open Customer Ledger", "fas-book-open", () -> {
                UiState.pendingCustomerId = order.customerId();
                nav.navigate("customer-ledger");
            }));
        }

        // Order Info + Financial Summary side-by-side
        VBox infoCard = new VBox(8);
        infoCard.getStyleClass().add("card");
        infoCard.setPadding(new Insets(14));
        infoCard.getChildren().add(Ui.h3("Order Information"));
        GridPane facts = new GridPane();
        facts.setHgap(18);
        facts.setVgap(5);
        int r = 0;
        addFact(facts, r++, "Order Number", order.orderNo());
        addFact(facts, r++, "Order Type", order.type().label());
        addFact(facts, r++, "Created At", Ui.dateTime(order.createdAt()));
        if (order.completedAt() != null) addFact(facts, r++, "Completed At", Ui.dateTime(order.completedAt()));
        addFact(facts, r++, "Table", order.tableNo() == null ? "—" : order.tableNo());
        addFact(facts, r++, "Cashier", order.cashierName());
        addFact(facts, r++, "Customer", order.customerName() == null || order.customerName().isBlank() ? "Walk-in" : order.customerName());
        if (order.customerPhone() != null && !order.customerPhone().isBlank()) addFact(facts, r++, "Phone", order.customerPhone());
        if (order.deliveryAddress() != null && !order.deliveryAddress().isBlank()) addFact(facts, r++, "Address", order.deliveryAddress());
        if (order.notes() != null && !order.notes().isBlank()) addFact(facts, r++, "Notes", order.notes());
        infoCard.getChildren().add(facts);
        HBox.setHgrow(infoCard, Priority.ALWAYS);

        HBox topCards = new HBox(12, infoCard, financeCard);

        // Initial render of items + finance summary
        refreshDraftUi[0].run();

        // Additional Info: Payments, Refunds, Print Jobs
        VBox historyCard = new VBox(8);
        historyCard.getStyleClass().add("card");
        historyCard.setPadding(new Insets(14));
        historyCard.getChildren().add(Ui.h3("Payment, Ledger & Printing History"));

        List<Payment> payments = ctx.payments().payments(order.id());
        historyCard.getChildren().add(Ui.label("PAYMENTS", "field-label"));
        if (payments.isEmpty()) {
            historyCard.getChildren().add(Ui.muted("No payments recorded yet."));
        } else {
            for (Payment p : payments) {
                HBox pRow = new HBox(10);
                pRow.setAlignment(Pos.CENTER_LEFT);
                VBox pInfo = new VBox(2,
                        Ui.label(p.method().label() + "  ·  " + Money.fmt(p.amount()), "h3"),
                        Ui.muted((p.reference() == null || p.reference().isBlank() ? "No reference" : "Ref: " + p.reference())
                                + "  ·  " + Ui.dateTime(p.createdAt())));
                HBox.setHgrow(pInfo, Priority.ALWAYS);
                pRow.getChildren().add(pInfo);
                if (ctx.canManage() && p.refundable().signum() > 0) {
                    pRow.getChildren().add(Ui.ghost("Refund", "fas-undo-alt", () -> refund(p)));
                }
                historyCard.getChildren().add(pRow);
            }
        }

        List<Refund> refunds = ctx.payments().refunds(order.id());
        if (!refunds.isEmpty()) {
            historyCard.getChildren().add(Ui.label("REFUNDS", "field-label"));
            for (Refund rf : refunds) {
                historyCard.getChildren().add(Ui.muted(Ui.dateTime(rf.createdAt()) + "  ·  " + Money.fmt(rf.amount())
                        + "  ·  " + rf.reason() + "  ·  " + rf.approvedBy()));
            }
        }

        if (order.customerId() != null) {
            List<CustomerLedgerEntry> ledgerEntries = ctx.customerLedger().entries(order.customerId()).stream()
                    .filter(e -> Objects.equals(e.orderId(), order.id()))
                    .toList();
            if (!ledgerEntries.isEmpty()) {
                historyCard.getChildren().add(Ui.label("CUSTOMER LEDGER TRANSACTIONS", "field-label"));
                for (CustomerLedgerEntry le : ledgerEntries) {
                    historyCard.getChildren().add(Ui.muted(le.entryType() + "  ·  " + Money.fmt(le.amount())
                            + "  ·  Bal After: " + Money.fmt(le.balanceAfter()) + "  ·  " + Ui.dateTime(le.createdAt())));
                }
            }
        }

        List<PrintJob> jobs = ctx.systemRepo().printJobs(order.id());
        historyCard.getChildren().add(Ui.label("PRINTING STATUS", "field-label"));
        if (jobs.isEmpty()) {
            historyCard.getChildren().add(Ui.muted("No print jobs recorded."));
        } else {
            for (PrintJob pj : jobs) {
                historyCard.getChildren().add(Ui.muted(pj.kind() + "  ·  " + pj.status()
                        + (pj.reprint() ? " (Reprint)" : "")
                        + "  ·  " + pj.printer()
                        + "  ·  " + Ui.dateTime(pj.createdAt())));
            }
        }

        VBox scrollBody = new VBox(12, topBar, actions, topCards, itemsCard, historyCard);
        scrollBody.setPadding(new Insets(2, 6, 12, 2));
        ScrollPane scroll = new ScrollPane(scrollBody);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        detailPage.getChildren().setAll(scroll);
        contentStack.getChildren().setAll(detailPage);
    }

    private HBox buildDraftItemRow(DraftItemEdit item, boolean editable,
                                   Runnable onInc, Runnable onDec, Runnable onRemove) {
        String imgPath = null;
        if (item.productId() != null) {
            imgPath = productImageCache.computeIfAbsent(item.productId(), pid -> {
                Product p = ctx.menu().product(pid);
                return p == null || p.imagePath() == null ? "" : p.imagePath();
            });
        }

        StackPane thumb = new StackPane();
        thumb.setPrefSize(48, 48);
        thumb.setMinSize(48, 48);
        thumb.setMaxSize(48, 48);
        thumb.setStyle("-fx-background-color: #F4EFEA; -fx-background-radius: 8;");
        Image img = imgPath != null && !imgPath.isBlank() ? Images.getCachedImage(imgPath, 96, 96) : null;
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setFitWidth(48);
            iv.setFitHeight(48);
            iv.setPreserveRatio(false);
            thumb.getChildren().add(iv);
        } else {
            thumb.getChildren().add(Ui.icon("fas-utensils", 18));
        }

        VBox text = new VBox(2);
        text.getChildren().add(Ui.label(item.qty() + " × " + item.name(), "h3"));
        if (item.addons() != null && !item.addons().isBlank()) {
            text.getChildren().add(Ui.muted("Options: " + item.addons()));
        }
        if (item.instructions() != null && !item.instructions().isBlank()) {
            text.getChildren().add(Ui.muted("Instructions: " + item.instructions()));
        }
        text.getChildren().add(Ui.muted("Unit: " + Money.fmt(item.unitPrice())));
        HBox.setHgrow(text, Priority.ALWAYS);

        Label lineTotal = Ui.label(Money.fmt(item.lineTotal()), "h3");
        HBox row = new HBox(10, thumb, text);
        if (editable) {
            Button minus = Ui.ghost("-", null, onDec);
            minus.setTooltip(new Tooltip("Decrease quantity"));
            Label qtyLbl = Ui.label(String.valueOf(item.qty()), "h3");
            qtyLbl.setMinWidth(24);
            qtyLbl.setAlignment(Pos.CENTER);
            Button plus = Ui.ghost("+", null, onInc);
            plus.setTooltip(new Tooltip("Increase quantity"));
            Button del = Ui.ghost("", "fas-trash-alt", onRemove);
            del.setTooltip(new Tooltip("Remove item from draft"));
            row.getChildren().addAll(minus, qtyLbl, plus, lineTotal, del);
        } else {
            row.getChildren().add(lineTotal);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-background-color: #FAF8F5; -fx-background-radius: 8; -fx-border-color: #EDE6DE; -fx-border-radius: 8;");
        return row;
    }

    private void printPaidCustomerReceipt(Order order, boolean reprint) {
        if (order.status() == OrderStatus.CANCELLED) { warn("Cancelled orders cannot be printed."); return; }
        ctx.printer().printCustomerReceipt(order, reprint, ctx.session().userId(), result ->
                Platform.runLater(() -> {
                    nav.showToast(result.message(), result.success() ? "SUCCESS" : "WARNING");
                    if (activeDetailOrderId != null && activeDetailOrderId == order.id()) {
                        openOrderCard(order.id());
                    }
                }));
    }

    private void addFact(GridPane g, int r, String k, String v) {
        g.add(Ui.muted(k + ":"), 0, r);
        Label val = Ui.label(v == null ? "—" : v, "field-value");
        val.setWrapText(true);
        g.add(val, 1, r);
    }

    private void addTotal(GridPane g, int r, String k, BigDecimal v) {
        g.add(Ui.muted(k + ":"), 0, r);
        g.add(Ui.label(Money.fmt(v), "h3"), 1, r);
    }

    private void openPdf(Order order) {
        if (order == null) return;
        try {
            var items = ctx.orders().items(order.id());
            var payments = ctx.payments().payments(order.id());
            Path pdfPath = AppPaths.receipts().resolve("receipt_" + order.orderNo() + ".pdf");
            ReceiptPdfGenerator.generateCustomerPdf(order, items, payments, order.orderNo(), ctx.settings(), pdfPath);
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(pdfPath.toFile());
            } else {
                toast("80mm Receipt PDF created at " + pdfPath.getFileName());
            }
        } catch (Exception ex) {
            Ui.error(nav.window(), "PDF Generation Failed", ex.getMessage());
        }
    }

    private void cancel(Order order) {
        String reason = Ui.prompt(nav.window(), "Cancel Order " + order.orderNo(), "A cancellation reason is required.", "Reason", "");
        if (reason == null || reason.isBlank()) return;
        if (!Ui.confirm(nav.window(), "Confirm Cancellation", "Cancel order " + order.orderNo() + "?\nReason: " + reason)) return;
        try {
            ctx.orders().cancel(order.id(), reason, ctx.session());
            toast("Order cancelled.");
            if (activeDetailOrderId != null) closeOrderCard();
            else refresh();
        } catch (Exception e) {
            Ui.error(nav.window(), e);
        }
    }

    private void refund(Payment p) {
        String amt = Ui.prompt(nav.window(), "Process Refund", "Refund amount (up to " + Money.fmt(p.refundable()) + ")", "Amount", Money.plain(p.refundable()));
        if (amt == null) return;
        String reason = Ui.prompt(nav.window(), "Refund Reason", "Explain why the payment is being refunded", "Reason", "");
        if (reason == null || reason.isBlank()) return;
        try {
            ctx.payments().refund(p.id(), Money.of(amt), reason, ctx.session());
            toast("Refund processed.");
            if (activeDetailOrderId != null) openOrderCard(activeDetailOrderId);
            else refresh();
        } catch (Exception e) {
            Ui.error(nav.window(), e);
        }
    }

    private void exportExcel() {
        export(ReportExporter.Format.EXCEL);
    }

    private void exportPdf() {
        export(ReportExporter.Format.PDF);
    }

    private void export(ReportExporter.Format format) {
        Task<Path> task = new Task<>() {
            @Override protected Path call() throws Exception {
                List<Order> orders = allFilteredOrders;
                List<ReportData.Column> columns = List.of(
                        new ReportData.Column("Order No", ReportData.ColType.TEXT, 20),
                        new ReportData.Column("Date/Time", ReportData.ColType.DATETIME, 22),
                        new ReportData.Column("Type", ReportData.ColType.TEXT, 12),
                        new ReportData.Column("Table", ReportData.ColType.TEXT, 8),
                        new ReportData.Column("Customer", ReportData.ColType.TEXT, 20),
                        new ReportData.Column("Cashier", ReportData.ColType.TEXT, 18),
                        new ReportData.Column("Subtotal", ReportData.ColType.CURRENCY, 12),
                        new ReportData.Column("Discount", ReportData.ColType.CURRENCY, 12),
                        new ReportData.Column("Tax", ReportData.ColType.CURRENCY, 10),
                        new ReportData.Column("Total", ReportData.ColType.CURRENCY, 12),
                        new ReportData.Column("Paid", ReportData.ColType.CURRENCY, 12),
                        new ReportData.Column("Status", ReportData.ColType.TEXT, 12),
                        new ReportData.Column("Payment", ReportData.ColType.TEXT, 12)
                );

                List<Object[]> rows = new ArrayList<>();
                for (Order o : orders) {
                    rows.add(new Object[]{
                            o.orderNo(),
                            o.createdAt(),
                            o.type().label(),
                            o.tableNo() == null ? "—" : o.tableNo(),
                            o.customerName() == null ? "" : o.customerName(),
                            o.cashierName(),
                            o.subtotal(),
                            o.discountTotal(),
                            o.taxTotal(),
                            o.total(),
                            o.paidTotal(),
                            o.status().label(),
                            o.paymentStatus().label()
                    });
                }

                String period = "All dates";
                if (from.getValue() != null && to.getValue() != null) {
                    period = from.getValue() + " to " + to.getValue();
                }

                ReportData data = new ReportData("orders-export", "Orders Export", period, columns, rows);
                return ctx.exporter().export(data, format, ctx.session());
            }
        };

        exportProgress.setVisible(true); exportProgress.setManaged(true);
        task.setOnSucceeded(e -> {
            exportProgress.setVisible(false); exportProgress.setManaged(false);
            Path p = task.getValue();
            toast("Export complete. Saved to " + p.getFileName());
        });
        task.setOnFailed(e -> {
            exportProgress.setVisible(false); exportProgress.setManaged(false);
            Ui.error(nav.window(), task.getException());
        });

        Thread t = new Thread(task, "export-orders");
        t.setDaemon(true);
        t.start();
    }
}
