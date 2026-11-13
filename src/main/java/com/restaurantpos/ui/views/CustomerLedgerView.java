package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Inventory.CustomerLedgerSummary;
import com.restaurantpos.printing.ReceiptBuilder;
import com.restaurantpos.printing.ReceiptLogo;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.UiState;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Complete Customer Credit Ledger & Account Settlement Workspace.
 */
public final class CustomerLedgerView extends AppView {
    private final TextField search = Ui.textField("Search customer name or phone...");
    private final ResponsiveCardGrid statsGrid = new ResponsiveCardGrid(12, 200, 4);
    private final TableView<CustomerLedgerSummary> customerTable = Ui.table();
    private final TableView<CustomerLedgerEntry> entriesTable = Ui.table();
    private final VBox detailHeader = new VBox(8);
    private CustomerLedgerSummary selectedSummary;

    public CustomerLedgerView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Customer Ledger & Credit Accounts",
                "Manage credit sales on account, inspect customer statements, and collect ledger payments.",
                Ui.primary("New Customer", "fas-user-plus", this::newCustomerDialog),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        search.setPrefWidth(280);
        Ui.bindSearch(search, this::loadCustomers);
        HBox searchBar = new HBox(10, Ui.icon("fas-search", 13), search, Ui.spacer(),
                Ui.muted("Credit entries never count as cash until ledger payment is collected."));
        searchBar.setAlignment(Pos.CENTER_LEFT);

        customerTable.getColumns().addAll(
                Ui.col("Customer", s -> s.customer().name(), 155),
                Ui.col("Phone", s -> s.customer().phone() == null ? "" : s.customer().phone(), 110),
                Ui.moneyCol("Billed Credit", CustomerLedgerSummary::totalCredit, 100),
                Ui.moneyCol("Received", CustomerLedgerSummary::totalReceived, 95),
                Ui.moneyCol("Balance Due", CustomerLedgerSummary::currentBalance, 110)
        );
        customerTable.setPlaceholder(Ui.empty("No customers", "Create a customer to track credit sales and collections."));
        customerTable.getSelectionModel().selectedItemProperty().addListener((obs, a, b) -> showLedger(b));

        VBox leftPane = new VBox(10, searchBar, customerTable);
        leftPane.getStyleClass().add("card");
        leftPane.setPadding(new Insets(12));
        leftPane.setMinWidth(430);
        leftPane.setPrefWidth(540);
        VBox.setVgrow(customerTable, Priority.ALWAYS);

