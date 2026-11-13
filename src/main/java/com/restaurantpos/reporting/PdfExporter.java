package com.restaurantpos.reporting;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.*;
import com.restaurantpos.util.BrandLogo;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class PdfExporter {
    private PdfExporter() {}

    public static Path export(ReportData data, Path directory, String restaurantName) throws Exception {
        return export(data, directory, restaurantName, "");
    }

    public static Path export(ReportData data, Path directory, String restaurantName, String restaurantAddress) throws Exception {
        Files.createDirectories(directory);
        Path target = directory.resolve(ExcelExporter.fileName(data) + ".pdf");
        boolean landscape = data.columns().size() > 6;
        Rectangle page = landscape ? PageSize.A4.rotate() : PageSize.A4;
        Document doc = new Document(page, 32, 32, 42, 44);
        PdfWriter writer = PdfWriter.getInstance(doc, Files.newOutputStream(target));
        writer.setPageEvent(new Footer(restaurantName));
        doc.open();

        Font brand = new Font(Font.HELVETICA, 17, Font.BOLD, new Color(24, 33, 48));
        Font subtitle = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(110, 116, 128));
        Font head = new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE);
        Font body = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(35, 38, 45));
        Font bodyBold = new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(35, 38, 45));

        PdfPTable brandHeader = new PdfPTable(new float[]{46f, doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin() - 46f});
        brandHeader.setWidthPercentage(100);
        brandHeader.setSpacingAfter(4);

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        try {
            BufferedImage logoImg = BrandLogo.roundedBuffered(null, 160, 24);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(logoImg, "PNG", baos);
            Image pdfLogo = Image.getInstance(baos.toByteArray());
            pdfLogo.scaleToFit(38f, 38f);
            logoCell.addElement(pdfLogo);
        } catch (Exception ignored) {}
        brandHeader.addCell(logoCell);

        PdfPCell textCell = new PdfPCell();
        textCell.setBorder(Rectangle.NO_BORDER);
        textCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Paragraph name = new Paragraph(restaurantName.toUpperCase(), brand);
        name.setSpacingAfter(2);
        textCell.addElement(name);
        if (restaurantAddress != null && !restaurantAddress.isBlank()) {
            Paragraph addrP = new Paragraph(restaurantAddress, subtitle);
            textCell.addElement(addrP);
        }
        brandHeader.addCell(textCell);
        doc.add(brandHeader);
        Paragraph title = new Paragraph(data.title(), new Font(Font.HELVETICA, 12, Font.BOLD, new Color(218, 108, 35)));
        title.setSpacingAfter(8);
        doc.add(title);
        doc.add(new Paragraph("Reporting period: " + data.period(), subtitle));
        doc.add(new Paragraph("Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")), subtitle));
        doc.add(new Paragraph(" ", subtitle));

        int cols = data.columns().size();
        float available = doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin();
        int widthSum = data.columns().stream().mapToInt(ReportData.Column::width).sum();
        float[] widths = new float[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = available * Math.max(4, data.columns().get(i).width()) / Math.max(1, widthSum);
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSplitLate(false);

        Color headerBg = new Color(25, 32, 45);
        for (ReportData.Column c : data.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(c.label(), head));
            cell.setBackgroundColor(headerBg);
            cell.setPadding(5);
            cell.setBorderColor(new Color(40, 48, 62));
            cell.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.addCell(cell);
        }

        boolean alternate = false;
        for (Object[] row : data.rows()) {
            Color bg = alternate ? new Color(248, 246, 242) : Color.WHITE;
            for (int c = 0; c < cols; c++) {
                Object v = c < row.length ? row[c] : null;
                String text = ReportData.format(v, data.columns().get(c).type());
                PdfPCell cell = new PdfPCell(new Phrase(text, body));
                cell.setBackgroundColor(bg);
                cell.setPadding(4);
                cell.setBorderColor(new Color(226, 223, 218));
                cell.setBorderWidth(0.35f);
                if (data.columns().get(c).type() == ReportData.ColType.CURRENCY
                        || data.columns().get(c).type() == ReportData.ColType.INTEGER) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
            alternate = !alternate;
        }
        doc.add(table);

        if (!data.rows().isEmpty()) {
            doc.add(new Paragraph(" ", subtitle));
            Paragraph totals = new Paragraph();
            totals.setAlignment(Element.ALIGN_RIGHT);
            totals.add(new Chunk("Totals:  ", bodyBold));
            boolean any = false;
            for (int c = 0; c < cols; c++) {
                if (data.columns().get(c).type() == ReportData.ColType.CURRENCY) {
                    totals.add(new Chunk(data.columns().get(c).label() + "  Rs " +
                            String.format("%,.2f", data.sum(c)) + "      ", body));
                    any = true;
                }
            }
            if (any) doc.add(totals);
        }
        doc.close();
        return target;
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String brand;
        private final Font font = new Font(Font.HELVETICA, 7, Font.NORMAL, new Color(120, 124, 132));
        Footer(String brand) { this.brand = brand; }
        @Override public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase left = new Phrase(brand + " • Confidential business report", font);
            Phrase right = new Phrase("Page " + writer.getPageNumber(), font);
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, left, document.leftMargin(),
                    document.bottomMargin() - 18, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, right, document.getPageSize().getWidth() - document.rightMargin(),
                    document.bottomMargin() - 18, 0);
        }
    }
}
