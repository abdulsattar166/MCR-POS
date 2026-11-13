package com.restaurantpos.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

public final class Money {
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Locale PK = Locale.forLanguageTag("en-PK");

    private Money() {}

    public static BigDecimal of(String v) {
        if (v == null || v.isBlank()) return ZERO;
        return new BigDecimal(v.trim()).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }

    public static BigDecimal nz(BigDecimal v) { return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP); }

    public static BigDecimal add(BigDecimal a, BigDecimal b) { return nz(a).add(nz(b)); }
    public static BigDecimal sub(BigDecimal a, BigDecimal b) { return nz(a).subtract(nz(b)); }
    public static BigDecimal mul(BigDecimal a, BigDecimal b) { return nz(a).multiply(nz(b)).setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        if (b == null || b.compareTo(BigDecimal.ZERO) == 0) return ZERO;
        return nz(a).divide(b, 4, RoundingMode.HALF_UP);
    }
    public static BigDecimal percent(BigDecimal base, BigDecimal pct) { return mul(base, div(pct, BigDecimal.valueOf(100))); }

    public static BigDecimal roundCash(BigDecimal value) {
        return nz(value).setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    public static String fmt(BigDecimal v) {
        NumberFormat f = NumberFormat.getCurrencyInstance(PK);
        f.setCurrency(java.util.Currency.getInstance("PKR"));
        return f.format(nz(v));
    }

    public static String plain(BigDecimal v) { return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    public static BigDecimal maxZero(BigDecimal v) { return nz(v).signum() < 0 ? ZERO : nz(v); }
}