        entriesTable.getColumns().addAll(
                Ui.col("Date & Time", e -> Ui.dateTime(e.createdAt()), 125),
                Ui.col("Type", e -> "CREDIT_SALE".equals(e.entryType()) ? "Credit Charge (+)" : "Payment Received (−)", 130),
                Ui.col("Order #", e -> e.orderNo() == null ? "—" : e.orderNo(), 115),
                Ui.moneyCol("Debit (+)", e -> "CREDIT_SALE".equals(e.entryType()) ? e.amount() : BigDecimal.ZERO, 90),
                Ui.moneyCol("Credit (−)", e -> "PAYMENT".equals(e.entryType()) ? e.amount() : BigDecimal.ZERO, 90),
                Ui.moneyCol("Balance", CustomerLedgerEntry::balanceAfter, 95),
                Ui.col("Method / Ref", e -> {
                    String m = e.paymentMethod() == null ? "" : e.paymentMethod().replace('_', ' ');
                    if (e.reference() != null && !e.reference().isBlank()) m += " (" + e.reference() + ")";
                    return m;
                }, 135),
                Ui.col("Description", e -> e.description() == null ? "" : e.description(), 180),
                Ui.col("Staff", e -> e.staffName() == null ? "" : e.staffName(), 110)
        );
        entriesTable.setPlaceholder(Ui.empty("Select a customer", "Choose a customer on the left to view full ledger history."));
        entriesTable.setRowFactory(tv -> {
            TableRow<CustomerLedgerEntry> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null && row.getItem().orderId() != null) {
                    UiState.pendingOrderId = row.getItem().orderId();
                    nav.navigate("orders");
                }
            });
            return row;
        });

        VBox rightPane = new VBox(12, detailHeader, new Separator(), Ui.label("TRANSACTION HISTORY", "field-label"), entriesTable);
        rightPane.getStyleClass().add("card");
        rightPane.setPadding(new Insets(14));
        HBox.setHgrow(rightPane, Priority.ALWAYS);
        VBox.setVgrow(entriesTable, Priority.ALWAYS);

        HBox split = new HBox(14, leftPane, rightPane);
        VBox.setVgrow(split, Priority.ALWAYS);

        getChildren().addAll(statsGrid, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        refresh();
    }

    @Override
    public void refresh() {
        loadCustomers();
        if (UiState.pendingCustomerId != null) {
            long cid = UiState.pendingCustomerId;
            UiState.pendingCustomerId = null;
            customerTable.getItems().stream()
                    .filter(s -> s.customer().id() == cid)
                    .findFirst()
                    .ifPresent(s -> {
                        customerTable.getSelectionModel().select(s);
                        customerTable.scrollTo(s);
                    });
        }
    }

    private void loadCustomers() {
        Long prevId = selectedSummary == null ? null : selectedSummary.customer().id();
        List<CustomerLedgerSummary> list = ctx.customerLedger().summaries(search.getText());
        customerTable.getItems().setAll(list);

        BigDecimal totalReceivable = Money.ZERO;
        BigDecimal totalCredit = Money.ZERO;
        BigDecimal totalReceived = Money.ZERO;
        int accountsWithBalance = 0;
        for (CustomerLedgerSummary s : list) {
            totalReceivable = totalReceivable.add(s.currentBalance());
            totalCredit = totalCredit.add(s.totalCredit());
            totalReceived = totalReceived.add(s.totalReceived());
            if (s.currentBalance().signum() > 0) accountsWithBalance++;
        }

        statsGrid.setCards(List.of(
                statCard("Outstanding Receivables", Money.fmt(totalReceivable), "fas-hand-holding-usd", "stat-icon-accent"),
                statCard("Accounts With Balance", String.valueOf(accountsWithBalance), "fas-users", "stat-icon-blue"),
                statCard("Total Credit Billed", Money.fmt(totalCredit), "fas-file-invoice-dollar", "stat-icon-amber"),
                statCard("Total Payments Collected", Money.fmt(totalReceived), "fas-money-bill-wave", "stat-icon-green")
        ));

        if (prevId != null) {
            list.stream().filter(s -> s.customer().id() == prevId).findFirst()
                    .ifPresentOrElse(s -> {
                        customerTable.getSelectionModel().select(s);
                        showLedger(s);
                    }, () -> showLedger(list.isEmpty() ? null : list.get(0)));
        } else if (!list.isEmpty()) {
            customerTable.getSelectionModel().selectFirst();
        } else {
            showLedger(null);
        }
    }

    private VBox statCard(String label, String val, String icon, String style) {
        VBox box = new VBox(3);
        box.getStyleClass().add("stat-card");
        box.setMaxWidth(Double.MAX_VALUE);
        Label ico = new Label();
        ico.setGraphic(Ui.icon(icon, 15));
        ico.getStyleClass().addAll("stat-icon", style);
        box.getChildren().addAll(ico, Ui.label(label.toUpperCase(), "stat-label"), Ui.label(val, "h2"));
        return box;
    }

    private void showLedger(CustomerLedgerSummary summary) {
        selectedSummary = summary;
        detailHeader.getChildren().clear();
        if (summary == null) {
            detailHeader.getChildren().add(Ui.empty("No customer selected", "Select a customer to inspect transactions or receive payment."));
            entriesTable.getItems().clear();
            return;
        }

        Customer c = summary.customer();
        Label balVal = Ui.label(Money.fmt(summary.currentBalance()), "total-amount");
        balVal.setStyle("-fx-font-size: 20px; -fx-text-fill: " + (summary.currentBalance().signum() > 0 ? "-pos-accent-dark;" : "#267047;"));

        VBox custInfo = new VBox(2,
                Ui.label(c.name(), "h2"),
                Ui.muted((c.phone() == null || c.phone().isBlank() ? "No phone" : c.phone())
                        + (c.address() == null || c.address().isBlank() ? "" : "  ·  " + c.address())),
                Ui.muted("Total Credit: " + Money.fmt(summary.totalCredit()) + "  ·  Total Received: " + Money.fmt(summary.totalReceived())));

        VBox balBox = new VBox(2, Ui.label("OUTSTANDING BALANCE", "field-label"), balVal);
        balBox.setAlignment(Pos.CENTER_RIGHT);

        HBox topRow = new HBox(12, custInfo, Ui.spacer(), balBox);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button collectBtn = Ui.primary("Receive Payment", "fas-coins", () -> receivePaymentDialog(summary));
        collectBtn.setDisable(summary.currentBalance().signum() <= 0);

        Button printVoucherBtn = Ui.secondary("Print Receipt", "fas-print", () -> {
            CustomerLedgerEntry sel = entriesTable.getSelectionModel().getSelectedItem();
            if (sel == null) {
                warn("Please select a ledger transaction from the history table to print its receipt.");
                return;
            }
            showLedgerPaymentReceipt(c, sel);
        });

        HBox btnRow = new HBox(10, collectBtn, printVoucherBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        detailHeader.getChildren().addAll(topRow, btnRow);
        entriesTable.getItems().setAll(ctx.customerLedger().entries(c.id()));
    }

    private void newCustomerDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Add New Customer");
        d.setHeaderText(null);

        TextField name = Ui.textField("Customer full name");
        TextField phone = Ui.textField("Phone number");
        TextField address = Ui.textField("Address");
        TextArea notes = Ui.textArea("Notes", 2);
        Label err = Ui.label("", "inline-error");
        err.setVisible(false);
        err.setManaged(false);

        VBox box = new VBox(10, Ui.field("Name", name), Ui.field("Phone", phone),
                Ui.field("Address", address), Ui.field("Notes", notes), err);
        box.setPadding(new Insets(10, 4, 4, 4));
        box.setPrefWidth(420);
        d.getDialogPane().setContent(box);

        ButtonType saveType = new ButtonType("Create Customer", ButtonBar.ButtonData.OK_DONE);
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
            UiState.pendingCustomerId = createdId[0];
            refresh();
            toast("Customer created.");
        }
    }

    private void receivePaymentDialog(CustomerLedgerSummary summary) {
        Customer c = summary.customer();
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Receive Ledger Payment — " + c.name());
        d.setHeaderText(null);

        Label dueInfo = Ui.label("Current Outstanding Balance: " + Money.fmt(summary.currentBalance()), "h3");
        TextField amountField = Ui.textField("Amount received (PKR)");
        amountField.setText(Money.plain(summary.currentBalance()));

        ComboBox<PaymentMethod> methodBox = new ComboBox<>();
        methodBox.getItems().setAll(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.EASYPAISA, PaymentMethod.JAZZCASH, PaymentMethod.BANK_TRANSFER);
        methodBox.setValue(PaymentMethod.CASH);
        methodBox.setMaxWidth(Double.MAX_VALUE);
        methodBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(PaymentMethod m) { return m == null ? "" : m.label(); }
            @Override public PaymentMethod fromString(String s) { return null; }
        });

        TextField refField = Ui.textField("Transaction reference (required for digital)");
        TextField noteField = Ui.textField("Payment notes (optional)");
        Label err = Ui.label("", "inline-error");
        err.setVisible(false);
        err.setManaged(false);

        VBox box = new VBox(10, dueInfo,
                Ui.field("Payment Method", methodBox),
                Ui.field("Amount Received (PKR)", amountField),
                Ui.field("Reference Number", refField),
                Ui.field("Note", noteField),
                err);
        box.setPadding(new Insets(10, 4, 4, 4));
        box.setPrefWidth(440);
        d.getDialogPane().setContent(box);

        ButtonType okType = new ButtonType("Confirm Payment", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, okType);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));

        long[] entryId = {0};
        Button okBtn = (Button) d.getDialogPane().lookupButton(okType);
        okBtn.getStyleClass().add("primary-button");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                BigDecimal amt = Money.of(amountField.getText());
                entryId[0] = ctx.customerLedger().collectLedgerPayment(
                        c.id(), amt, methodBox.getValue(), refField.getText(), noteField.getText(), ctx.session());
            } catch (Exception ex) {
                err.setText(ex.getMessage());
                err.setVisible(true);
                err.setManaged(true);
                ev.consume();
            }
        });

        if (d.showAndWait().filter(b -> b == okType).isPresent() && entryId[0] > 0) {
            refresh();
            toast("Ledger payment recorded for " + c.name() + ".");
            ctx.customerLedger().entries(c.id()).stream()
                    .filter(e -> e.id() == entryId[0])
                    .findFirst()
                    .ifPresent(e -> showLedgerPaymentReceipt(c, e));
        }
    }

    private void showLedgerPaymentReceipt(Customer c, CustomerLedgerEntry entry) {
        String text = new ReceiptBuilder(ctx.settings()).ledgerReceipt(c.name(), c.phone(), c.address(), entry, false);
        Dialog<Void> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Customer Ledger Receipt — CLR-" + entry.id() + " (" + c.name() + ")");
        d.setHeaderText(null);
        d.setResizable(true);

        boolean isCredit = "CREDIT_SALE".equalsIgnoreCase(entry.entryType());
        Label statusBanner = new Label(isCredit
                ? "CREDIT CHARGED TO LEDGER — CLR-" + entry.id()
                : "LEDGER PAYMENT CONFIRMED — CLR-" + entry.id());
        statusBanner.setStyle(isCredit
                ? "-fx-background-color: -pos-amber-soft; -fx-text-fill: #925805; -fx-font-weight: bold; -fx-padding: 8 12; -fx-background-radius: 8;"
                : "-fx-background-color: -pos-green-soft; -fx-text-fill: #267047; -fx-font-weight: bold; -fx-padding: 8 12; -fx-background-radius: 8;");
        statusBanner.setMaxWidth(Double.MAX_VALUE);
        statusBanner.setAlignment(Pos.CENTER);

        ComboBox<String> printerBox = new ComboBox<>();
        printerBox.getItems().addAll("Auto-detect 80mm Thermal Printer", "Windows Print Dialog (Prompt on Print)", "Save 80mm PDF Only");
        for (String p : ctx.printer().availablePrinters()) {
            if (!printerBox.getItems().contains(p)) printerBox.getItems().add(p);
        }
        String configured = ctx.systemRepo().printerForRole("CASHIER");
        printerBox.setValue(configured != null && !configured.isBlank() && printerBox.getItems().contains(configured)
                ? configured : "Auto-detect 80mm Thermal Printer");
        printerBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(printerBox, Priority.ALWAYS);
        HBox printerRow = new HBox(10, Ui.label("Destination:", "field-label"), printerBox);
        printerRow.setAlignment(Pos.CENTER_LEFT);

        ImageView logoView = new ImageView(ReceiptLogo.fxCircularLogo(ctx.settings(), 70));
        logoView.setFitWidth(70);
        logoView.setFitHeight(70);
        logoView.setPreserveRatio(true);
        logoView.setSmooth(true);
        HBox logoBox = new HBox(logoView);
        logoBox.setAlignment(Pos.CENTER);
        logoBox.setPadding(new Insets(12, 8, 4, 8));
        logoBox.setStyle("-fx-background-color: #fdfbf7; -fx-border-color: #ded7cb; -fx-border-width: 1 1 0 1; -fx-background-radius: 6 6 0 0; -fx-border-radius: 6 6 0 0;");

        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.getStyleClass().add("receipt-paper");
        area.setPrefRowCount(19);
        area.setPrefColumnCount(48);

        final Path[] lastPdf = {null};
        Button[] printBtns = new Button[2];

        Runnable[] submitPrint = new Runnable[2]; // 0 = original, 1 = reprint
        for (int idx = 0; idx < 2; idx++) {
            final boolean isReprint = idx == 1;
            submitPrint[idx] = () -> {
                printBtns[0].setDisable(true);
                printBtns[1].setDisable(true);
                area.setText(new ReceiptBuilder(ctx.settings()).ledgerReceipt(c.name(), c.phone(), c.address(), entry, isReprint));

                String chosen = printerBox.getValue();
                if ("Windows Print Dialog (Prompt on Print)".equals(chosen)) {
                    javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
                    if (job != null) {
                        boolean ok = job.showPrintDialog(d.getDialogPane().getScene().getWindow());
                        if (!ok) {
                            printBtns[0].setDisable(false);
                            printBtns[1].setDisable(false);
                            statusBanner.setText("⚠ Print cancelled in Windows print dialog.");
                            return;
                        }
                        chosen = job.getPrinter() != null ? job.getPrinter().getName() : null;
                        job.cancelJob();
                    }
                }

                statusBanner.setText("Submitting ledger " + (isReprint ? "reprint" : "receipt") + "...");
                ctx.printer().printLedgerReceipt(c, entry, ctx.session().userId(), chosen, isReprint, res ->
                        javafx.application.Platform.runLater(() -> {
                            printBtns[0].setDisable(false);
                            printBtns[1].setDisable(false);
                            if (res.customerPdfPath() != null) lastPdf[0] = res.customerPdfPath();
                            if (res.success()) {
                                statusBanner.setStyle("-fx-background-color: -pos-green-soft; -fx-text-fill: #267047; -fx-font-weight: bold; -fx-padding: 8 12; -fx-background-radius: 8;");
                                statusBanner.setText("✓ " + res.message());
                                toast(res.message());
                            } else {
                                statusBanner.setStyle("-fx-background-color: -pos-red-soft; -fx-text-fill: #9b1c1c; -fx-font-weight: bold; -fx-padding: 8 12; -fx-background-radius: 8;");
                                statusBanner.setText("⚠ " + res.message());
                                warn(res.message());
                            }
                        })
                );
            };
        }

        printBtns[0] = Ui.primary("Print Receipt", "fas-print", submitPrint[0]);
        printBtns[1] = Ui.secondary("Reprint Receipt", "fas-redo", submitPrint[1]);

        Button openPdf = Ui.ghost("Open 80mm PDF", "fas-file-pdf", () -> {
            try {
                Path p = lastPdf[0] != null && Files.exists(lastPdf[0])
                        ? lastPdf[0]
                        : AppPaths.receipts().resolve("LedgerReceipt_CLR-" + entry.id() + ".pdf");
                ReceiptPdfGenerator.generateLedgerReceiptPdf(c.name(), c.phone(), c.address(), entry, ctx.settings(), p, false);
                lastPdf[0] = p;
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(p.toFile());
                } else {
                    toast("80mm PDF saved to " + p.getFileName());
                }
            } catch (Exception ex) {
                Ui.error(nav.window(), ex);
            }
        });

        Button savePdf = Ui.ghost("Save 80mm PDF", "fas-download", () -> {
            try {
                javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                fc.setTitle("Save Customer Ledger Receipt PDF");
                fc.setInitialFileName("LedgerReceipt_CLR-" + entry.id() + ".pdf");
                fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
                java.io.File target = fc.showSaveDialog(d.getDialogPane().getScene().getWindow());
                if (target != null) {
                    ReceiptPdfGenerator.generateLedgerReceiptPdf(c.name(), c.phone(), c.address(), entry, ctx.settings(), target.toPath(), false);
                    lastPdf[0] = target.toPath();
                    toast("Saved 80mm PDF to " + target.getName());
                }
            } catch (Exception ex) {
                Ui.error(nav.window(), ex);
            }
        });

        VBox receiptBox = new VBox(0, logoBox, area);
        VBox.setVgrow(area, Priority.ALWAYS);
        FlowPane actions = new FlowPane(8, 8, savePdf, openPdf, printBtns[1], printBtns[0]);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(10, statusBanner, printerRow, receiptBox, actions);
        content.setPadding(new Insets(10));
        content.setPrefWidth(520);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        d.showAndWait();
    }
}
