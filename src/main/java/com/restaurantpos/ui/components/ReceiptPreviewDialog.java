package com.restaurantpos.ui.components;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.printing.ReceiptLogo;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.ui.animation.Animations;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.List;

public final class ReceiptPreviewDialog extends Dialog<Void> {
    private final AppContext ctx;
    private final Order order;
    private final Label statusBanner = new Label();
    private final ComboBox<String> printerSelector = new ComboBox<>();
    private final TextArea area;
    private final Button printBtn;
    private final Button reprintBtn;
    private final Button openPdfBtn;
    private java.nio.file.Path lastPdfPath;

    public ReceiptPreviewDialog(AppContext ctx, Order order, Window owner, Runnable onDrafts, Runnable onNewOrder) {
        this.ctx = ctx;
        this.order = order;
        initOwner(owner);
        setTitle("Receipt Preview — Order " + order.orderNo());
        setHeaderText(null);
        setResizable(true);

        boolean isPaid = order.paymentStatus() == com.restaurantpos.enums.PaymentStatus.PAID;
        boolean isOnAccount = order.paymentStatus() == com.restaurantpos.enums.PaymentStatus.ON_ACCOUNT;

        if (isPaid) {
            statusBanner.setText("PAID — PAYMENT CONFIRMED\nOrder payment completed.");
            statusBanner.setStyle("-fx-background-color: -pos-green-soft; -fx-text-fill: #267047; -fx-font-weight: bold; -fx-padding: 10 14; -fx-background-radius: 8;");
        } else if (isOnAccount) {
            statusBanner.setText("ON ACCOUNT — CHARGED TO CUSTOMER LEDGER\nBilled to customer ledger. Cash payment is collected via Customer Ledger.");
            statusBanner.setStyle("-fx-background-color: rgba(61,111,168,0.16); -fx-text-fill: #2c5282; -fx-font-weight: bold; -fx-padding: 10 14; -fx-background-radius: 8;");
        } else {
            statusBanner.setText("UNPAID — PAYMENT PENDING\nOrder placed as Draft. Please collect payment at counter.");
            statusBanner.setStyle("-fx-background-color: -pos-amber-soft; -fx-text-fill: #925805; -fx-font-weight: bold; -fx-padding: 10 14; -fx-background-radius: 8;");
        }
        statusBanner.setMaxWidth(Double.MAX_VALUE);
        statusBanner.setAlignment(Pos.CENTER);

        String receiptText = ctx.printer().previewCustomer(order);
        area = new TextArea(receiptText);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("receipt-paper");
        area.setPrefRowCount(14);
        area.setMinHeight(150);
        area.setPrefColumnCount(48);

        ImageView logoView = new ImageView(ReceiptLogo.fxCircularLogo(ctx.settings(), 74));
        logoView.setFitWidth(74);
        logoView.setFitHeight(74);
        logoView.setPreserveRatio(true);
        logoView.setSmooth(true);
        HBox logoBox = new HBox(logoView);
        logoBox.setAlignment(Pos.CENTER);
        logoBox.setPadding(new Insets(12, 8, 4, 8));
        logoBox.setStyle("-fx-background-color: #fdfbf7; -fx-border-color: #ded7cb; -fx-border-width: 1 1 0 1; -fx-background-radius: 6 6 0 0; -fx-border-radius: 6 6 0 0;");

        VBox receiptContainer = new VBox(0, logoBox, area);
        VBox.setVgrow(area, Priority.ALWAYS);

        // Printer Selector setup
        printerSelector.getItems().add("Auto-detect 80mm Thermal Printer");
        printerSelector.getItems().add("Windows Print Dialog (Prompt on Print)");
        printerSelector.getItems().add("Save 80mm PDF Only");
        List<String> detected = ctx.printer().availablePrinters();
        for (String p : detected) {
            if (!printerSelector.getItems().contains(p)) {
                printerSelector.getItems().add(p);
            }
        }
        String configured = ctx.systemRepo().printerForRole("CASHIER");
        if (configured != null && !configured.isBlank() && printerSelector.getItems().contains(configured)) {
            printerSelector.setValue(configured);
        } else {
            printerSelector.setValue("Auto-detect 80mm Thermal Printer");
        }
        printerSelector.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(printerSelector, Priority.ALWAYS);

        HBox printerRow = new HBox(10, Ui.label("Destination:", "field-label"), printerSelector);
        printerRow.setAlignment(Pos.CENTER_LEFT);

        printBtn = Ui.primary(isPaid ? "Print Receipt" : "Print Bill", "fas-print", () -> doPrint(false));
        printBtn.setMinWidth(Region.USE_PREF_SIZE);
        printBtn.setMinHeight(36);
        reprintBtn = Ui.secondary("Reprint Receipt", "fas-redo", () -> doPrint(true));
        reprintBtn.setMinWidth(Region.USE_PREF_SIZE);
        reprintBtn.setMinHeight(36);
        reprintBtn.setVisible(isPaid);
        reprintBtn.setManaged(isPaid);
        openPdfBtn = Ui.ghost("Open 80mm PDF", "fas-file-pdf", this::openPdf);
        openPdfBtn.setMinWidth(Region.USE_PREF_SIZE);
        openPdfBtn.setMinHeight(36);
        openPdfBtn.setVisible(true);
        openPdfBtn.setManaged(true);
        Button downloadPdfBtn = Ui.ghost("Download PDF", "fas-download", this::downloadPdf);
        downloadPdfBtn.setMinWidth(Region.USE_PREF_SIZE);
        downloadPdfBtn.setMinHeight(36);

        FlowPane btnRow = new FlowPane(10, 8);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.setMinHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(btnRow, Priority.NEVER);
        btnRow.getChildren().addAll(downloadPdfBtn, openPdfBtn, printBtn, reprintBtn);

        if (onDrafts != null) {
            Button draftsBtn = Ui.secondary("Go to Draft Orders", "fas-clipboard-list", () -> {
                close();
                onDrafts.run();
            });
            btnRow.getChildren().add(draftsBtn);
        }

        if (onNewOrder != null) {
            Button newOrderBtn = Ui.button("Start New Order", "fas-plus", "secondary-button", () -> {
                close();
                onNewOrder.run();
            });
            btnRow.getChildren().add(newOrderBtn);
        }

        VBox.setVgrow(receiptContainer, Priority.ALWAYS);
        VBox content = new VBox(10, statusBanner, printerRow, receiptContainer, btnRow);
        content.setPadding(new Insets(12));
        content.setPrefWidth(520);
        var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double dialogHeight = Math.max(400, Math.min(580, screenBounds.getHeight() - 150));
        content.setPrefHeight(dialogHeight);
        getDialogPane().setContent(content);

        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(closeType);

        if (getDialogPane().getScene() != null) Ui.applyStyles(getDialogPane().getScene());
        setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));
    }

    private void doPrint(boolean reprint) {
        printBtn.setDisable(true);
        reprintBtn.setDisable(true);
        try {
            Order fresh = ctx.orders().find(order.id());
            Order useOrder = fresh != null ? fresh : order;
            area.setText(ctx.printer().previewCustomer(useOrder, reprint));

            String selection = printerSelector.getValue();
            if ("Windows Print Dialog (Prompt on Print)".equals(selection)) {
                PrinterJob job = PrinterJob.createPrinterJob();
                if (job == null) {
                    executePrint(useOrder, reprint, "Auto-detect 80mm Thermal Printer");
                    return;
                }
                boolean proceed = job.showPrintDialog(getDialogPane().getScene().getWindow());
                if (!proceed) {
                    printBtn.setDisable(false);
                    reprintBtn.setDisable(false);
                    statusBanner.setText(statusBanner.getText() + "\n⚠ Print cancelled in Windows print dialog.");
                    return;
                }
                String chosenPrinter = job.getPrinter() != null ? job.getPrinter().getName() : null;
                job.cancelJob(); // Release print job lock and dispatch through standardized printing service
                executePrint(useOrder, reprint, chosenPrinter);
                return;
            }

            executePrint(useOrder, reprint, selection);
        } catch (Exception ex) {
            printBtn.setDisable(false);
            reprintBtn.setDisable(false);
            statusBanner.setStyle("-fx-background-color: -pos-amber-soft; -fx-text-fill: #925805; -fx-font-weight: bold; -fx-padding: 10 14; -fx-background-radius: 8;");
            statusBanner.setText("PRINT FEEDBACK\n⚠ " + ex.getMessage());
        }
    }

    private void executePrint(Order useOrder, boolean reprint, String chosenPrinter) {
        Long userId = (ctx.session() != null && ctx.session().active()) ? ctx.session().userId() : 1L;
        statusBanner.setText("Submitting " + (reprint ? "reprint" : "receipt") + " print job...");

        ctx.printer().printCustomerReceipt(useOrder, reprint, userId, chosenPrinter, result ->
                Platform.runLater(() -> {
                    printBtn.setDisable(false);
                    reprintBtn.setDisable(false);
                    if (result.customerPdfPath() != null && java.nio.file.Files.exists(result.customerPdfPath())) {
                        lastPdfPath = result.customerPdfPath();
                        openPdfBtn.setVisible(true);
                        openPdfBtn.setManaged(true);
                    }
                    if (result.success()) {
                        statusBanner.setStyle("-fx-background-color: -pos-green-soft; -fx-text-fill: #267047; -fx-font-weight: bold; -fx-padding: 10 14; -fx-background-radius: 8;");
                        statusBanner.setText((reprint ? "REPRINT SUBMITTED" : "PRINT SUBMITTED") + "\n✓ " + result.message());
                    } else {
                        statusBanner.setStyle("-fx-background-color: -pos-amber-soft; -fx-text-fill: #925805; -fx-font-weight: bold; -fx-padding: 10 14; -fx-background-radius: 8;");
                        statusBanner.setText("PRINT FEEDBACK\n⚠ " + result.message());
                    }
                })
        );
    }

    private void openPdf() {
        if (lastPdfPath == null || !java.nio.file.Files.exists(lastPdfPath)) {
            try {
                Order fresh = ctx.orders().find(order.id());
                Order useOrder = fresh != null ? fresh : order;
                lastPdfPath = AppPaths.receipts().resolve("Receipt_" + useOrder.orderNo() + ".pdf");
                ReceiptPdfGenerator.generateCustomerPdf(useOrder, ctx.orders().items(useOrder.id()),
                        ctx.payments().payments(useOrder.id()), "PREVIEW-" + useOrder.orderNo(), ctx.settings(), lastPdfPath);
            } catch (Exception ignored) {}
        }

        if (lastPdfPath == null || !java.nio.file.Files.exists(lastPdfPath)) return;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(lastPdfPath.toFile());
            }
        } catch (Exception ex) {
            // ignore
        }
    }

    private void downloadPdf() {
        try {
            Order fresh = ctx.orders().find(order.id());
            Order useOrder = fresh != null ? fresh : order;
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Save Receipt PDF");
            fc.setInitialFileName("Receipt_" + useOrder.orderNo() + ".pdf");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
            java.io.File target = fc.showSaveDialog(getDialogPane().getScene().getWindow());
            if (target != null) {
                ReceiptPdfGenerator.generateCustomerPdf(useOrder, ctx.orders().items(useOrder.id()),
                        ctx.payments().payments(useOrder.id()), useOrder.orderNo(), ctx.settings(), target.toPath());
                lastPdfPath = target.toPath();
                statusBanner.setText("PDF SAVED\n✓ Receipt downloaded to " + target.getName());
            }
        } catch (Exception ex) {
            Ui.error(getDialogPane().getScene().getWindow(), "PDF Download Failed", ex.getMessage());
        }
    }
}
