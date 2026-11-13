package com.restaurantpos.dao;

import com.restaurantpos.model.SystemModels.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SystemRepository {
    private final Database db = Database.get();

    // ---------- settings ----------
    public String setting(String key, String fallback) {
        String v = db.queryOne("SELECT value FROM settings WHERE key=?", rs -> rs.getString(1), key);
        return v == null ? fallback : v;
    }

    public void setSetting(String key, String value) {
        db.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    public Map<String, String> allSettings() {
        Map<String, String> map = new LinkedHashMap<>();
        db.query("SELECT key,value FROM settings ORDER BY key", rs -> map.put(rs.getString(1), rs.getString(2)));
        return map;
    }

    public BigDecimal decimalSetting(String key, BigDecimal fallback) {
        String v = setting(key, null);
        if (v == null || v.isBlank()) return fallback;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return fallback; }
    }

    public int intSetting(String key, int fallback) {
        try { return Integer.parseInt(setting(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    // ---------- audit ----------
    public void audit(Long userId, String username, String action, String entity, Long entityId, String details) {
        db.update("INSERT INTO audit_log(user_id,username,action,entity,entity_id,details,created_at) VALUES(?,?,?,?,?,?,?)",
                userId, username, action, entity, entityId, details, LocalDateTime.now());
    }

    public List<AuditEvent> auditEvents(int limit, String search) {
        if (search == null || search.isBlank()) {
            return db.query("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", this::mapAudit, limit);
        }
        String q = "%" + search + "%";
        return db.query("""
                SELECT * FROM audit_log WHERE action LIKE ? OR username LIKE ? OR entity LIKE ? OR details LIKE ?
                ORDER BY id DESC LIMIT ?
                """, this::mapAudit, q, q, q, q, limit);
    }

    private AuditEvent mapAudit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditEvent(rs.getLong("id"), Rows.longOrNull(rs, "user_id"), rs.getString("username"),
                rs.getString("action"), rs.getString("entity"), Rows.longOrNull(rs, "entity_id"),
                rs.getString("details"), Rows.dt(rs, "created_at"));
    }

    // ---------- notifications ----------
    public void notify(String type, String title, String message, String severity) {
        db.update("INSERT INTO notifications(type,title,message,severity,is_read,created_at) VALUES(?,?,?,?,0,?)",
                type, title, message, severity, LocalDateTime.now());
    }

    public List<Notification> notifications(int limit, boolean unreadOnly) {
        String sql = "SELECT * FROM notifications" + (unreadOnly ? " WHERE is_read=0" : "") + " ORDER BY id DESC LIMIT ?";
        return db.query(sql, rs -> new Notification(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
                rs.getString("message"), rs.getString("severity"), Rows.bool(rs, "is_read"), Rows.dt(rs, "created_at")), limit);
    }

    public int unreadCount() {
        return (int) db.scalarLong("SELECT COUNT(*) FROM notifications WHERE is_read=0");
    }

    public void markRead(long id) { db.update("UPDATE notifications SET is_read=1 WHERE id=?", id); }
    public void markAllRead() { db.update("UPDATE notifications SET is_read=1 WHERE is_read=0"); }

    // ---------- printing ----------
    public long createPrintJob(String sequenceId, long orderId, String kind, String printer, String status, String error,
                               boolean reprint, Long userId) {
        return db.insert("""
                INSERT INTO print_jobs(sequence_id,order_id,kind,printer,status,error,reprint,user_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, sequenceId, orderId, kind, printer, status, error, reprint, userId, LocalDateTime.now());
    }

    public void updatePrintJob(long id, String status, String error) {
        db.update("UPDATE print_jobs SET status=?, error=?, printed_at=? WHERE id=?",
                status, error, "SUCCESS".equals(status) ? LocalDateTime.now() : null, id);
    }

    public List<PrintJob> printJobs(long orderId) {
        return db.query("""
                SELECT pj.*, o.order_no, u.full_name AS user_name FROM print_jobs pj
                JOIN orders o ON o.id = pj.order_id
                LEFT JOIN users u ON u.id = pj.user_id
                WHERE pj.order_id=? ORDER BY pj.id DESC
                """, rs -> new PrintJob(rs.getLong("id"), rs.getString("sequence_id"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("kind"), rs.getString("printer"), rs.getString("status"),
                rs.getString("error"), Rows.bool(rs, "reprint"), rs.getString("user_name"),
                Rows.dt(rs, "created_at"), Rows.dt(rs, "printed_at")), orderId);
    }

    public List<PrintJob> recentPrintJobs(int limit) {
        return db.query("""
                SELECT pj.*, o.order_no, u.full_name AS user_name FROM print_jobs pj
                JOIN orders o ON o.id = pj.order_id LEFT JOIN users u ON u.id = pj.user_id
                ORDER BY pj.id DESC LIMIT ?
                """, rs -> new PrintJob(rs.getLong("id"), rs.getString("sequence_id"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getString("kind"), rs.getString("printer"), rs.getString("status"),
                rs.getString("error"), Rows.bool(rs, "reprint"), rs.getString("user_name"),
                Rows.dt(rs, "created_at"), Rows.dt(rs, "printed_at")), limit);
    }
    public List<String> printerNames() {
        return db.query("SELECT name FROM printer_configs ORDER BY role, name", rs -> rs.getString(1));
    }

    public String printerForRole(String role) {
        String v = db.queryOne("SELECT system_printer FROM printer_configs WHERE role=? AND active=1 ORDER BY id LIMIT 1",
                rs -> rs.getString(1), role);
        return v == null ? "" : v;
    }

    public boolean printerCutForRole(String role) {
        Long v = db.queryOne("SELECT cut_enabled FROM printer_configs WHERE role=? AND active=1 ORDER BY id LIMIT 1",
                rs -> rs.getLong(1), role);
        return v != null && v != 0;
    }

    public void savePrinterConfig(String name, String role, String systemPrinter) {
        savePrinterConfig(name, role, systemPrinter, true);
    }

    public void savePrinterConfig(String name, String role, String systemPrinter, boolean cut) {
        long exists = db.scalarLong("SELECT COUNT(*) FROM printer_configs WHERE role=?", role);
        if (exists > 0) {
            db.update("UPDATE printer_configs SET name=?, system_printer=?, cut_enabled=? WHERE role=?", name, systemPrinter, cut ? 1 : 0, role);
        } else {
            db.insert("INSERT INTO printer_configs(name,role,system_printer,cut_enabled) VALUES(?,?,?,?)", name, role, systemPrinter, cut ? 1 : 0);
        }
    }

    // ---------- backups ----------
    public long recordBackup(String path, long size, String checksum, String status, String driveStatus, String details) {
        return db.insert("INSERT INTO backup_history(path,size,checksum,status,drive_status,details,created_at) VALUES(?,?,?,?,?,?,?)",
                path, size, checksum, status, driveStatus, details, LocalDateTime.now());
    }

    public List<BackupRecord> backups(int limit) {
        return db.query("SELECT * FROM backup_history ORDER BY id DESC LIMIT ?",
                rs -> new BackupRecord(rs.getLong("id"), rs.getString("path"), rs.getLong("size"),
                        rs.getString("checksum"), rs.getString("status"), rs.getString("drive_status"),
                        rs.getString("details"), Rows.dt(rs, "created_at")), limit);
    }

    public void updateBackupDrive(long id, String driveStatus, String details) {
        db.update("UPDATE backup_history SET drive_status=?, details=? WHERE id=?", driveStatus, details, id);
    }

    // ---------- promotions ----------
    public record Promotion(long id, String name, String type, BigDecimal value, String startAt, String endAt, boolean active) {}

    public List<Promotion> promotions() {
        return db.query("SELECT * FROM promotions ORDER BY id DESC", rs -> new Promotion(rs.getLong("id"),
                rs.getString("name"), rs.getString("type"), Rows.bd(rs, "value"), rs.getString("start_at"),
                rs.getString("end_at"), Rows.bool(rs, "active")));
    }

    public void addPromotion(String name, String type, BigDecimal value) {
        db.insert("INSERT INTO promotions(name,type,value,active) VALUES(?,?,?,1)", name, type, value);
    }
}
