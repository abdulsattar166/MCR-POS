package com.restaurantpos.ui.components;

import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.util.Money;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;

public final class PaymentDialog extends Dialog<List<PaymentInput>> {
    private final BigDecimal total;
    private final boolean allowPartial;
    private final ObservableList<PaymentInput> applied = FXCollections.observableArrayList();
    private final TableView<PaymentInput> table = Ui.table();
    private final ComboBox<PaymentMethod> method = new ComboBox<>();
    private final TextField amountField = Ui.textField("Amount");
    private final TextField tenderedField = Ui.textField("Cash received");
    private final TextField referenceField = Ui.textField("Transaction reference");
    private final Label remainingValue = Ui.label("", "total-amount");
    private final Label changeValue = Ui.label("", "h3");
    private final Label error = Ui.label("", "inline-error");
    private boolean completed;

    public PaymentDialog(BigDecimal total, boolean allowPartial, Window owner) {
        this.total = Money.nz(total);
        this.allowPartial = allowPartial;
        initOwner(owner);
        setTitle("Checkout");
        setHeaderText(null);
        setResizable(true);

        Label totalLabel = Ui.label(Money.fmt(total), "total-amount");
        VBox totalBox = new VBox(1, Ui.label("AMOUNT PAYABLE", "field-label"), totalLabel);
        HBox header = new HBox(16, totalBox, Ui.spacer());
        header.setAlignment(Pos.CENTER_LEFT);

        method.getItems().setAll(PaymentMethod.values());
        method.setValue(PaymentMethod.CASH);
        method.setMaxWidth(Double.MAX_VALUE);
        method.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(PaymentMethod m) { return m == null ? "" : m.label(); }
            @Override public PaymentMethod fromString(String s) { return null; }
        });
        method.valueProperty().addListener((o, a, b) -> {
            boolean cash = b == PaymentMethod.CASH;
            tenderedField.setDisable(!cash);
            referenceField.setPromptText(cash ? "Optional note" : "Reference / approval code");
            if (remaining().signum() > 0) amountField.setText(Money.plain(remaining()));
            if (cash) tenderedField.setText(Money.plain(remaining()));
            updateSummary();
        });

        table.getColumns().addAll(
                Ui.col("Method", i -> i.method().label(), 130),
                Ui.moneyCol("Amount", PaymentInput::amount, 100),
                Ui.col("Reference", i -> i.reference() == null ? "" : i.reference(), 170));
        table.setPrefHeight(130);
        table.setItems(applied);
        table.setPlaceholder(Ui.muted("No payment added yet."));

        amountField.textProperty().addListener((o, a, b) -> updateSummary());
        tenderedField.textProperty().addListener((o, a, b) -> updateSummary());

        HBox methodRow = new HBox(10, Ui.field("Payment method", method), Ui.field("Amount", amountField));
        HBox.setHgrow(methodRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(methodRow.getChildren().get(1), Priority.ALWAYS);
        HBox cashRow = new HBox(10, Ui.field("Cash received", tenderedField), Ui.field("Reference / note", referenceField));
        HBox.setHgrow(cashRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(cashRow.getChildren().get(1), Priority.ALWAYS);

        HBox quick = new HBox(7);
        quick.setAlignment(Pos.CENTER_LEFT);
        quick.getChildren().add(Ui.muted("Quick cash:"));
        for (String value : new String[]{"Exact", "500", "1000", "2000", "5000"}) {
            Button b = new Button(value);
            b.getStyleClass().add("table-toggle");
            b.setOnAction(e -> {
                if (method.getValue() != PaymentMethod.CASH) return;
                amountField.setText(Money.plain(remaining()));
                tenderedField.setText("Exact".equals(value) ? Money.plain(remaining()) : value);
                updateSummary();
            });
            quick.getChildren().add(b);
        }

        Button add = Ui.secondary("Add split payment", "fas-plus", this::addPayment);
        HBox addRow = new HBox(9, add);
        addRow.setAlignment(Pos.CENTER_RIGHT);

        HBox summary = new HBox(20);
        summary.setAlignment(Pos.CENTER_RIGHT);
        summary.getChildren().addAll(
                new VBox(1, Ui.label("REMAINING", "field-label"), remainingValue),
                new VBox(1, Ui.label("CHANGE DUE", "field-label"), changeValue));
        error.setVisible(false); error.setManaged(false);

        Label note = Ui.muted("Digital payments are recorded as manually confirmed. Verify the reference before completing the sale. "
                + "Part-paid orders require an authorized Credit entry.");
        note.setWrapText(true);

        VBox content = new VBox(13, header, new Separator(), methodRow, cashRow, quick, addRow, table, summary, error, note);
        content.setPadding(new Insets(8, 10, 8, 10));
        content.setPrefWidth(590);

        ScrollPane contentScroll = new ScrollPane(content);
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
        ButtonType pay = new ButtonType("Complete payment", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, pay);
        if (getDialogPane().getScene() != null) Ui.applyStyles(getDialogPane().getScene());
        else setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));
        getDialogPane().getStyleClass().add("app-dialog");
        Button payButton = (Button) getDialogPane().lookupButton(pay);
        payButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (remaining().signum() > 0) {
                    PaymentInput p = readInput();
                    if (p != null) applied.add(p);
                }
                if (applied.isEmpty()) throw new IllegalArgumentException("Record at least one payment.");
                BigDecimal paid = applied.stream().map(PaymentInput::amount).reduce(Money.ZERO, BigDecimal::add);
                boolean credit = applied.stream().anyMatch(p -> p.method() == PaymentMethod.CREDIT);
                if (paid.compareTo(total) < 0 && !allowPartial && !credit) {
                    throw new IllegalArgumentException("Payment is short by " + Money.fmt(total.subtract(paid))
                            + ". Add the remaining amount, or use Credit if authorized.");
                }
                completed = true;
                setResult(FXCollections.observableArrayList(applied));
            } catch (Exception ex) {
                error.setText(ex.getMessage());
                error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        setResultConverter(b -> b == pay && completed ? FXCollections.observableArrayList(applied) : null);
        amountField.setText(Money.plain(total));
        tenderedField.setText(Money.plain(total));
        updateSummary();
        setOnShown(e -> { Ui.applyStyles(getDialogPane().getScene()); amountField.requestFocus(); amountField.selectAll(); });
    }

    private void addPayment() {
        try {
            PaymentInput p = readInput();
            if (p != null) {
                applied.add(p);
                error.setVisible(false); error.setManaged(false);
                amountField.setText(remaining().signum() > 0 ? Money.plain(remaining()) : "");
                tenderedField.setText(remaining().signum() > 0 ? Money.plain(remaining()) : "");
                updateSummary();
            }
        } catch (Exception ex) {
            error.setText(ex.getMessage());
            error.setVisible(true); error.setManaged(true);
        }
    }

    private PaymentInput readInput() {
        BigDecimal remaining = remaining();
        if (remaining.signum() <= 0) return null;
        BigDecimal amount;
        try { amount = Money.of(amountField.getText()); }
        catch (Exception e) { throw new IllegalArgumentException("Enter a valid payment amount."); }
        if (amount.signum() <= 0) throw new IllegalArgumentException("Payment amount must be greater than zero.");
        if (amount.compareTo(remaining) > 0) throw new IllegalArgumentException("Payment exceeds the remaining amount of " + Money.fmt(remaining) + ".");
        PaymentMethod m = method.getValue();
        if (m == null) throw new IllegalArgumentException("Select a payment method.");
        BigDecimal tendered = amount;
        if (m == PaymentMethod.CASH) {
            try { tendered = Money.of(tenderedField.getText()); }
            catch (Exception e) { throw new IllegalArgumentException("Enter the cash received."); }
            if (tendered.compareTo(amount) < 0) throw new IllegalArgumentException("Cash received is less than the applied amount.");
        }
        String reference = referenceField.getText() == null ? "" : referenceField.getText().trim();
        if ((m == PaymentMethod.EASYPAISA || m == PaymentMethod.JAZZCASH || m == PaymentMethod.BANK_TRANSFER) && reference.isBlank()) {
            throw new IllegalArgumentException("Enter the " + m.label() + " transaction reference.");
        }
        return new PaymentInput(m, amount, tendered, reference, "");
    }

    private BigDecimal remaining() {
        BigDecimal paid = applied.stream().map(PaymentInput::amount).reduce(Money.ZERO, BigDecimal::add);
        return Money.maxZero(Money.sub(total, paid));
    }

    private void updateSummary() {
        remainingValue.setText(Money.fmt(remaining()));
        BigDecimal change = Money.ZERO;
        for (PaymentInput p : applied) {
            if (p.method() == PaymentMethod.CASH) {
                BigDecimal t = p.tendered() == null ? p.amount() : p.tendered();
                if (t.compareTo(p.amount()) > 0) change = change.add(t.subtract(p.amount()));
            }
        }
        BigDecimal currentAmount = safeAmount();
        if (method.getValue() == PaymentMethod.CASH) {
            BigDecimal t = safeTendered();
            if (t.compareTo(currentAmount) > 0) change = change.add(t.subtract(currentAmount));
        }
        changeValue.setText(Money.fmt(change));
    }

    private BigDecimal safeAmount() {
        try { return Money.of(amountField.getText()); } catch (Exception e) { return Money.ZERO; }
    }

    private BigDecimal safeTendered() {
        try { return Money.of(tenderedField.getText()); } catch (Exception e) { return Money.ZERO; }
    }
}
