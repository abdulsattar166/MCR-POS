package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.UiState;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Tooltip;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class PaymentsView extends AppView {
    private final DatePicker from = Ui.datePicker(LocalDate.now());
    private final DatePicker to = Ui.datePicker(LocalDate.now());
    private final ComboBox<String> method = new ComboBox<>();
    private final ComboBox<User> cashier = new ComboBox<>();
    private final TextField search = Ui.textField("Order number or reference...");
    private final TableView<Object[]> table = Ui.table();
    private final TableView<Object[]> refundTable = Ui.table();
    private final ResponsiveCardGrid statsGrid = new ResponsiveCardGrid(12, 190, 5);
    private final Label summary = Ui.muted("");

    public PaymentsView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Payments & Refunds", "Every transaction, reference, refund and reconciliation in one register.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        method.getItems().add("All methods");
        for (PaymentMethod m : PaymentMethod.values()) method.getItems().add(m.label());
        method.setValue("All methods");
        cashier.getItems().add(null);
        cashier.getItems().addAll(ctx.users().all());
        cashier.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(User u) { return u == null ? "All cashiers" : u.fullName(); }
            @Override public User fromString(String s) { return null; }
        });
        for (ComboBox<?> c : new ComboBox[]{method, cashier}) c.setPrefWidth(140);
        search.setPrefWidth(200);
        FlowPane filters = new FlowPane(8, 8, from, to, method, cashier, search, summary);
        filters.setAlignment(Pos.CENTER_LEFT);
        from.valueProperty().addListener((o, a, b) -> load());
        to.valueProperty().addListener((o, a, b) -> load());
        method.valueProperty().addListener((o, a, b) -> load());
        cashier.valueProperty().addListener((o, a, b) -> load());
        Ui.bindSearch(search, this::load);

        statsGrid.setPadding(new Insets(0));

        table.getColumns().addAll(
                Ui.col("Time", r -> Ui.dateTime(java.time.LocalDateTime.parse(String.valueOf(r[1]))), 135),
                Ui.col("Order", r -> String.valueOf(r[3]), 145),
                Ui.col("Cashier", r -> String.valueOf(r[4]), 135),
                Ui.col("Method", r -> String.valueOf(r[5]).replace('_', ' '), 115),
                Ui.moneyCol("Amount", r -> (BigDecimal) r[6], 110),
                Ui.moneyCol("Refunded", r -> (BigDecimal) r[7], 100),
                Ui.col("Reference", r -> r[8] == null ? "" : String.valueOf(r[8]), 140),
                Ui.col("Status", r -> String.valueOf(r[9]), 90),
                Ui.col("Shift", r -> r[10] == null ? "—" : "#" + r[10], 60));
        table.setPlaceholder(Ui.empty("No payments", "Transactions appear here as soon as payments are recorded."));
        table.setRowFactory(tv -> {
            TableRow<Object[]> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null) {
                    UiState.pendingOrderId = ((Number) row.getItem()[2]).longValue();
                    nav.navigate("orders");
                }
            });
            return row;
        });
        FlowPane tableActions = new FlowPane(8, 8,
                Ui.secondary("Refund selected payment", "fas-undo-alt", this::refundSelected),
                Ui.ghost("View order", "fas-external-link-alt", () -> {
                    Object[] r = table.getSelectionModel().getSelectedItem();
                    if (r == null) return;
                    UiState.pendingOrderId = ((Number) r[2]).longValue();
                    nav.navigate("orders");
                }),
                Ui.muted("Double-click a row to open its order."));
        tableActions.setAlignment(Pos.CENTER_LEFT);

        refundTable.getColumns().addAll(
                Ui.col("Time", r -> String.valueOf(r[1]), 140),
                Ui.col("Order", r -> String.valueOf(r[2]), 150),
                Ui.col("Method", r -> String.valueOf(r[3]), 120),
                Ui.moneyCol("Amount", r -> (BigDecimal) r[4], 110),
                Ui.col("Reason", r -> String.valueOf(r[5]), 240),
                Ui.col("Approved by", r -> String.valueOf(r[6]), 150),
                Ui.col("Recorded by", r -> String.valueOf(r[7]), 150));
        refundTable.setPlaceholder(Ui.empty("No refunds", "Refunds recorded against completed payments appear here."));

        table.setPrefHeight(460);
        table.setMinHeight(260);
        refundTable.setPrefHeight(460);
        refundTable.setMinHeight(260);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox paymentsTab = new VBox(10, tableActions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox refundsTab = new VBox(10, refundTable);
        VBox.setVgrow(refundTable, Priority.ALWAYS);
        tabs.getTabs().addAll(new Tab("Transactions", paymentsTab), new Tab("Refund register", refundsTab));
        tabs.setMinHeight(340);
        tabs.setPrefHeight(520);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox content = new VBox(14, filters, statsGrid, tabs);
        content.setPadding(new Insets(10, 14, 14, 14));

        ScrollPane pageScroll = new ScrollPane(content);
        pageScroll.setFitToWidth(true);
        pageScroll.setFitToHeight(false);
        pageScroll.setPannable(true);
        pageScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(pageScroll, Priority.ALWAYS);

        pageScroll.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getHeight() > 0) {
                double available = newVal.getHeight() - filters.getHeight() - statsGrid.getHeight() - 40;
                double targetHeight = Math.max(380, available);
                tabs.setPrefHeight(targetHeight);
                table.setPrefHeight(Math.max(260, targetHeight - 55));
                refundTable.setPrefHeight(Math.max(260, targetHeight - 20));
            }
        });

        getChildren().add(pageScroll);
        refresh();
    }

    @Override
    public void refresh() { load(); }

    private void load() {
        String methodValue = method.getSelectionModel().getSelectedIndex() <= 0 ? null
                : PaymentMethod.values()[method.getSelectionModel().getSelectedIndex() - 1].name();
        List<Object[]> rows = ctx.reportRepo().paymentTransactions(from.getValue(), to.getValue(), methodValue,
                cashier.getValue() == null ? null : cashier.getValue().id(), search.getText());
        table.getItems().setAll(rows);
        refundTable.getItems().setAll(ctx.reportRepo().refundTransactions(from.getValue(), to.getValue()));
        BigDecimal total = Money.ZERO, refunded = Money.ZERO, cash = Money.ZERO;
        for (Object[] r : rows) {
            total = total.add((BigDecimal) r[6]);
            refunded = refunded.add((BigDecimal) r[7]);
            if ("CASH".equals(r[5])) cash = cash.add((BigDecimal) r[6]);
        }
        statsGrid.setCards(List.of(
                stat("Collected", Money.fmt(total), "fas-coins", "stat-icon-accent"),
                stat("Cash", Money.fmt(cash), "fas-money-bill-wave", "stat-icon-green"),
                stat("Digital", Money.fmt(Money.sub(total, cash)), "fas-mobile-alt", "stat-icon-blue"),
                stat("Refunded", Money.fmt(refunded), "fas-undo-alt", "stat-icon-red"),
                stat("Net retained", Money.fmt(Money.sub(total, refunded)), "fas-wallet", "stat-icon-green")));
        summary.setText(rows.size() + " transaction(s)");
    }

    private VBox stat(String label, String value, String icon, String style) {
        VBox box = new VBox(3);
        box.getStyleClass().add("stat-card");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setStyle("-fx-min-width: 0; -fx-pref-width: 0;");
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setFocusTraversable(true);
        box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        box.setAccessibleText(label + " card. Activate to view related records.");
        Tooltip.install(box, new Tooltip("View " + label.toLowerCase() + " details"));
        box.setOnMouseClicked(e -> nav.navigate("orders"));
        box.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) { nav.navigate("orders"); e.consume(); } });
        box.setOnMouseEntered(e -> com.restaurantpos.ui.animation.Animations.hoverLift(box));
        box.setOnMouseExited(e -> com.restaurantpos.ui.animation.Animations.hoverReset(box));
        Label ico = new Label();
        ico.setGraphic(Ui.icon(icon, 15));
        ico.getStyleClass().addAll("stat-icon", style);
        box.getChildren().addAll(ico, Ui.label(label.toUpperCase(), "stat-label"), Ui.label(value, "h2"));
        return box;
    }

    private void refundSelected() {
        if (!ctx.canManage()) { warn("Only an owner or manager can approve refunds."); return; }
        Object[] row = table.getSelectionModel().getSelectedItem();
        if (row == null) { warn("Select a payment transaction."); return; }
        long paymentId = ((Number) row[0]).longValue();
        Payment payment = ctx.orderRepo().payment(paymentId);
        if (payment == null) return;
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Refund payment — order " + row[3]);
        d.setHeaderText(null);
        TextField amount = Ui.textField("Refund amount");
        amount.setText(Money.plain(payment.refundable()));
        TextArea reason = Ui.textArea("Refund reason (required)", 2);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, Ui.muted("Original payment " + payment.method().label() + " " + Money.fmt(payment.amount())
                        + "  ·  already refunded " + Money.fmt(payment.refundedAmount())
                        + "  ·  remaining refundable " + Money.fmt(payment.refundable())),
                Ui.field("Amount to refund", amount), Ui.field("Reason", reason), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(440);
        ScrollPane dialogScroll = new ScrollPane(box);
        dialogScroll.setFitToWidth(true);
        dialogScroll.setFitToHeight(false);
        dialogScroll.setPannable(true);
        dialogScroll.getStyleClass().add("edge-to-edge");
        d.getDialogPane().setContent(dialogScroll);
        ButtonType refund = new ButtonType("Record refund", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, refund);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(refund);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.payments().refund(paymentId, Money.of(amount.getText()), reason.getText(), ctx.session());
            } catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == refund).isPresent()) { refresh(); toast("Refund recorded and audited."); }
    }
}
