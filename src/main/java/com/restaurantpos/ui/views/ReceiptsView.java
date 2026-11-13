package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.SystemModels.PrintJob;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

public final class ReceiptsView extends AppView {
    private final ComboBox<String> cashierPrinter = new ComboBox<>();
    private final CheckBox cashierCut = new CheckBox("Send cut command");
    private final TextArea header = Ui.textArea("Receipt header", 4);
    private final TextArea footer = Ui.textArea("Receipt footer", 3);
    private final TableView<PrintJob> history = Ui.table();
    private final TextField previewOrderNo = Ui.textField("Order number");
    private List<String> printers = new ArrayList<>();

    public ReceiptsView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Receipts & Printers", "80 mm thermal receipts, printer assignment and print history.",
                Ui.button("Refresh printers", "fas-sync-alt", "secondary-button", this::reloadPrinters)));
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        reloadPrinters();
        cashierPrinter.setMaxWidth(Double.MAX_VALUE);
        header.setText(ctx.settings().receiptHeader());
        footer.setText(ctx.settings().receiptFooter());
        cashierPrinter.setValue(displayValue(ctx.systemRepo().printerForRole("CASHIER")));
        cashierCut.setSelected(ctx.systemRepo().printerCutForRole("CASHIER"));

        GridPane config = Ui.form(2);
        config.add(Ui.field("Cashier / customer 80mm printer", cashierPrinter), 0, 0, 2, 1);
        config.add(cashierCut, 0, 1);
        Label note = Ui.muted("Standard 80 mm thermal receipts are used throughout the system. "
                + "If a physical thermal printer is connected, ESC/POS print jobs are sent with the official logo. "
                + "If no thermal printer is available or the printer is offline, an 80 mm PDF is automatically generated in the receipts folder.");
        note.setWrapText(true);
        VBox printerCard = Ui.card("Printer assignment", "fas-print", config, note,
                Ui.primary("Save printer settings", "fas-save", this::savePrinters));
        VBox templateCard = Ui.card("Receipt template", "fas-receipt",
                Ui.field("Header lines", header), Ui.field("Footer message", footer),
                Ui.primary("Save template", "fas-save", this::saveTemplate));
        VBox contentTab = new VBox(14, printerCard, templateCard);
        contentTab.setPadding(new Insets(14));
        ScrollPane contentScroll = new ScrollPane(contentTab);
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("edge-to-edge");

        history.getColumns().addAll(
                Ui.col("Time", j -> Ui.dateTime(j.createdAt()), 135),
                Ui.col("Order", PrintJob::orderNo, 145),
                Ui.col("Copy", PrintJob::kind, 90),
                Ui.col("Printer", j -> j.printer() == null ? "—" : j.printer(), 170),
                Ui.col("Status", PrintJob::status, 85),
                Ui.col("User", j -> j.userName() == null ? "—" : j.userName(), 130),
                Ui.col("Reprint", j -> j.reprint() ? "Yes" : "No", 65),
                Ui.col("Error", j -> j.error() == null ? "" : j.error(), 240));
        history.setPlaceholder(Ui.empty("No print jobs", "Receipts printed from the POS appear here."));
        HBox previewBar = new HBox(8, previewOrderNo,
                Ui.secondary("Preview receipt", "fas-eye", () -> {
                    String no = previewOrderNo.getText() == null ? "" : previewOrderNo.getText().trim();
                    var order = ctx.orders().findByNo(no);
                    if (order == null) { warn("Order not found."); return; }
                    nav.previewReceipt(order.id());
                }),
                Ui.button("Refresh", "fas-sync-alt", "ghost-button", this::loadHistory),
                Ui.muted("Preview a receipt without charging or printing."));
        previewBar.setAlignment(Pos.CENTER_LEFT);
        VBox historyTab = new VBox(10, previewBar, history);
        VBox.setVgrow(history, Priority.ALWAYS);
        historyTab.setPadding(new Insets(14));

        tabs.getTabs().addAll(new Tab("Printers & template", contentScroll), new Tab("Print history", historyTab));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        loadHistory();
    }

    private String displayValue(String printer) {
        if (printer == null || printer.isBlank() || "Save to file only".equals(printer) || "Auto-detect 80mm Thermal Printer".equals(printer)) {
            return "Auto-detect 80mm Thermal Printer";
        }
        if ("__DEFAULT__".equals(printer)) return "Windows Default Printer";
        return printer;
    }

    private String storedValue(String display) {
        if (display == null || display.isBlank() || display.equals("Auto-detect 80mm Thermal Printer")) return "";
        if (display.equals("Windows Default Printer")) return "__DEFAULT__";
        return display;
    }

    private void reloadPrinters() {
        printers = new ArrayList<>(ctx.printer().availablePrinters());
        String value = cashierPrinter.getValue() == null ? "Auto-detect 80mm Thermal Printer" : cashierPrinter.getValue();
        cashierPrinter.getItems().setAll("Auto-detect 80mm Thermal Printer", "Windows Default Printer");
        cashierPrinter.getItems().addAll(printers);
        if (!cashierPrinter.getItems().contains(value)) cashierPrinter.getItems().add(value);
        cashierPrinter.setValue(value);
    }

    private void savePrinters() {
        try {
            ctx.systemRepo().savePrinterConfig("Cashier Receipt Printer", "CASHIER",
                    storedValue(cashierPrinter.getValue()), cashierCut.isSelected());
            ctx.audit().log("PRINTER_SETTINGS_SAVED", "settings", null,
                    "Cashier=" + cashierPrinter.getValue());
            toast("Printer settings saved.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void saveTemplate() {
        try {
            ctx.settings().save("receipt.header", header.getText());
            ctx.settings().save("receipt.footer", footer.getText());
            ctx.audit().log("RECEIPT_TEMPLATE_SAVED", "settings", null, "Receipt header/footer updated");
            toast("Receipt template saved.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void loadHistory() {
        history.getItems().setAll(ctx.systemRepo().recentPrintJobs(200));
    }

    @Override
    public void refresh() {
        reloadPrinters();
        loadHistory();
    }
}
