package com.restaurantpos.reporting;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public record ReportData(String key, String title, String period, List<Column> columns, List<Object[]> rows) {

    public enum ColType { TEXT, INTEGER, CURRENCY, DATE, DATETIME, PERCENT }

    public record Column(String label, ColType type, int width) {
        public static Column text(String label, int width) { return new Column(label, ColType.TEXT, width); }
        public static Column integer(String label, int width) { return new Column(label, ColType.INTEGER, width); }
        public static Column money(String label, int width) { return new Column(label, ColType.CURRENCY, width); }
        public static Column date(String label, int width) { return new Column(label, ColType.DATE, width); }
        public static Column dateTime(String label, int width) { return new Column(label, ColType.DATETIME, width); }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
    private static final NumberFormat MONEY = NumberFormat.getNumberInstance(Locale.US);
    static { MONEY.setMinimumFractionDigits(2); MONEY.setMaximumFractionDigits(2); }

    public static String format(Object value, ColType type) {
        if (value == null) return "";
        return switch (type) {
            case CURRENCY -> "Rs " + MONEY.format(value instanceof BigDecimal b ? b : new BigDecimal(value.toString()));
            case INTEGER -> String.valueOf(value);
            case PERCENT -> MONEY.format(value) + "%";
            case DATE -> {
                String s = value.toString();
                try { yield java.time.LocalDate.parse(s.substring(0, Math.min(10, s.length()))).format(DATE); }
                catch (Exception e) { yield s; }
            }
            case DATETIME -> {
                String s = value.toString();
                try {
                    if (s.length() >= 16) yield java.time.LocalDateTime.parse(s.replace(' ', 'T').substring(0, 16)).format(DATETIME);
                    yield s;
                } catch (Exception e) { yield s; }
            }
            default -> value.toString();
        };
    }

    public BigDecimal sum(int col) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : rows) {
            if (row.length > col && row[col] instanceof BigDecimal b) total = total.add(b);
            else if (row.length > col && row[col] instanceof Number n) total = total.add(BigDecimal.valueOf(n.longValue()));
        }
        return total;
    }
}
