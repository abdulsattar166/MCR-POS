package com.restaurantpos.dao;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.exception.AppException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Database {
    private static Database instance;

    private final Path file;
    private final ThreadLocal<Connection> activeTx = new ThreadLocal<>();
    private final ThreadLocal<Connection> cachedConn = new ThreadLocal<>();

    private Database(Path file) {
        this.file = file;
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new AppException("SQLite JDBC driver is unavailable.", e); }
    }

    public static synchronized Database get() {
        if (instance == null) {
            instance = new Database(AppPaths.database());
        }
        return instance;
    }

    public static synchronized void use(Path databaseFile) {
        if (instance != null) instance.closeQuietly();
        instance = new Database(databaseFile);
    }

    public Path file() { return file; }

    private Connection raw() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=8000");
            }
            return c;
        } catch (SQLException e) {
            throw new AppException("Cannot open the local database: " + e.getMessage(), e);
        }
    }

    /** Returns the transaction-bound connection, or a cached thread-local connection. */
    private Connection conn() {
        if (activeTx.get() != null) return activeTx.get();
        try {
            Connection c = cachedConn.get();
            if (c != null && !c.isClosed()) return c;
            c = raw();
            cachedConn.set(c);
            return c;
        } catch (SQLException e) {
            return raw();
        }
    }

    private void release(Connection c) {
        if (c != activeTx.get() && c != cachedConn.get()) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new AppException("Database query failed: " + e.getMessage(), e);
        } finally { release(c); }
    }

    public <T> T queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> list = query(sql, mapper, args);
        return list.isEmpty() ? null : list.get(0);
    }

    public long scalarLong(String sql, Object... args) {
        Long v = queryOne(sql, rs -> rs.getLong(1), args);
        return v == null ? 0 : v;
    }

    public BigDecimal scalarDecimal(String sql, Object... args) {
        BigDecimal v = queryOne(sql, rs -> rs.getBigDecimal(1), args);
        return v == null ? BigDecimal.ZERO : v;
    }

    public int update(String sql, Object... args) {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new AppException("Database update failed: " + e.getMessage(), e);
        } finally { release(c); }
    }

    public long insert(String sql, Object... args) {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new AppException("Database insert failed: " + e.getMessage(), e);
        } finally { release(c); }
    }

    public <T> T tx(TxFunction<T> work) {
        Connection existing = activeTx.get();
        if (existing != null) {
            try { return work.apply(existing); }
            catch (SQLException e) { throw new AppException("Transaction failed: " + e.getMessage(), e); }
        }
        Connection c = raw();
        try {
            c.setAutoCommit(false);
            activeTx.set(c);
            T result = work.apply(c);
            c.commit();
            return result;
        } catch (Exception e) {
            try { c.rollback(); } catch (SQLException ignored) {}
            if (e instanceof AppException ae) throw ae;
            throw new AppException(e.getMessage() == null ? "Transaction failed." : e.getMessage(), e);
        } finally {
            activeTx.remove();
            try { c.setAutoCommit(true); c.close(); } catch (SQLException ignored) {}
        }
    }

    public void txRun(TxVoid work) {
        tx(c -> { work.run(c); return null; });
    }

    public void executeScript(String script) {
        txRun(c -> {
            StringBuilder sb = new StringBuilder();
            for (String line : script.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
                sb.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    try (Statement st = c.createStatement()) {
                        st.execute(sb.toString());
                    }
                    sb.setLength(0);
                }
            }
            if (!sb.toString().isBlank()) {
                try (Statement st = c.createStatement()) { st.execute(sb.toString()); }
            }
        });
    }

    public boolean integrityOk() {
        try {
            String result = queryOne("PRAGMA integrity_check", rs -> rs.getString(1));
            return "ok".equalsIgnoreCase(result);
        } catch (Exception e) { return false; }
    }

    public void checkpoint() {
        try (Connection c = raw(); Statement st = c.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            throw new AppException("Unable to checkpoint the database: " + e.getMessage(), e);
        }
    }

    public void backupTo(Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(target);
            checkpoint();
            String escaped = target.toAbsolutePath().toString().replace("'", "''");
            try (Connection c = raw(); Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + escaped + "'");
            }
        } catch (Exception e) {
            throw new AppException("Database backup failed: " + e.getMessage(), e);
        }
    }

    public void replaceWith(Path source) {
        closeQuietly();
        try {
            Files.copy(source, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(Path.of(file + "-wal"));
            Files.deleteIfExists(Path.of(file + "-shm"));
        } catch (Exception e) {
            throw new AppException("Database restoration failed: " + e.getMessage(), e);
        }
    }

    public void closeQuietly() {
        Connection tx = activeTx.get();
        if (tx != null) { try { tx.close(); } catch (SQLException ignored) {} activeTx.remove(); }
        Connection c = cachedConn.get();
        if (c != null) { try { c.close(); } catch (SQLException ignored) {} cachedConn.remove(); }
    }

    private void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setNull(i + 1, Types.NULL);
            else if (a instanceof BigDecimal bd) ps.setString(i + 1, bd.toPlainString());
            else if (a instanceof Enum<?> e) ps.setString(i + 1, e.name());
            else if (a instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else if (a instanceof java.time.LocalDateTime dt) ps.setString(i + 1, dt.toString());
            else if (a instanceof java.time.LocalDate d) ps.setString(i + 1, d.toString());
            else ps.setObject(i + 1, a);
        }
    }

    @FunctionalInterface public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }
    @FunctionalInterface public interface TxFunction<T> { T apply(Connection c) throws SQLException; }
    @FunctionalInterface public interface TxVoid { void run(Connection c) throws SQLException; }
}
