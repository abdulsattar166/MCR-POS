package com.restaurantpos.printing;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.dao.OrderRepository;
import com.restaurantpos.dao.SystemRepository;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.service.AuditService;
import com.restaurantpos.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Centralized Receipt Printing Service.
 * Standardizes on 80 mm thermal layout with circular restaurant logo.
 * Automatically detects physical 80 mm thermal printers via javax.print.
 * Automatically falls back to generating high-fidelity 80 mm PDF receipts in AppPaths.receipts()
 * when physical hardware is absent, disconnected, or offline.
 */
public class ReceiptPrintingService {
    private static final Logger log = LoggerFactory.getLogger(ReceiptPrintingService.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    public static final long DEFAULT_UNPAID_KITCHEN_DELAY_MS = 3000L;

    private final SystemRepository system;
    private final OrderRepository orders;
    private final SettingsService settings;
    private final AuditService audit;
    private final ReceiptBuilder builder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "receipt-printing-service");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "receipt-print-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Long> lastPrint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastKitchenPrint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastAutoOrderPrint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastLedgerPrint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastKitchenUpdatePrint = new ConcurrentHashMap<>();

    public ReceiptPrintingService(SystemRepository system, OrderRepository orders, SettingsService settings, AuditService audit) {
        this.system = system;
        this.orders = orders;
        this.settings = settings;
        this.audit = audit;
        this.builder = new ReceiptBuilder(settings);
    }

    public record PrintResult(
            boolean success,
            boolean printed,
            String message,
            String kitchenText,
            String customerText,
            Path kitchenPdfPath,
            Path customerPdfPath
    ) {
        public PrintResult(boolean success, boolean printed, String message, String kitchenText, String customerText) {
            this(success, printed, message, kitchenText, customerText, null, null);
        }
    }

