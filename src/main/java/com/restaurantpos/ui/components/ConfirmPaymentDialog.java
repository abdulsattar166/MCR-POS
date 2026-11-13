package com.restaurantpos.ui.components;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.service.PaymentService.CheckoutResult;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.ui.UiState;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

public final class ConfirmPaymentDialog extends Dialog<CheckoutResult> {
    private final AppContext ctx;
    private final Order order;
    private final List<OrderItem> items;
    private final BigDecimal balanceDue;
    private final ComboBox<PaymentMethod> methodBox = new ComboBox<>();
    private final TextField amountField = Ui.textField("Amount to collect (PKR)");
    private final TextField tenderedField = Ui.textField("Cash received (PKR)");
    private final TextField refField = Ui.textField("Transaction reference (required for digital)");
    private final Label changeLabel = Ui.label("PKR 0.00", "h2");
    private final Label errorLabel = Ui.label("", "inline-error");
    private final Label customerInfoLabel = Ui.muted("No customer selected");
    private final Label customerBalanceLabel = Ui.label("Balance: PKR 0.00", "h3");
    private Customer selectedCustomer;
    private final Button confirmBtn;
    private boolean processing = false;

    public ConfirmPaymentDialog(AppContext ctx, Order order, Window owner, Consumer<CheckoutResult> onPaid) {
        this(ctx, order, owner, onPaid, null);
    }

    public ConfirmPaymentDialog(AppContext ctx, Order order, Window owner, Consumer<CheckoutResult> onPaid, Consumer<Long> onOpenLedger) {
        this.ctx = ctx;
        this.order = order;
        this.items = ctx.orders().items(order.id());
        this.balanceDue = Money.maxZero(order.balance());
        if (order.customerId() != null) {
            this.selectedCustomer = ctx.customerLedger().findCustomer(order.customerId());
        }
        initOwner(owner);
        setTitle("Confirm Payment — Order " + order.orderNo());
        setHeaderText(null);
        setResizable(true);

        // Header
        VBox headerBox = new VBox(4);
        Button previewBillBtn = Ui.secondary("Preview Bill", "fas-receipt", () -> {
            new ReceiptPreviewDialog(ctx, order, getDialogPane().getScene().getWindow(), null, null).showAndWait();
        });
        HBox topRow = new HBox(12,
                Ui.label("Order " + order.orderNo(), "h2"),
                Ui.chip(order.type().label(), "primary"),
                order.tableNo() == null ? new Label() : Ui.chip("Table " + order.tableNo(), "accent"),
                Ui.spacer(),
                previewBillBtn
        );
        topRow.setAlignment(Pos.CENTER_LEFT);
        Label timeLabel = Ui.muted("Placed: " + Ui.dateTime(order.createdAt()) + "  ·  Cashier: " + order.cashierName());
        headerBox.getChildren().addAll(topRow, timeLabel);

        // Itemized Table
        TableView<OrderItem> itemsTable = Ui.table();
        itemsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);

        TableColumn<OrderItem, String> nameCol = new TableColumn<>("Product Name");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        nameCol.setPrefWidth(185);

        TableColumn<OrderItem, String> optCol = new TableColumn<>("Variant / Add-ons");
        optCol.setCellValueFactory(c -> {
            String a = c.getValue().addons();
            String inst = c.getValue().instructions();
            String text = "";
            if (a != null && !a.isBlank()) text += a;
            if (inst != null && !inst.isBlank()) text += (text.isEmpty() ? "" : " | ") + inst;
            return new javafx.beans.property.SimpleStringProperty(text.isBlank() ? "—" : text);
        });
        optCol.setPrefWidth(140);

