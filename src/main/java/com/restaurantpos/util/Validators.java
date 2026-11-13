package com.restaurantpos.util;

import com.restaurantpos.exception.AppException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class Validators {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+\\-() ]{7,20}$");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a");
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");

    private Validators() {}

    public static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new AppException(field + " is required.");
        return value.trim();
    }

    public static String max(String value, int max, String field) {
        if (value != null && value.length() > max) throw new AppException(field + " must be at most " + max + " characters.");
        return value;
    }

    public static String email(String value, String field) {
        required(value, field);
        if (!EMAIL.matcher(value.trim()).matches()) throw new AppException(field + " is not a valid email address.");
        return value.trim().toLowerCase();
    }

    public static String phone(String value, String field, boolean optional) {
        if (optional && (value == null || value.isBlank())) return "";
        required(value, field);
        if (!PHONE.matcher(value.trim()).matches()) throw new AppException(field + " is not a valid phone number.");
        return value.trim();
    }

    public static BigDecimal positiveMoney(String value, String field) {
        try {
            BigDecimal d = Money.of(value);
            if (d.signum() <= 0) throw new AppException(field + " must be greater than zero.");
            return d;
        } catch (NumberFormatException e) {
            throw new AppException(field + " must be a valid amount.");
        }
    }

    public static BigDecimal nonNegativeMoney(String value, String field) {
        try {
            BigDecimal d = Money.of(value);
            if (d.signum() < 0) throw new AppException(field + " cannot be negative.");
            return d;
        } catch (NumberFormatException e) {
            throw new AppException(field + " must be a valid amount.");
        }
    }

    public static int positiveInt(String value, String field) {
        try {
            int i = Integer.parseInt(value.trim());
            if (i <= 0) throw new AppException(field + " must be greater than zero.");
            return i;
        } catch (NumberFormatException e) {
            throw new AppException(field + " must be a whole number.");
        }
    }

    public static LocalDate date(String iso, String field) {
        try { return LocalDate.parse(iso); }
        catch (Exception e) { throw new AppException(field + " must be a valid date."); }
    }

    public static LocalDateTime now() { return LocalDateTime.now().withNano(0); }
}