    public List<String> availablePrinters() {
        try {
            return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                    .map(PrintService::getName).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public record UnpaidOrderPrintResult(
            boolean customerSuccess,
            boolean customerPrinted,
            String customerMessage,
            Path customerPdfPath,
            String customerText,
            boolean kitchenSuccess,
            boolean kitchenPrinted,
            String kitchenMessage,
            Path kitchenPdfPath,
            String kitchenText
    ) {}

    public boolean hasCustomerPrintJob(long orderId) {
        try {
            return system.printJobs(orderId).stream()
                    .anyMatch(j -> "CUSTOMER".equalsIgnoreCase(j.kind()) && "SUCCESS".equalsIgnoreCase(j.status()));
        } catch (Exception e) {
            return false;
        }
    }

    public void printLedgerReceipt(Customer customer, CustomerLedgerEntry entry, Long userId,
                                   String chosenPrinter, boolean reprint, Consumer<PrintResult> callback) {
        if (customer == null || entry == null) {
            if (callback != null) callback.accept(new PrintResult(false, false, "Select a customer and ledger transaction first.", "", ""));
            return;
        }
        long now = System.currentTimeMillis();
        lastLedgerPrint.entrySet().removeIf(e -> now - e.getValue() > 30000);
        Long last = lastLedgerPrint.get(entry.id());
        if (!reprint && last != null && now - last < 4000) {
            if (callback != null) {
                callback.accept(new PrintResult(false, false,
                        "Ledger receipt CLR-" + entry.id() + " was just submitted. Please wait before printing again.",
                        "", "", null, null));
            }
            return;
        }
        lastLedgerPrint.put(entry.id(), now);

        executor.submit(() -> {
            String ts = LocalDateTime.now().format(FILE_TS);
            String sequenceId = (reprint ? "REP-" : "") + "CLR-" + entry.id();
            String configured = (chosenPrinter != null && !chosenPrinter.isBlank())
                    ? chosenPrinter : system.printerForRole("CASHIER");
            boolean cutEnabled = system.printerCutForRole("CASHIER");

            String ledgerText = "";
            Path ledgerPdf = null;
            boolean physicallyPrinted = false;

            try {
                ledgerText = builder.ledgerReceipt(customer.name(), customer.phone(), customer.address(), entry, reprint);
                Path dir = AppPaths.receipts();
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("Ledger_" + sequenceId + "_" + ts + ".txt"), ledgerText, StandardCharsets.UTF_8);

                ledgerPdf = dir.resolve("LedgerReceipt_" + sequenceId + "_" + ts + ".pdf");
                ReceiptPdfGenerator.generateLedgerReceiptPdf(
                        customer.name(), customer.phone(), customer.address(), entry, settings, ledgerPdf, reprint);

                boolean pdfOnly = isPdfOnlyMode(configured);
                boolean explicitHardware = isExplicitHardwarePrinter(configured);
                PrintService printer = pdfOnly ? null : resolveThermalPrinter(configured);

                if (printer != null) {
                    physicallyPrinted = sendEscPos(printer, ledgerText, true, cutEnabled);
                    if (!physicallyPrinted) {
                        throw new RuntimeException("Could not write ledger receipt to printer '" + printer.getName() + "'.");
                    }
                } else if (explicitHardware) {
                    throw new RuntimeException("Configured printer '" + configured + "' is unavailable or disconnected.");
                }

                String message;
                if (physicallyPrinted) {
                    message = (reprint ? "Ledger reprint" : "Ledger receipt") + " submitted to printer '" + printer.getName() + "'.";
                } else if (pdfOnly) {
                    message = "80 mm Ledger receipt PDF saved: " + ledgerPdf.getFileName();
                } else {
                    message = "Thermal printer not detected. 80 mm Ledger receipt PDF generated: " + ledgerPdf.getFileName();
                }

                audit.log("LEDGER_RECEIPT_PRINT", "customer_ledger", entry.id(),
                        sequenceId + " / customer=" + customer.name() + " / " + message);
                if (callback != null) {
                    callback.accept(new PrintResult(true, physicallyPrinted, message, "", ledgerText, null, ledgerPdf));
                }
            } catch (Exception e) {
                log.warn("Ledger receipt print failed for CLR-{}: {}", entry.id(), e.getMessage());
                audit.log("LEDGER_RECEIPT_FAILED", "customer_ledger", entry.id(), sequenceId + " â€” " + e.getMessage());
                if (callback != null) {
                    callback.accept(new PrintResult(false, false,
                            "Ledger receipt printing failed: " + e.getMessage(),
                            "", ledgerText, null, ledgerPdf));
                }
            }
        });
    }

    private static boolean isPdfOnlyMode(String configuredName) {
        return "__PDF_ONLY__".equalsIgnoreCase(configuredName)
                || "Save to file only".equalsIgnoreCase(configuredName)
                || "Save 80mm PDF Only".equalsIgnoreCase(configuredName);
    }

    private static boolean isExplicitHardwarePrinter(String configuredName) {
        return configuredName != null && !configuredName.isBlank()
                && !isPdfOnlyMode(configuredName)
                && !"Auto-detect 80mm Thermal Printer".equalsIgnoreCase(configuredName)
                && !"None".equalsIgnoreCase(configuredName);
    }

    /**
     * Generates and prints the 80 mm customer receipt off the JavaFX Application Thread.
     */
    public void printCustomerReceipt(Order order, boolean reprint, Long userId, Consumer<PrintResult> callback) {
        printCustomerReceipt(order, reprint, userId, null, callback);
    }

    public void printCustomerReceipt(Order order, boolean reprint, Long userId, String chosenCashierPrinter, Consumer<PrintResult> callback) {
        if (order == null) {
            if (callback != null) callback.accept(new PrintResult(false, false, "Order not found.", "", ""));
            return;
        }
        long now = System.currentTimeMillis();
        lastPrint.entrySet().removeIf(e -> now - e.getValue() > 30000);
        String printKey = order.id() + ":" + (order.paymentStatus() != null ? order.paymentStatus().name() : "UNPAID");
        Long last = lastPrint.get(printKey);
        if (!reprint && last != null && now - last < 800) {
            if (callback != null) {
                callback.accept(new PrintResult(false, false,
                        "Receipt printing for " + order.orderNo() + " just started — please wait a moment.", "", ""));
            }
            return;
        }
        lastPrint.put(printKey, now);

        executor.submit(() -> {
            Order fresh = orders.find(order.id());
            if (fresh == null) fresh = order;
            String ts = LocalDateTime.now().format(FILE_TS);
            String seqPrefix = reprint ? "REP-" : "REC-";
            String sequenceId = seqPrefix + fresh.orderNo() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            String cashierConfigured = (chosenCashierPrinter != null && !chosenCashierPrinter.isBlank())
                    ? chosenCashierPrinter : system.printerForRole("CASHIER");
            boolean cashierCut = system.printerCutForRole("CASHIER");
            String customerText = "";
            Path customerPdf = null;
            long jobC = 0;

            try {
                List<OrderItem> items = orders.items(fresh.id());
                List<Payment> payments = orders.payments(fresh.id());
                customerText = builder.customerReceipt(fresh, items, payments, sequenceId, reprint);
                saveText(fresh, reprint ? "CUSTOMER_REPRINT" : "CUSTOMER", customerText, sequenceId);

                String filePrefix = reprint ? "Reprint_" : "Receipt_";
                customerPdf = AppPaths.receipts().resolve(filePrefix + fresh.orderNo() + "_" + ts + ".pdf");
                ReceiptPdfGenerator.generateCustomerPdf(fresh, items, payments, sequenceId, settings, customerPdf, reprint);

                jobC = system.createPrintJob(sequenceId, fresh.id(), "CUSTOMER",
                        printerNameOrAuto(cashierConfigured), "QUEUED", null, reprint, userId);

                boolean pdfOnly = isPdfOnlyMode(cashierConfigured);
                boolean explicitHardware = isExplicitHardwarePrinter(cashierConfigured);
                PrintService cPrinter = pdfOnly ? null : resolveThermalPrinter(cashierConfigured);

                boolean printed = false;
                if (cPrinter != null) {
                    printed = sendEscPos(cPrinter, customerText, true, cashierCut);
                    if (!printed) {
                        throw new RuntimeException("Could not submit receipt to printer '" + cPrinter.getName() + "'.");
                    }
                } else if (explicitHardware) {
                    throw new RuntimeException("Configured cashier printer '" + cashierConfigured + "' is unavailable or disconnected.");
                }

                String note = printed
                        ? (reprint ? "Reprinted" : "Printed") + " receipt to '" + cPrinter.getName() + "' (80mm PDF: " + customerPdf.getFileName() + ")."
                        : (pdfOnly
                            ? "Saved 80 mm customer receipt PDF: " + customerPdf.getFileName()
                            : "Thermal printer not detected. Generated 80 mm customer receipt PDF: " + customerPdf.getFileName());
                system.updatePrintJob(jobC, "SUCCESS", note);
                audit.log(reprint ? "RECEIPT_REPRINT" : "RECEIPT_PRINT", "orders", fresh.id(), sequenceId + " / " + note);
                if (callback != null) {
                    callback.accept(new PrintResult(true, printed, note, "", customerText, null, customerPdf));
                }
            } catch (Exception e) {
                log.warn("Customer receipt print failed for order {}: {}", fresh.orderNo(), e.getMessage());
                if (jobC > 0) system.updatePrintJob(jobC, "FAILED", e.getMessage());
                audit.log("RECEIPT_PRINT_FAILED", "orders", fresh.id(), sequenceId + " â€” " + e.getMessage());
                if (callback != null) {
                    callback.accept(new PrintResult(false, false,
                            "Customer receipt print failed: " + e.getMessage(), "", customerText, null, customerPdf));
                }
            }
        });
    }

    public void printPair(Order order, boolean reprint, Long userId, Consumer<PrintResult> callback) {
        printPair(order, reprint, userId, null, callback);
    }

    /**
     * Executes the standardized 80 mm print sequence off the UI thread:
     * 1. Kitchen Order Ticket (skipped on reprint or if already printed on order placement)
     * 2. Configured inter-job delay
     * 3. Customer Receipt with Circular Logo (thermal print or 80 mm PDF fallback)
     */
    public void printPair(Order order, boolean reprint, Long userId, String chosenCashierPrinter, Consumer<PrintResult> callback) {
        printCustomerReceipt(order, reprint, userId, chosenCashierPrinter, callback);
    }


    public String previewCustomer(Order order) {
        return previewCustomer(order, false);
    }

    public String previewCustomer(Order order, boolean reprint) {
        Order fresh = orders.find(order.id());
        Order useOrder = fresh != null ? fresh : order;
        return builder.customerReceipt(useOrder, orders.items(useOrder.id()), orders.payments(useOrder.id()),
                (reprint ? "REPRINT-" : "PREVIEW-") + useOrder.orderNo(), reprint);
    }

    /**
     * Resolves an 80 mm thermal printer:
     * 1. If a specific device is configured and found in javax.print, use it.
     * 2. If configured is "Auto-detect" or none, automatically detects any connected 80mm thermal printer.
     */
    public PrintService resolveThermalPrinter(String configuredName) {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        if (services == null || services.length == 0) return null;

        // Check if explicitly configured device matches
        if (configuredName != null && !configuredName.isBlank()
                && !"Auto-detect 80mm Thermal Printer".equalsIgnoreCase(configuredName)
                && !"Save to file only".equalsIgnoreCase(configuredName)
                && !"None".equalsIgnoreCase(configuredName)) {

            if ("__DEFAULT__".equalsIgnoreCase(configuredName)) {
                return PrintServiceLookup.lookupDefaultPrintService();
            }
            for (PrintService s : services) {
                if (s.getName().equalsIgnoreCase(configuredName)) return s;
            }
            for (PrintService s : services) {
                if (s.getName().toLowerCase().contains(configuredName.toLowerCase())) return s;
            }
        }

        // Auto-detect 80 mm thermal printer by industry keywords
        String[] thermalKeywords = {
                "thermal", "pos", "receipt", "80", "xp-", "xprinter",
                "tm-t", "tmt", "epson", "star", "bixolon", "pos-80",
                "rp-", "tsp", "srp", "rongta", "gprinter", "sunmi", "munbyn"
        };
        for (PrintService s : services) {
            String lower = s.getName().toLowerCase();
            for (String kw : thermalKeywords) {
                if (lower.contains(kw)) {
                    log.info("Auto-detected 80 mm thermal printer: '{}'", s.getName());
                    return s;
                }
            }
        }

        return null;
    }

    private boolean sendEscPos(PrintService service, String text, boolean withLogo, boolean cutEnabled) {
        if (!isThermalPrinter(service.getName())) {
            return printFormattedGraphicReceipt(service, text, withLogo);
        }
        try {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            byte[] logo = withLogo ? ReceiptLogo.escPosRaster(settings) : new byte[0];
            byte[] cut = cutEnabled ? new byte[]{0x1D, 0x56, 0x41, 0x00} : new byte[0];
            byte[] payload = new byte[2 + logo.length + body.length + cut.length];
            payload[0] = 0x1B; payload[1] = 0x40; // ESC @ initialize
            System.arraycopy(logo, 0, payload, 2, logo.length);
            System.arraycopy(body, 0, payload, 2 + logo.length, body.length);
            System.arraycopy(cut, 0, payload, 2 + logo.length + body.length, cut.length);

            Doc doc = new SimpleDoc(new ByteArrayInputStream(payload), DocFlavor.INPUT_STREAM.AUTOSENSE, null);
            DocPrintJob job = service.createPrintJob();
            job.print(doc, new HashPrintRequestAttributeSet());
            log.info("Successfully sent 80 mm ESC/POS print job (logo={}) to {}", withLogo, service.getName());
            return true;
        } catch (Exception e) {
            log.warn("Direct ESC/POS printing failed on '{}', attempting Windows graphic fallback: {}", service.getName(), e.getMessage());
            return printFormattedGraphicReceipt(service, text, withLogo);
        }
    }

    private boolean isThermalPrinter(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        String[] thermalKeywords = {
                "thermal", "pos", "receipt", "80", "xp-", "xprinter",
                "tm-t", "tmt", "epson", "star", "bixolon", "pos-80",
                "rp-", "tsp", "srp", "rongta", "gprinter", "sunmi", "munbyn"
        };
        for (String kw : thermalKeywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private boolean printFormattedGraphicReceipt(PrintService service, String text, boolean withLogo) {
        try {
            java.awt.image.BufferedImage circular = withLogo ? ReceiptLogo.circleSquareLogo(settings) : null;
            String[] lines = text == null ? new String[0] : text.split("\\R");
            java.awt.print.PrinterJob job = java.awt.print.PrinterJob.getPrinterJob();
            job.setPrintService(service);
            java.awt.print.PageFormat pf = job.defaultPage();
            java.awt.print.Paper paper = new java.awt.print.Paper();
            double widthPt = 226.77;
            double heightPt = Math.max(420.0, 120.0 + lines.length * 10.5);
            paper.setSize(widthPt, heightPt);
            paper.setImageableArea(8, 10, widthPt - 16, heightPt - 20);
            pf.setPaper(paper);

            job.setPrintable((graphics, pageFormat, pageIndex) -> {
                if (pageIndex > 0) return java.awt.print.Printable.NO_SUCH_PAGE;
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) graphics;
                g2.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int y = 4;
                if (circular != null) {
                    int logoSize = 54;
                    int logoX = (int) ((pageFormat.getImageableWidth() - logoSize) / 2.0);
                    g2.drawImage(circular, logoX, y, logoSize, logoSize, null);
                    y += logoSize + 8;
                }
                g2.setColor(java.awt.Color.BLACK);
                g2.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 7));
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int lineH = Math.max(9, fm.getHeight());
                for (String line : lines) {
                    y += lineH;
                    g2.drawString(line, 2, y);
                }
                return java.awt.print.Printable.PAGE_EXISTS;
            }, pf);
            job.print();
            log.info("Successfully sent formatted 80 mm graphic print job (logo={}) to {}", withLogo, service.getName());
            return true;
        } catch (Exception e) {
            log.warn("Formatted graphic printing failed on '{}': {}", service.getName(), e.getMessage());
            return false;
        }
    }

    private Path saveText(Order order, String kind, String text, String sequenceId) throws Exception {
        Path dir = AppPaths.receipts();
        Path file = dir.resolve(order.orderNo() + "_" + kind + "_" + sequenceId + ".txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private String printerNameOrAuto(String name) {
        return (name == null || name.isBlank() || "Save to file only".equals(name))
                ? "Auto-detect 80mm Thermal Printer" : name;
    }
}
