package com.restaurantpos.reporting;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ExcelExporter {
    private ExcelExporter() {}

    public static Path export(ReportData data, Path directory, String restaurantName) throws Exception {
        return export(data, directory, restaurantName, "");
    }

    public static Path export(ReportData data, Path directory, String restaurantName, String restaurantAddress) throws Exception {
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName(data) + ".xlsx");
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(target)) {
            Sheet sheet = wb.createSheet(shorten(data.title(), 28));
            sheet.setDisplayGridlines(false);
            sheet.setColumnWidth(0, 9000);

            CellStyle titleStyle = style(wb, 16, true, IndexedColors.DARK_BLUE.getIndex(), FillPatternType.NO_FILL);
            CellStyle subStyle = style(wb, 10, false, IndexedColors.GREY_50_PERCENT.getIndex(), FillPatternType.NO_FILL);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setFont(font(wb, 10, true, IndexedColors.WHITE.getIndex()));
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);

            CellStyle money = numberStyle(wb, "#,##0.00", HorizontalAlignment.RIGHT);
            CellStyle integer = numberStyle(wb, "#,##0", HorizontalAlignment.RIGHT);
            CellStyle date = numberStyle(wb, "dd mmm yyyy", HorizontalAlignment.CENTER);
            CellStyle dateTime = numberStyle(wb, "dd mmm yyyy hh:mm", HorizontalAlignment.LEFT);
            CellStyle totalStyle = style(wb, 10, true, IndexedColors.DARK_BLUE.getIndex(), FillPatternType.SOLID_FOREGROUND, HorizontalAlignment.RIGHT);
            CellStyle totalText = style(wb, 10, true, IndexedColors.BLACK.getIndex(), FillPatternType.SOLID_FOREGROUND, HorizontalAlignment.RIGHT);

            int row = 0;
            Row r0 = sheet.createRow(row++);
            r0.setHeightInPoints(26);
            Cell title = r0.createCell(0); title.setCellValue(restaurantName + " — " + data.title()); title.setCellStyle(titleStyle);

            if (restaurantAddress != null && !restaurantAddress.isBlank()) {
                Row rAddr = sheet.createRow(row++);
                Cell addrCell = rAddr.createCell(0);
                addrCell.setCellValue("Location: " + restaurantAddress);
                addrCell.setCellStyle(subStyle);
            }

            Row r1 = sheet.createRow(row++);
            Cell period = r1.createCell(0); period.setCellValue("Reporting period: " + data.period()); period.setCellStyle(subStyle);
            Row r2 = sheet.createRow(row++);
            Cell generated = r2.createCell(0);
            generated.setCellValue("Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));
            generated.setCellStyle(subStyle);
            row++;

            int headerRow = row;
            Row header = sheet.createRow(row++);
            header.setHeightInPoints(28);
            for (int c = 0; c < data.columns().size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(data.columns().get(c).label());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(c, Math.max(10, data.columns().get(c).width()) * 256);
            }

            for (Object[] values : data.rows()) {
                Row er = sheet.createRow(row++);
                for (int c = 0; c < data.columns().size(); c++) {
                    Object v = c < values.length ? values[c] : null;
                    Cell cell = er.createCell(c);
                    writeCell(cell, v, data.columns().get(c).type(), money, integer, date, dateTime);
                }
            }

            if (!data.rows().isEmpty()) {
                row++;
                Row total = sheet.createRow(row++);
                Cell label = total.createCell(0);
                label.setCellValue("TOTAL");
                label.setCellStyle(totalText);
                for (int c = 1; c < data.columns().size(); c++) {
                    Cell cell = total.createCell(c);
                    ReportData.ColType t = data.columns().get(c).type();
                    if (t == ReportData.ColType.CURRENCY) {
                        cell.setCellValue(data.sum(c).doubleValue());
                        cell.setCellStyle(totalStyle);
                    } else if (t == ReportData.ColType.INTEGER) {
                        cell.setCellValue(data.sum(c).doubleValue());
                        cell.setCellStyle(totalStyle);
                    }
                }
            }

            sheet.createFreezePane(0, headerRow + 1);
            if (!data.columns().isEmpty()) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(headerRow, Math.max(headerRow, row - 1),
                        0, data.columns().size() - 1));
            }
            sheet.getPrintSetup().setLandscape(data.columns().size() > 6);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.setFitToPage(true);
            sheet.setZoom(90);
            wb.write(out);
        }
        return target;
    }

    private static void writeCell(Cell cell, Object v, ReportData.ColType type, CellStyle money, CellStyle integer,
                                  CellStyle date, CellStyle dateTime) {
        if (v == null) { cell.setCellValue(""); return; }
        switch (type) {
            case CURRENCY -> {
                BigDecimal d = v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
                cell.setCellValue(d.doubleValue()); cell.setCellStyle(money);
            }
            case INTEGER -> {
                cell.setCellValue(v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()));
                cell.setCellStyle(integer);
            }
            case DATE -> {
                try { cell.setCellValue(java.sql.Date.valueOf(LocalDate.parse(v.toString().substring(0, 10)))); cell.setCellStyle(date); }
                catch (Exception e) { cell.setCellValue(v.toString()); }
            }
            case DATETIME -> {
                try {
                    String s = v.toString().replace(' ', 'T');
                    LocalDateTime dt = LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s);
                    cell.setCellValue(dt); cell.setCellStyle(dateTime);
                } catch (Exception e) { cell.setCellValue(v.toString()); }
            }
            case PERCENT -> {
                cell.setCellValue(v.toString() + "%");
            }
            default -> cell.setCellValue(v.toString());
        }
    }

    private static CellStyle style(Workbook wb, int size, boolean bold, short color, FillPatternType fill) {
        return style(wb, size, bold, color, fill, HorizontalAlignment.LEFT);
    }

    private static CellStyle style(Workbook wb, int size, boolean bold, short color, FillPatternType fill, HorizontalAlignment align) {
        CellStyle s = wb.createCellStyle();
        s.setFont(font(wb, size, bold, color));
        s.setFillPattern(fill);
        s.setAlignment(align);
        return s;
    }

    private static CellStyle numberStyle(Workbook wb, String format, HorizontalAlignment align) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat(format));
        s.setAlignment(align);
        return s;
    }

    private static Font font(Workbook wb, int size, boolean bold, short color) {
        Font f = wb.createFont();
        f.setFontName("Calibri");
        f.setFontHeightInPoints((short) size);
        f.setBold(bold);
        f.setColor(color);
        return f;
    }

    public static String fileName(ReportData data) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return data.key().toLowerCase() + "_" + stamp;
    }

    private static String shorten(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
