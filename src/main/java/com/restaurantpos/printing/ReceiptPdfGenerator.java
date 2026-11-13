package com.restaurantpos.printing;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.service.SettingsService;
import com.restaurantpos.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * High-quality 80 mm thermal receipt PDF generator using OpenPDF.
 * Output conforms strictly to standard 80 mm POS thermal paper width (~226.77 pt).
 */
public final class ReceiptPdfGenerator {
    private static final Logger log = LoggerFactory.getLogger(ReceiptPdfGenerator.class);

    // 80 mm width in typographic points (80 / 25.4 * 72 = 226.77)
    public static final float PAGE_WIDTH_PT = 226.77f;
    private static final float MARGIN_H = 10f;
    private static final float MARGIN_V = 12f;
    private static final float PRINTABLE_WIDTH = PAGE_WIDTH_PT - (MARGIN_H * 2); // 206.77 pt
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a");

    // Fonts
    private static final Font FONT_TITLE = new Font(Font.HELVETICA, 10.5f, Font.BOLD, new Color(20, 24, 33));
    private static final Font FONT_SUBTITLE = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(85, 90, 100));
    private static final Font FONT_LABEL = new Font(Font.HELVETICA, 7f, Font.BOLD, new Color(60, 64, 72));
    private static final Font FONT_VALUE = new Font(Font.HELVETICA, 7f, Font.NORMAL, new Color(20, 24, 33));
    private static final Font FONT_VALUE_BOLD = new Font(Font.HELVETICA, 7f, Font.BOLD, new Color(20, 24, 33));
    private static final Font FONT_ITEM_NAME = new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(20, 24, 33));
    private static final Font FONT_ITEM_DETAIL = new Font(Font.HELVETICA, 6.5f, Font.ITALIC, new Color(95, 100, 110));
    private static final Font FONT_TOTAL_TITLE = new Font(Font.HELVETICA, 9.5f, Font.BOLD, new Color(20, 24, 33));
    private static final Font FONT_TOTAL_VALUE = new Font(Font.HELVETICA, 9.5f, Font.BOLD, new Color(185, 40, 25));
    private static final Font FONT_DIVIDER = new Font(Font.COURIER, 6.5f, Font.NORMAL, new Color(175, 180, 190));
    private static final Font FONT_BANNER_TITLE = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
    private static final Font FONT_BANNER_SUB = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, Color.WHITE);
    private static final Font FONT_FOOTER = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, new Color(110, 115, 125));

    private ReceiptPdfGenerator() {}

    /**
     * Generates an 80 mm thermal Customer Receipt PDF.
     */
    public static Path generateCustomerPdf(Order order, List<OrderItem> items, List<Payment> payments,
                                          String sequenceId, SettingsService settings, Path outputPath) throws Exception {
        return generateCustomerPdf(order, items, payments, sequenceId, settings, outputPath, false);
    }

    /**
     * Generates an 80 mm thermal Customer Receipt PDF with optional reprint indicator.
     */
    public static Path generateCustomerPdf(Order order, List<OrderItem> items, List<Payment> payments,
                                          String sequenceId, SettingsService settings, Path outputPath, boolean reprint) throws Exception {
        Files.createDirectories(outputPath.getParent());

        // Calculate dynamic height to fit continuous thermal roll layout
        int itemCount = items == null ? 0 : items.size();
        int paymentCount = payments == null ? 0 : payments.size();
        boolean isDelivery = order.type() == OrderType.DELIVERY;

        float estimatedHeight = 460f
                + (itemCount * 28f)
                + (paymentCount * 20f)
                + (isDelivery ? 65f : 0f)
                + (reprint ? 30f : 0f);
        float pageHeight = Math.max(420f, estimatedHeight);

        Rectangle pageSize = new Rectangle(PAGE_WIDTH_PT, pageHeight);
        Document doc = new Document(pageSize, MARGIN_H, MARGIN_H, MARGIN_V, MARGIN_V);

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            PdfWriter.getInstance(doc, out);
            doc.open();

            // 1. Centered Circular Restaurant Logo
            addCircularLogo(doc, settings);

            // 2. Restaurant Brand & Header
            Paragraph rName = new Paragraph(settings.restaurantName().toUpperCase(), FONT_TITLE);
            rName.setAlignment(Element.ALIGN_CENTER);
            rName.setSpacingAfter(2f);
            doc.add(rName);

            String headerLines = settings.receiptHeader();
            boolean addressRendered = false;
            String addr = settings.restaurantAddress();
            if (headerLines != null && !headerLines.isBlank()) {
                for (String line : headerLines.split("\\R")) {
                    Paragraph pLine = new Paragraph(line.trim(), FONT_SUBTITLE);
                    pLine.setAlignment(Element.ALIGN_CENTER);
                    doc.add(pLine);
                    if (addr != null && !addr.isBlank() && line.toLowerCase().contains(addr.toLowerCase())) {
                        addressRendered = true;
                    }
                }
            }
            if (!addressRendered && addr != null && !addr.isBlank()) {
                Paragraph pAddr = new Paragraph(addr.trim(), FONT_SUBTITLE);
                pAddr.setAlignment(Element.ALIGN_CENTER);
                doc.add(pAddr);
            }

            if (order.paymentStatus() == PaymentStatus.PAID) {
                Paragraph receiptTitle = new Paragraph("PAID CUSTOMER RECEIPT",
                        new Font(Font.HELVETICA, 9.5f, Font.BOLD, new Color(25, 110, 55)));
                receiptTitle.setAlignment(Element.ALIGN_CENTER);
                receiptTitle.setSpacingBefore(3f);
                receiptTitle.setSpacingAfter(2f);
                doc.add(receiptTitle);
            }

            if (reprint) {
                PdfPTable rTable = new PdfPTable(1);
                rTable.setWidthPercentage(100);
                PdfPCell rCell = new PdfPCell(new Phrase("*** REPRINT / DUPLICATE RECEIPT ***",
                        new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(130, 40, 20))));
                rCell.setBackgroundColor(new Color(255, 242, 235));
                rCell.setBorderColor(new Color(230, 160, 140));
                rCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                rCell.setPadding(3.5f);
                rTable.addCell(rCell);
                rTable.setSpacingBefore(4f);
                rTable.setSpacingAfter(2f);
                doc.add(rTable);
            }

            addDashedDivider(doc);

            // 3. Receipt & Order Metadata Table
            PdfPTable metaTable = new PdfPTable(new float[]{103f, 103.77f});
            metaTable.setWidthPercentage(100);

            metaTable.addCell(pairCell("Receipt #:", sequenceId));
            metaTable.addCell(pairCell("Order Date:", order.createdAt() == null ? "" : order.createdAt().format(TIME_FMT)));

            metaTable.addCell(pairCell("Order #:", order.orderNo()));
            metaTable.addCell(pairCell("Cashier:", order.cashierName()));
            if (order.completedAt() != null) {
                metaTable.addCell(pairCell("Completed:", order.completedAt().format(TIME_FMT)));
                metaTable.addCell(pairCell("", ""));
            }

            metaTable.addCell(pairCell("Order Type:", order.type().label()));
            if (order.tableNo() != null) {
                metaTable.addCell(pairCell("Table:", formatTableLabel(order.tableNo())));
            } else if (order.tokenNo() != null) {
                metaTable.addCell(pairCell("Token:", "Token " + order.tokenNo()));
            } else {
                metaTable.addCell(pairCell("", ""));
            }

            doc.add(metaTable);

            // 4. Delivery / Customer Information Box
            if (isDelivery && order.customerName() != null && !order.customerName().isBlank()) {
                addDashedDivider(doc);
                PdfPTable delivTable = new PdfPTable(1);
                delivTable.setWidthPercentage(100);
                PdfPCell dCell = new PdfPCell();
                dCell.setBackgroundColor(new Color(245, 247, 250));
                dCell.setBorderColor(new Color(215, 220, 228));
                dCell.setPadding(5f);

                Paragraph dHead = new Paragraph("DELIVERY CUSTOMER DETAILS", FONT_LABEL);
                dCell.addElement(dHead);
                dCell.addElement(new Paragraph("Customer: " + order.customerName(), FONT_VALUE_BOLD));
                if (order.customerPhone() != null && !order.customerPhone().isBlank()) {
                    dCell.addElement(new Paragraph("Phone: " + order.customerPhone(), FONT_VALUE));
                }
                if (order.deliveryAddress() != null && !order.deliveryAddress().isBlank()) {
                    dCell.addElement(new Paragraph("Address: " + order.deliveryAddress(), FONT_VALUE));
                }
                if (order.notes() != null && !order.notes().isBlank()) {
                    dCell.addElement(new Paragraph("Instructions: " + order.notes(), FONT_ITEM_DETAIL));
                }
                delivTable.addCell(dCell);
                doc.add(delivTable);
            }

            addDashedDivider(doc);

            // 5. Itemized Bill Table (Qty, Item, Unit Price, Line Total)
            PdfPTable itemTable = new PdfPTable(new float[]{24f, 100f, 40f, 42.77f});
            itemTable.setWidthPercentage(100);

            // Header row
            itemTable.addCell(headerCell("QTY", Element.ALIGN_CENTER));
            itemTable.addCell(headerCell("ITEM", Element.ALIGN_LEFT));
            itemTable.addCell(headerCell("PRICE", Element.ALIGN_RIGHT));
            itemTable.addCell(headerCell("TOTAL", Element.ALIGN_RIGHT));

            for (OrderItem itm : items) {
                // Qty
                PdfPCell cQty = new PdfPCell(new Phrase(String.valueOf(itm.qty()), FONT_VALUE_BOLD));
                cQty.setHorizontalAlignment(Element.ALIGN_CENTER);
                cQty.setBorder(Rectangle.NO_BORDER);
                cQty.setPaddingTop(3f);
                cQty.setPaddingBottom(2f);
                itemTable.addCell(cQty);

                // Name & Addons
                PdfPCell cName = new PdfPCell();
                cName.setBorder(Rectangle.NO_BORDER);
                cName.setPaddingTop(3f);
                cName.setPaddingBottom(2f);
                cName.addElement(new Paragraph(itm.name(), FONT_ITEM_NAME));
                if (itm.addons() != null && !itm.addons().isBlank()) {
                    cName.addElement(new Paragraph("+ " + itm.addons(), FONT_ITEM_DETAIL));
                }
                if (itm.instructions() != null && !itm.instructions().isBlank()) {
                    cName.addElement(new Paragraph("* " + itm.instructions(), FONT_ITEM_DETAIL));
                }
                itemTable.addCell(cName);

                // Unit Price
                PdfPCell cPrice = new PdfPCell(new Phrase(Money.plain(itm.unitPrice()), FONT_VALUE));
                cPrice.setHorizontalAlignment(Element.ALIGN_RIGHT);
                cPrice.setBorder(Rectangle.NO_BORDER);
                cPrice.setPaddingTop(3f);
                cPrice.setPaddingBottom(2f);
                itemTable.addCell(cPrice);

                // Line Total
                PdfPCell cTotal = new PdfPCell(new Phrase(Money.plain(itm.lineTotal()), FONT_VALUE_BOLD));
                cTotal.setHorizontalAlignment(Element.ALIGN_RIGHT);
                cTotal.setBorder(Rectangle.NO_BORDER);
                cTotal.setPaddingTop(3f);
                cTotal.setPaddingBottom(2f);
                itemTable.addCell(cTotal);
            }
            doc.add(itemTable);

            addDashedDivider(doc);

            // 6. Financial Summary Table
            PdfPTable totalTable = new PdfPTable(new float[]{116f, 90.77f});
            totalTable.setWidthPercentage(100);

            addTotalRow(totalTable, "Subtotal:", Money.fmt(order.subtotal()), false);
            if (Money.nz(order.discountTotal()).signum() > 0) {
                addTotalRow(totalTable, "Discounts:", "-" + Money.fmt(order.discountTotal()), false);
            }
            if (Money.nz(order.taxTotal()).signum() > 0) {
                addTotalRow(totalTable, "Tax:", Money.fmt(order.taxTotal()), false);
            }
            if (Money.nz(order.serviceCharge()).signum() > 0) {
                addTotalRow(totalTable, "Service Charge:", Money.fmt(order.serviceCharge()), false);
            }
            if (Money.nz(order.deliveryCharge()).signum() > 0) {
                addTotalRow(totalTable, "Delivery Charge:", Money.fmt(order.deliveryCharge()), false);
            }

            // Grand Total
            PdfPCell gtLabel = new PdfPCell(new Phrase("GRAND TOTAL:", FONT_TOTAL_TITLE));
            gtLabel.setBorder(Rectangle.TOP);
            gtLabel.setBorderColor(new Color(50, 55, 65));
            gtLabel.setPaddingTop(4f);
            gtLabel.setPaddingBottom(4f);

            PdfPCell gtVal = new PdfPCell(new Phrase(Money.fmt(order.total()), FONT_TOTAL_VALUE));
            gtVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
            gtVal.setBorder(Rectangle.TOP);
            gtVal.setBorderColor(new Color(50, 55, 65));
            gtVal.setPaddingTop(4f);
            gtVal.setPaddingBottom(4f);

            totalTable.addCell(gtLabel);
            totalTable.addCell(gtVal);

            // Paid amount & Change/Balance
            addTotalRow(totalTable, "Paid Total:", Money.fmt(order.paidTotal()), false);
            if (Money.nz(order.creditAmount()).signum() > 0) {
                addTotalRow(totalTable, "On Account (Ledger):", Money.fmt(order.creditAmount()), false);
            }
            if (Money.nz(order.changeDue()).signum() > 0) {
                addTotalRow(totalTable, "Change Returned:", Money.fmt(order.changeDue()), false);
            }
            BigDecimal balance = order.balance();
            if (balance.signum() > 0) {
                addTotalRow(totalTable, "Outstanding Balance:", Money.fmt(balance), true);
            }

            doc.add(totalTable);

            // 7. Payment Methods List
            if (payments != null && !payments.isEmpty()) {
                addDashedDivider(doc);
                PdfPTable payTable = new PdfPTable(new float[]{116f, 90.77f});
                payTable.setWidthPercentage(100);
                for (Payment p : payments) {
                    String pText = p.method().label();
                    if (p.reference() != null && !p.reference().isBlank()) {
                        pText += " (Ref: " + p.reference() + ")";
                    }
                    PdfPCell pCell1 = new PdfPCell(new Phrase(pText, FONT_VALUE));
                    pCell1.setBorder(Rectangle.NO_BORDER);
                    PdfPCell pCell2 = new PdfPCell(new Phrase(Money.fmt(p.amount()), FONT_VALUE_BOLD));
                    pCell2.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    pCell2.setBorder(Rectangle.NO_BORDER);
                    payTable.addCell(pCell1);
                    payTable.addCell(pCell2);
                }
                doc.add(payTable);
            }

            addDoubleDivider(doc);

            // 8. Prominent Payment Status Banner (PAID vs UNPAID)
            PdfPTable bannerTable = new PdfPTable(1);
            bannerTable.setWidthPercentage(100);
            PdfPCell bCell = new PdfPCell();
            bCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            bCell.setPadding(6f);

            if (order.paymentStatus() == PaymentStatus.PAID) {
                bCell.setBackgroundColor(new Color(36, 120, 68)); // Forest green
                Paragraph bTitle = new Paragraph("*** PAID â€” PAYMENT CONFIRMED ***", FONT_BANNER_TITLE);
                bTitle.setAlignment(Element.ALIGN_CENTER);
                Paragraph bSub = new Paragraph("Thank you for your business! Please visit again.", FONT_BANNER_SUB);
                bSub.setAlignment(Element.ALIGN_CENTER);
                bCell.addElement(bTitle);
                bCell.addElement(bSub);
            } else if (order.paymentStatus() == PaymentStatus.ON_ACCOUNT) {
                bCell.setBackgroundColor(new Color(45, 95, 165)); // Ledger Blue
                Paragraph bTitle = new Paragraph("*** ON ACCOUNT â€” CUSTOMER LEDGER ***", FONT_BANNER_TITLE);
                bTitle.setAlignment(Element.ALIGN_CENTER);
                Paragraph bSub = new Paragraph("Billed to customer ledger. Cash payment not yet settled.", FONT_BANNER_SUB);
                bSub.setAlignment(Element.ALIGN_CENTER);
                bCell.addElement(bTitle);
                bCell.addElement(bSub);
            } else {
                bCell.setBackgroundColor(new Color(185, 90, 15)); // Amber / Orange
                Paragraph bTitle = new Paragraph("*** UNPAID â€” PAYMENT PENDING ***", FONT_BANNER_TITLE);
                bTitle.setAlignment(Element.ALIGN_CENTER);
                Paragraph bSub = new Paragraph("Payment has not been received. Please pay at counter.", FONT_BANNER_SUB);
                bSub.setAlignment(Element.ALIGN_CENTER);
                bCell.addElement(bTitle);
                bCell.addElement(bSub);
            }
            bannerTable.addCell(bCell);
            doc.add(bannerTable);

            // 9. Footer
            String footerLines = settings.receiptFooter();
            if (footerLines != null && !footerLines.isBlank()) {
                addDashedDivider(doc);
                for (String fLine : footerLines.split("\\R")) {
                    Paragraph pFooter = new Paragraph(fLine.trim(), FONT_FOOTER);
                    pFooter.setAlignment(Element.ALIGN_CENTER);
                    doc.add(pFooter);
                }
            }

            Paragraph softAttribution = new Paragraph("Powered by ZS Sattar", FONT_FOOTER);
            softAttribution.setAlignment(Element.ALIGN_CENTER);
            softAttribution.setSpacingBefore(4f);
            doc.add(softAttribution);

            doc.close();
        }

        log.info("80 mm Customer receipt PDF generated: {}", outputPath);
        return outputPath;
    }

    /**
     * Generates an 80 mm thermal Customer Ledger Receipt PDF with centered circular logo.
     */
    public static Path generateLedgerPaymentPdf(String customerName, String customerPhone,
                                                CustomerLedgerEntry entry, SettingsService settings,
                                                Path outputPath) throws Exception {
        return generateLedgerReceiptPdf(customerName, customerPhone, "", entry, settings, outputPath, false);
    }

    /**
     * Generates an 80 mm thermal Customer Ledger Receipt PDF supporting credit charges,
     * payment collections, previous/remaining balances, related order #, and reprint indicator.
     */
    public static Path generateLedgerReceiptPdf(String customerName, String customerPhone, String customerAddress,
                                                CustomerLedgerEntry entry, SettingsService settings,
                                                Path outputPath, boolean reprint) throws Exception {
        Files.createDirectories(outputPath.getParent());

        boolean isCredit = "CREDIT_SALE".equalsIgnoreCase(entry.entryType());
        BigDecimal prevBalance = Money.nz(entry.balanceAfter()).subtract(Money.nz(entry.signedDelta()));

        Rectangle pageSize = new Rectangle(PAGE_WIDTH_PT, reprint ? 480f : 455f);
        Document doc = new Document(pageSize, MARGIN_H, MARGIN_H, MARGIN_V, MARGIN_V);

        try (OutputStream out = Files.newOutputStream(outputPath)) {
            PdfWriter.getInstance(doc, out);
            doc.open();

            // 1. Centered Circular Restaurant Logo
            addCircularLogo(doc, settings);

            // 2. Restaurant Brand & Header
            String brand = settings != null ? settings.restaurantName() : "Mubarak Cafe & Restaurant";
            Paragraph rName = new Paragraph(brand.toUpperCase(), FONT_TITLE);
            rName.setAlignment(Element.ALIGN_CENTER);
            rName.setSpacingAfter(2f);
            doc.add(rName);

            String addr = settings != null ? settings.restaurantAddress() : "";
            if (addr != null && !addr.isBlank()) {
                Paragraph rAddr = new Paragraph(addr.trim(), FONT_SUBTITLE);
                rAddr.setAlignment(Element.ALIGN_CENTER);
                doc.add(rAddr);
            }
            String phone = settings != null ? settings.restaurantPhone() : "";
            if (phone != null && !phone.isBlank()) {
                Paragraph rPhone = new Paragraph("Tel: " + phone.trim(), FONT_SUBTITLE);
                rPhone.setAlignment(Element.ALIGN_CENTER);
                doc.add(rPhone);
            }

            Color titleColor = isCredit ? new Color(185, 85, 15) : new Color(25, 110, 62);
            Paragraph title = new Paragraph("CUSTOMER LEDGER RECEIPT", new Font(Font.HELVETICA, 8.8f, Font.BOLD, titleColor));
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingBefore(4f);
            title.setSpacingAfter(3f);
            doc.add(title);

            if (reprint) {
                Paragraph rep = new Paragraph("*** REPRINT / DUPLICATE RECEIPT ***",
                        new Font(Font.HELVETICA, 7.2f, Font.BOLD, new Color(130, 40, 20)));
                rep.setAlignment(Element.ALIGN_CENTER);
                rep.setSpacingAfter(2f);
                doc.add(rep);
            }

            addDoubleDivider(doc);

            PdfPTable meta = new PdfPTable(new float[]{85f, 121.77f});
            meta.setWidthPercentage(100);
            meta.addCell(pairCell("Receipt #:", "CLR-" + entry.id()));
            meta.addCell(pairCell("Date:", entry.createdAt() == null ? "" : entry.createdAt().format(TIME_FMT)));
            meta.addCell(pairCell("Customer:", customerName == null ? "-" : customerName));
            meta.addCell(pairCell("Phone:", customerPhone == null || customerPhone.isBlank() ? "-" : customerPhone));
            if (customerAddress != null && !customerAddress.isBlank()) {
                meta.addCell(pairCell("Address:", customerAddress.trim()));
                meta.addCell(pairCell("", ""));
            }
            if (entry.orderNo() != null && !entry.orderNo().isBlank()) {
                meta.addCell(pairCell("Order #:", entry.orderNo()));
                meta.addCell(pairCell("", ""));
            }
            meta.addCell(pairCell("Txn Type:", isCredit ? "Credit Added (+)" : "Payment Received (-)"));
            if (!isCredit) {
                meta.addCell(pairCell("Method:", entry.paymentMethod() == null || entry.paymentMethod().isBlank() ? "Cash" : entry.paymentMethod()));
            } else {
                meta.addCell(pairCell("", ""));
            }
            meta.addCell(pairCell("Reference:", entry.reference() == null || entry.reference().isBlank() ? "-" : entry.reference()));
            meta.addCell(pairCell("Staff:", entry.staffName() == null || entry.staffName().isBlank() ? "-" : entry.staffName()));
            doc.add(meta);

            addDashedDivider(doc);

            PdfPTable totals = new PdfPTable(new float[]{112f, 94.77f});
            totals.setWidthPercentage(100);
            addTotalRow(totals, "Previous Balance:", Money.fmt(prevBalance), false);
            addTotalRow(totals, isCredit ? "Credit Added (+):" : "Amount Received (-):", Money.fmt(entry.amount()), false);
            addTotalRow(totals, "Remaining Balance:", Money.fmt(entry.balanceAfter()), true);
            addTotalRow(totals, "Status:", isCredit ? "CHARGED TO LEDGER" : "PAYMENT CONFIRMED", false);
            doc.add(totals);

            if (entry.description() != null && !entry.description().isBlank()) {
                addDashedDivider(doc);
                Paragraph pNote = new Paragraph("Note: " + entry.description().trim(), FONT_VALUE);
                pNote.setSpacingAfter(2f);
                doc.add(pNote);
            }

            addDoubleDivider(doc);
            String footerText = settings != null && settings.receiptFooter() != null && !settings.receiptFooter().isBlank()
                    ? settings.receiptFooter() : "Thank you for your business!";
            for (String line : footerText.split("\\R")) {
                if (!line.isBlank()) {
                    Paragraph foot = new Paragraph(line.trim(), FONT_FOOTER);
                    foot.setAlignment(Element.ALIGN_CENTER);
                    doc.add(foot);
                }
            }

            Paragraph pPowered = new Paragraph("Powered by ZS Sattar", FONT_FOOTER);
            pPowered.setAlignment(Element.ALIGN_CENTER);
            pPowered.setSpacingBefore(4f);
            doc.add(pPowered);

            doc.close();
        }

        log.info("80 mm Customer Ledger receipt PDF generated: {}", outputPath);
        return outputPath;
    }

    private static void addCircularLogo(Document doc, SettingsService settings) {
        try {
            BufferedImage logo = ReceiptLogo.circleSquareLogo(settings);
            if (logo == null) {
                throw new IllegalStateException("Circular official restaurant logo rendered null.");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(logo, "PNG", baos);
            Image img = Image.getInstance(baos.toByteArray());
            img.setAlignment(Element.ALIGN_CENTER);
            img.scaleToFit(58f, 58f);
            img.setSpacingBefore(2f);
            img.setSpacingAfter(6f);
            doc.add(img);
        } catch (Exception e) {
            log.error("Failed to embed official circular logo into 80 mm receipt PDF", e);
            throw new IllegalStateException("Could not embed official circular logo into receipt PDF: " + e.getMessage(), e);
        }
    }

    private static void addDashedDivider(Document doc) throws DocumentException {
        Paragraph p = new Paragraph("--------------------------------------------------", FONT_DIVIDER);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(1f);
        p.setSpacingAfter(2f);
        doc.add(p);
    }

    private static void addDoubleDivider(Document doc) throws DocumentException {
        Paragraph p = new Paragraph("==================================================", FONT_DIVIDER);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(2f);
        p.setSpacingAfter(3f);
        doc.add(p);
    }

    private static PdfPCell pairCell(String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(1.5f);
        if (label != null && !label.isBlank()) {
            Phrase phrase = new Phrase();
            phrase.add(new Chunk(label + " ", FONT_LABEL));
            phrase.add(new Chunk(value == null ? "" : value, FONT_VALUE));
            cell.addElement(phrase);
        } else if (value != null && !value.isBlank()) {
            cell.addElement(new Paragraph(value, FONT_VALUE));
        }
        return cell;
    }

    private static PdfPCell headerCell(String text, int alignment) {
        PdfPCell c = new PdfPCell(new Phrase(text, FONT_LABEL));
        c.setHorizontalAlignment(alignment);
        c.setBackgroundColor(new Color(240, 243, 246));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(new Color(180, 185, 195));
        c.setPadding(3f);
        return c;
    }

    private static void addTotalRow(PdfPTable table, String label, String value, boolean highlight) {
        Font fLabel = highlight ? new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(185, 40, 25)) : FONT_LABEL;
        Font fVal = highlight ? new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(185, 40, 25)) : FONT_VALUE_BOLD;

        PdfPCell cLabel = new PdfPCell(new Phrase(label, fLabel));
        cLabel.setBorder(Rectangle.NO_BORDER);
        cLabel.setPadding(1.5f);

        PdfPCell cVal = new PdfPCell(new Phrase(value, fVal));
        cVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cVal.setBorder(Rectangle.NO_BORDER);
        cVal.setPadding(1.5f);

        table.addCell(cLabel);
        table.addCell(cVal);
    }

    private static String formatTableLabel(String tableNo) {
        if (tableNo == null || tableNo.isBlank()) return "";
        String t = tableNo.trim();
        return t.toLowerCase().startsWith("table") ? t : "Table " + t;
    }
}
