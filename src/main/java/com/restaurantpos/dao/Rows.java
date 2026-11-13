package com.restaurantpos.dao;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

final class Rows {
    private Rows() {}

    static LocalDateTime dt(String s) { return s == null || s.isBlank() ? null : LocalDateTime.parse(s); }
    static LocalDate date(String s) { return s == null || s.isBlank() ? null : LocalDate.parse(s); }
    static LocalDateTime dt(ResultSet rs, String col) throws SQLException { return dt(rs.getString(col)); }
    static LocalDate date(ResultSet rs, String col) throws SQLException { return date(rs.getString(col)); }
    static BigDecimal bd(ResultSet rs, String col) throws SQLException {
        String v = rs.getString(col);
        return v == null || v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v);
    }
    static BigDecimal bdNullable(ResultSet rs, String col) throws SQLException {
        String v = rs.getString(col);
        return v == null || v.isBlank() ? null : new BigDecimal(v);
    }
    static boolean bool(ResultSet rs, String col) throws SQLException { return rs.getInt(col) != 0; }
    static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
    static String str(ResultSet rs, String col) throws SQLException { return rs.getString(col); }
}
