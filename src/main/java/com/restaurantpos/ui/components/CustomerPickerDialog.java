package com.restaurantpos.ui.components;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerSummary;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.List;

/**
 * Reusable dialog to search/select an existing customer or create a new customer
 * for customer ledger billing and inspection.
 */
public final class CustomerPickerDialog extends Dialog<Customer> {
    private final AppContext ctx;
    private final TextField searchField = Ui.textField("Search customer name or phone...");
    private final TableView<CustomerLedgerSummary> table = Ui.table();
    private final Label errorLabel = Ui.label("", "inline-error");
    private Customer selectedCustomer;

    public CustomerPickerDialog(AppContext ctx, Window owner, String title, Runnable onOpenLedgerCallback) {
        this.ctx = ctx;
        initOwner(owner);
        setTitle(title != null ? title : "Select Customer for Ledger");
        setHeaderText(null);
        setResizable(true);

        searchField.setPrefWidth(260);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Ui.bindSearch(searchField, this::loadCustomers);

        Button newCustomerBtn = Ui.secondary("New Customer", "fas-user-plus", this::showCreateCustomerDialog);
        HBox topBar = new HBox(10, Ui.icon("fas-search", 13), searchField, newCustomerBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);

        table.getColumns().addAll(
                Ui.col("Customer Name", s -> s.customer().name(), 170),
                Ui.col("Phone", s -> s.customer().phone() == null ? "" : s.customer().phone(), 120),
                Ui.moneyCol("Total Credit", CustomerLedgerSummary::totalCredit, 100),
                Ui.moneyCol("Received", CustomerLedgerSummary::totalReceived, 100),
                Ui.moneyCol("Outstanding Balance", CustomerLedgerSummary::currentBalance, 130)
        );
        table.setPlaceholder(Ui.empty("No customers found", "Click 'New Customer' to add one."));
        table.setPrefHeight(260);
        table.getSelectionModel().selectedItemProperty().addListener((obs, a, b) -> {
            selectedCustomer = b == null ? null : b.customer();
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        });

        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        VBox content = new VBox(12, topBar, table, errorLabel);
        content.setPadding(new Insets(10, 4, 4, 4));
        content.setPrefSize(660, 380);
        getDialogPane().setContent(content);

        ButtonType selectType = new ButtonType("Use Selected Customer", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, selectType);

        if (getDialogPane().getScene() != null) Ui.applyStyles(getDialogPane().getScene());
        else setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));

        Button okBtn = (Button) getDialogPane().lookupButton(selectType);
        okBtn.getStyleClass().add("primary-button");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (selectedCustomer == null) {
                errorLabel.setText("Please select a customer or create a new customer first.");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                ev.consume();
            }
        });
        setResultConverter(b -> b == selectType ? selectedCustomer : null);

        loadCustomers();
    }

    private void loadCustomers() {
        List<CustomerLedgerSummary> list = ctx.customerLedger().summaries(searchField.getText());
        table.getItems().setAll(list);
        if (!list.isEmpty() && table.getSelectionModel().getSelectedItem() == null) {
            table.getSelectionModel().selectFirst();
        }
    }

    private void showCreateCustomerDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(getDialogPane().getScene().getWindow());
        d.setTitle("Add New Customer");
        d.setHeaderText(null);

        TextField name = Ui.textField("Full name (required)");
        TextField phone = Ui.textField("Phone number (optional)");
        TextField address = Ui.textField("Address (optional)");
        TextArea notes = Ui.textArea("Notes (optional)", 2);
        Label err = Ui.label("", "inline-error");
        err.setVisible(false);
        err.setManaged(false);

        VBox box = new VBox(10,
                Ui.field("Customer Name", name),
                Ui.field("Phone Number", phone),
                Ui.field("Address", address),
                Ui.field("Notes", notes),
                err);
        box.setPadding(new Insets(10, 4, 4, 4));
        box.setPrefWidth(420);
        d.getDialogPane().setContent(box);

        ButtonType saveType = new ButtonType("Save Customer", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveType);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));

        long[] createdId = {0};
        Button saveBtn = (Button) d.getDialogPane().lookupButton(saveType);
        saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                createdId[0] = ctx.customerLedger().createCustomer(
                        name.getText(), phone.getText(), address.getText(), "", notes.getText(), ctx.session());
            } catch (Exception ex) {
                err.setText(ex.getMessage());
                err.setVisible(true);
                err.setManaged(true);
                ev.consume();
            }
        });

        if (d.showAndWait().filter(b -> b == saveType).isPresent() && createdId[0] > 0) {
            loadCustomers();
            table.getItems().stream()
                    .filter(s -> s.customer().id() == createdId[0])
                    .findFirst()
                    .ifPresent(s -> {
                        table.getSelectionModel().select(s);
                        selectedCustomer = s.customer();
                    });
        }
    }
}
