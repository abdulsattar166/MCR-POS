package com.restaurantpos.dao;

import com.restaurantpos.enums.Role;
import com.restaurantpos.model.SystemModels.User;

import java.time.LocalDateTime;
import java.util.List;

public final class UserRepository {
    private final Database db = Database.get();

    public record AuthRecord(User user, String hash, String salt) {}
    public record LoginAttempt(long id, String username, boolean success, String detail, LocalDateTime createdAt) {}

    private User map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Role role;
        try {
            role = Role.valueOf(rs.getString("role"));
        } catch (Exception e) {
            role = Role.CASHIER;
        }
        return new User(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"),
                role, Rows.bool(rs, "active"), Rows.bool(rs, "must_change_password"),
                rs.getString("photo_path"), Rows.dt(rs, "created_at"), Rows.dt(rs, "last_login"), null);
    }

    public AuthRecord findAuth(String username) {
        return db.queryOne("SELECT * FROM users WHERE username = ? COLLATE NOCASE",
                rs -> new AuthRecord(map(rs), rs.getString("password_hash"), rs.getString("salt")), username);
    }

    public User findById(long id) {
        return db.queryOne("SELECT * FROM users WHERE id = ?", rs -> map(rs), id);
    }

    public List<User> all() {
        return db.query("SELECT * FROM users WHERE role NOT IN ('WAITER','KITCHEN') ORDER BY full_name", this::map);
    }

    public List<User> byRole(Role role) {
        return db.query("SELECT * FROM users WHERE role = ? AND active = 1 ORDER BY full_name", this::map, role.name());
    }

    public long create(String username, String hash, String salt, String fullName, Role role,
                       boolean active, boolean mustChange, String photoPath) {
        return db.insert("""
                INSERT INTO users(username,password_hash,salt,full_name,role,active,must_change_password,photo_path,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, username, hash, salt, fullName, role.name(), active, mustChange, photoPath, LocalDateTime.now());
    }

    public void update(long id, String fullName, Role role, boolean active, String photoPath) {
        db.update("UPDATE users SET full_name=?, role=?, active=?, photo_path=? WHERE id=?",
                fullName, role.name(), active, photoPath, id);
    }

    public void setPassword(long id, String hash, String salt, boolean mustChange) {
        db.update("UPDATE users SET password_hash=?, salt=?, must_change_password=? WHERE id=?",
                hash, salt, mustChange, id);
    }

    public void setActive(long id, boolean active) {
        db.update("UPDATE users SET active=? WHERE id=?", active, id);
    }

    public void touchLogin(long id) {
        db.update("UPDATE users SET last_login=? WHERE id=?", LocalDateTime.now(), id);
    }

    public void logAttempt(String username, boolean success, String detail) {
        db.update("INSERT INTO login_activity(username,success,detail,created_at) VALUES(?,?,?,?)",
                username, success, detail, LocalDateTime.now());
    }

    public List<LoginAttempt> recentAttempts(int limit) {
        return db.query("SELECT * FROM login_activity ORDER BY id DESC LIMIT ?",
                rs -> new LoginAttempt(rs.getLong("id"), rs.getString("username"), Rows.bool(rs, "success"),
                        rs.getString("detail"), Rows.dt(rs, "created_at")), limit);
    }

    public boolean usernameExists(String username) {
        return db.scalarLong("SELECT COUNT(*) FROM users WHERE username = ? COLLATE NOCASE", username) > 0;
    }

    public long count() { return db.scalarLong("SELECT COUNT(*) FROM users"); }
}