        TableColumn<OrderItem, String> qtyCol = new TableColumn<>("Qty");
        qtyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().qty())));
        qtyCol.setPrefWidth(55);
        qtyCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<OrderItem, String> priceCol = new TableColumn<>("Unit Price (PKR)");
        priceCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(Money.fmt(c.getValue().unitPrice())));
        priceCol.setPrefWidth(110);
        priceCol.setStyle("-fx-alignment: CENTER_RIGHT;");

        TableColumn<OrderItem, String> totalCol = new TableColumn<>("Line Total (PKR)");
        totalCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(Money.fmt(c.getValue().lineTotal())));
        totalCol.setPrefWidth(115);
        totalCol.setStyle("-fx-alignment: CENTER_RIGHT; -fx-font-weight: bold;");

        itemsTable.getColumns().addAll(nameCol, optCol, qtyCol, priceCol, totalCol);
        itemsTable.getItems().setAll(items);
        itemsTable.setFixedCellSize(34);
        double tableHeight = Math.min(220, Math.max(95, items.size() * 34 + 36));
        itemsTable.setPrefHeight(tableHeight);
        itemsTable.setMinHeight(tableHeight);

        // Financial totals
        GridPane totalGrid = new GridPane();
        totalGrid.setHgap(16); totalGrid.setVgap(4);
        int r = 0;
        totalGrid.add(Ui.muted("Subtotal:"), 0, r); totalGrid.add(Ui.label(Money.fmt(order.subtotal()), "h3"), 1, r++);
        if (Money.nz(order.discountTotal()).signum() > 0) {
            totalGrid.add(Ui.muted("Discounts:"), 0, r); totalGrid.add(Ui.label("-" + Money.fmt(order.discountTotal()), "h3"), 1, r++);
        }
        if (Money.nz(order.taxTotal()).signum() > 0) {
            totalGrid.add(Ui.muted("Tax:"), 0, r); totalGrid.add(Ui.label(Money.fmt(order.taxTotal()), "h3"), 1, r++);
        }
        if (Money.nz(order.deliveryCharge()).signum() > 0) {
            totalGrid.add(Ui.muted("Delivery:"), 0, r); totalGrid.add(Ui.label(Money.fmt(order.deliveryCharge()), "h3"), 1, r++);
        }
        totalGrid.add(Ui.muted("Grand Total:"), 0, r); totalGrid.add(Ui.label(Money.fmt(order.total()), "h3"), 1, r++);
        if (Money.nz(order.paidTotal()).signum() > 0) {
            totalGrid.add(Ui.muted("Previously Paid:"), 0, r); totalGrid.add(Ui.label(Money.fmt(order.paidTotal()), "h3"), 1, r++);
        }
        Label balanceVal = Ui.label(Money.fmt(balanceDue), "total-amount");
        balanceVal.setStyle("-fx-font-size: 18px; -fx-text-fill: -pos-accent-dark;");
        totalGrid.add(Ui.label("BALANCE DUE:", "field-label"), 0, r); totalGrid.add(balanceVal, 1, r++);

        // Payment inputs
        methodBox.getItems().setAll(
                PaymentMethod.CASH,
                PaymentMethod.CARD,
                PaymentMethod.EASYPAISA,
                PaymentMethod.JAZZCASH,
                PaymentMethod.BANK_TRANSFER,
                PaymentMethod.CREDIT
        );
        methodBox.setValue(PaymentMethod.CASH);
        methodBox.setMaxWidth(Double.MAX_VALUE);
        methodBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(PaymentMethod m) { return m == null ? "" : m.label(); }
            @Override public PaymentMethod fromString(String s) { return null; }
        });

        amountField.setText(Money.plain(balanceDue));
        tenderedField.setText(Money.plain(balanceDue));

        HBox quickCash = new HBox(6);
        quickCash.setAlignment(Pos.CENTER_LEFT);
        quickCash.getChildren().add(Ui.muted("Quick cash:"));
        for (String amt : new String[]{"Exact", "500", "1000", "2000", "5000"}) {
            Button b = new Button(amt);
            b.getStyleClass().add("table-toggle");
            b.setOnAction(e -> {
                if (methodBox.getValue() != PaymentMethod.CASH) return;
                tenderedField.setText("Exact".equals(amt) ? Money.plain(balanceDue) : amt);
                updateChange();
            });
            quickCash.getChildren().add(b);
        }

        HBox changeRow = new HBox(12, Ui.label("CHANGE DUE:", "field-label"), changeLabel);
        changeRow.setAlignment(Pos.CENTER_LEFT);
        changeRow.setStyle("-fx-background-color: -pos-surface-alt; -fx-padding: 8 12; -fx-background-radius: 8;");

        VBox ledgerCard = new VBox(8);
        ledgerCard.setStyle("-fx-background-color: -pos-surface-alt; -fx-padding: 10 12; -fx-background-radius: 8; -fx-border-color: -pos-border; -fx-border-radius: 8;");
        Button pickCustomerBtn = Ui.secondary("Select / Add Customer", "fas-user-plus", () -> {
            CustomerPickerDialog picker = new CustomerPickerDialog(ctx, getDialogPane().getScene().getWindow(),
                    "Select Customer for Order " + order.orderNo(), null);
            picker.showAndWait().ifPresent(c -> {
                selectedCustomer = c;
                updateCustomerBanner();
            });
        });
        Button openLedgerBtn = Ui.ghost("Open Customer Ledger", "fas-book", () -> {
            if (selectedCustomer != null) {
                UiState.pendingCustomerId = selectedCustomer.id();
                if (onOpenLedger != null) {
                    close();
                    onOpenLedger.accept(selectedCustomer.id());
                } else {
                    new Alert(Alert.AlertType.INFORMATION,
                            selectedCustomer.name() + "\nOutstanding Ledger Balance: "
                                    + Money.fmt(ctx.customerLedger().balance(selectedCustomer.id()))).showAndWait();
                }
            } else {
                Ui.error(getDialogPane().getScene().getWindow(), "Select Customer", "Select a customer first to open their ledger.");
            }
        });
        HBox custActions = new HBox(8, pickCustomerBtn, openLedgerBtn, Ui.spacer(), customerBalanceLabel);
        custActions.setAlignment(Pos.CENTER_LEFT);
        ledgerCard.getChildren().addAll(
                Ui.label("CUSTOMER & LEDGER ACCOUNT", "field-label"),
                customerInfoLabel,
                custActions
        );
        updateCustomerBanner();

        methodBox.valueProperty().addListener((o, a, b) -> {
            boolean isCash = b == PaymentMethod.CASH;
            boolean isCredit = b == PaymentMethod.CREDIT;
            tenderedField.setDisable(!isCash);
            quickCash.setVisible(isCash);
            quickCash.setManaged(isCash);
            changeRow.setVisible(isCash);
            changeRow.setManaged(isCash);
            refField.setPromptText(isCredit ? "Credit note (optional)" : (isCash ? "Optional note" : "Approval code / reference number (required)"));
            if (isCash) {
                tenderedField.setText(Money.plain(readAmount()));
            }
            updateChange();
        });

        amountField.textProperty().addListener((o, a, b) -> updateChange());
        tenderedField.textProperty().addListener((o, a, b) -> updateChange());

        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        VBox form = new VBox(10,
                ledgerCard,
                Ui.field("Payment method", methodBox),
                Ui.field("Amount to collect / charge (PKR)", amountField),
                Ui.field("Cash received from customer (PKR)", tenderedField),
                quickCash,
                changeRow,
                Ui.field("Reference / Note", refField),
                errorLabel
        );

        VBox mainContent = new VBox(14,
                headerBox,
                new Separator(),
                Ui.label("ITEMIZED BILL", "section-label"),
                itemsTable,
                new Separator(),
                totalGrid,
                new Separator(),
                Ui.label("RECORD PAYMENT", "section-label"),
                form
        );
        mainContent.setPadding(new Insets(12, 14, 12, 14));
        mainContent.setPrefWidth(630);
        mainContent.setMinWidth(560);

        ScrollPane contentScroll = new ScrollPane(mainContent);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(false);
        contentScroll.setPannable(true);
        contentScroll.getStyleClass().add("edge-to-edge");

        var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double screenH = screenBounds != null ? screenBounds.getHeight() : 768;
        double maxDialogHeight = Math.max(380, Math.min(820, screenH - 120));
        contentScroll.setMaxHeight(maxDialogHeight);
        contentScroll.setPrefHeight(Math.min(maxDialogHeight, 620));

        getDialogPane().setContent(contentScroll);

        ButtonType payType = new ButtonType("Confirm Completed Order", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, payType);
        if (getDialogPane().getScene() != null) Ui.applyStyles(getDialogPane().getScene());
        else setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));

        confirmBtn = (Button) getDialogPane().lookupButton(payType);
        confirmBtn.getStyleClass().add("primary-button");

        confirmBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (processing) {
                ev.consume();
                return;
            }
            try {
                BigDecimal collectAmount = readAmount();
                if (collectAmount.signum() <= 0) throw new IllegalArgumentException("Amount must be greater than zero.");
                if (collectAmount.compareTo(balanceDue) > 0) {
                    throw new IllegalArgumentException("Amount cannot exceed the balance due of " + Money.fmt(balanceDue) + ".");
                }
                PaymentMethod m = methodBox.getValue();
                if (m == null) throw new IllegalArgumentException("Select a payment method.");
                BigDecimal tendered = collectAmount;
                if (m == PaymentMethod.CASH) {
                    tendered = readTendered();
                    if (tendered.compareTo(collectAmount) < 0) {
                        throw new IllegalArgumentException("Cash received is less than the amount to collect.");
                    }
                } else if (m == PaymentMethod.EASYPAISA || m == PaymentMethod.JAZZCASH || m == PaymentMethod.BANK_TRANSFER) {
                    String ref = refField.getText() == null ? "" : refField.getText().trim();
                    if (ref.isBlank()) {
                        throw new IllegalArgumentException("Transaction reference is required for " + m.label() + ".");
                    }
                }

                // Prevent double clicking
                processing = true;
                confirmBtn.setDisable(true);

                CheckoutResult result;
                if (m == PaymentMethod.CREDIT) {
                    if (selectedCustomer == null) {
                        throw new IllegalArgumentException("Select or create a customer to charge this amount to the Customer Ledger.");
                    }
                    ctx.customerLedger().addOrderToLedger(selectedCustomer.id(), order.id(), collectAmount,
                            refField.getText(), ctx.session());
                    Order updatedOrder = ctx.orders().find(order.id());
                    result = new CheckoutResult(order.id(), order.orderNo(),
                            updatedOrder != null ? updatedOrder.total() : order.total(),
                            updatedOrder != null ? updatedOrder.paidTotal() : order.paidTotal(),
                            Money.ZERO);
                } else {
                    PaymentInput pInput = new PaymentInput(m, collectAmount, tendered,
                            refField.getText() == null ? "" : refField.getText().trim(), "");
                    result = ctx.payments().recordBalancePayment(order.id(), List.of(pInput), ctx.session());
                }
                setResult(result);
                if (onPaid != null) onPaid.accept(result);
            } catch (Exception ex) {
                processing = false;
                confirmBtn.setDisable(false);
                errorLabel.setText(ex.getMessage());
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                ev.consume();
            }
        });

        updateChange();
        setOnShown(e -> {
            Ui.applyStyles(getDialogPane().getScene());
            Animations.pop(getDialogPane());
            tenderedField.requestFocus();
            tenderedField.selectAll();
        });
    }

    private BigDecimal readAmount() {
        try { return Money.of(amountField.getText()); }
        catch (Exception e) { return balanceDue; }
    }

    private BigDecimal readTendered() {
        try { return Money.of(tenderedField.getText()); }
        catch (Exception e) { return readAmount(); }
    }

    private void updateChange() {
        if (methodBox.getValue() != PaymentMethod.CASH) {
            changeLabel.setText("PKR 0.00");
            return;
        }
        BigDecimal applied = readAmount();
        BigDecimal received = readTendered();
        BigDecimal change = Money.maxZero(received.subtract(applied));
        changeLabel.setText(Money.fmt(change));
    }

    private void updateCustomerBanner() {
        if (selectedCustomer == null) {
            customerInfoLabel.setText(order.customerName() != null && !order.customerName().isBlank()
                    ? "Order customer: " + order.customerName() + " (Select customer profile for ledger)"
                    : "No customer selected (Required only for Ledger / Credit charges)");
            customerBalanceLabel.setText("Balance: PKR 0.00");
        } else {
            BigDecimal bal = ctx.customerLedger().balance(selectedCustomer.id());
            customerInfoLabel.setText(selectedCustomer.name()
                    + (selectedCustomer.phone() == null || selectedCustomer.phone().isBlank() ? "" : "  ·  " + selectedCustomer.phone()));
            customerBalanceLabel.setText("Ledger Balance: " + Money.fmt(bal));
        }
    }
}
