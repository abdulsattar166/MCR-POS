package com.restaurantpos.dao;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.exception.AppException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

public final class SchemaManager {
    private final Database db = Database.get();

    public void migrate() {
        db.executeScript("""
                CREATE TABLE IF NOT EXISTS app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                """);
        int current = version();
        if (current < 1) {
            db.executeScript(loadSchema());
            setVersion(1);
        }
        if (!hasColumn("users", "waiter_no")) {
            db.update("ALTER TABLE users ADD COLUMN waiter_no INTEGER");
        }
        if (!hasColumn("orders", "waiter_id")) {
            db.update("ALTER TABLE orders ADD COLUMN waiter_id INTEGER REFERENCES users(id) ON DELETE SET NULL");
        }
        if (!hasColumn("orders", "credit_amount")) {
            db.update("ALTER TABLE orders ADD COLUMN credit_amount NUMERIC NOT NULL DEFAULT 0");
        }
        if (!hasColumn("product_variants", "min_price")) {
            db.update("ALTER TABLE product_variants ADD COLUMN min_price NUMERIC");
        }
        if (!hasColumn("product_variants", "max_price")) {
            db.update("ALTER TABLE product_variants ADD COLUMN max_price NUMERIC");
        }
        if (!hasColumn("product_variants", "price")) {
            db.update("ALTER TABLE product_variants ADD COLUMN price NUMERIC");
        }
        if (!hasColumn("shifts", "shift_name")) {
            db.update("ALTER TABLE shifts ADD COLUMN shift_name TEXT");
        }
        if (!hasColumn("shifts", "scheduled_start")) {
            db.update("ALTER TABLE shifts ADD COLUMN scheduled_start TEXT");
        }
        if (!hasColumn("shifts", "scheduled_end")) {
            db.update("ALTER TABLE shifts ADD COLUMN scheduled_end TEXT");
        }
        db.executeScript("""
                CREATE TABLE IF NOT EXISTS shift_schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    end_time TEXT NOT NULL,
                    working_days TEXT NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI,SAT,SUN',
                    grace_minutes INTEGER NOT NULL DEFAULT 15,
                    active INTEGER NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS customer_ledger (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
                    order_id INTEGER REFERENCES orders(id) ON DELETE RESTRICT,
                    entry_type TEXT NOT NULL,
                    amount NUMERIC NOT NULL,
                    signed_delta NUMERIC NOT NULL,
                    balance_after NUMERIC NOT NULL,
                    payment_method TEXT,
                    reference TEXT,
                    description TEXT,
                    staff_id INTEGER NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
                    shift_id INTEGER REFERENCES shifts(id) ON DELETE RESTRICT,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_ledger_customer ON customer_ledger(customer_id);
                CREATE INDEX IF NOT EXISTS idx_ledger_order ON customer_ledger(order_id);
                CREATE INDEX IF NOT EXISTS idx_customers_name ON customers(name);
                CREATE INDEX IF NOT EXISTS idx_customers_phone ON customers(phone);
                CREATE INDEX IF NOT EXISTS idx_product_variants_prod ON product_variants(product_id);
                CREATE INDEX IF NOT EXISTS idx_product_addons_prod ON product_addons(product_id);
                CREATE INDEX IF NOT EXISTS idx_products_active_avail ON products(active, available);
                CREATE INDEX IF NOT EXISTS idx_products_sku ON products(sku);
                CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items(product_id);
                CREATE INDEX IF NOT EXISTS idx_recipes_product ON recipes(product_id);
                CREATE INDEX IF NOT EXISTS idx_shifts_user_status ON shifts(user_id, status);
                CREATE INDEX IF NOT EXISTS idx_payments_shift ON payments(shift_id);
                CREATE INDEX IF NOT EXISTS idx_payments_order ON payments(order_id);
                """);

        // Safe v6 migration: deactivate legacy waiter/kitchen accounts, clear forced password change, standardize address
        if (current < 6) {
            db.update("UPDATE users SET active=0 WHERE role IN ('WAITER', 'KITCHEN')");
            db.update("UPDATE users SET must_change_password=0 WHERE role='OWNER'");
            db.update("UPDATE settings SET value='Rawalpindi 6th Road, Satellite Town, D-Block' WHERE key='restaurant.address' AND value='Rawalpindi 6th road Satellite Town D-Block'");
            setVersion(6);
        }

        // Assign stable sequential waiter_no for waiters without one
        assignStableWaiterNumbers();

        // Self-heal any historical unlinked payments
        try {
            db.update("""
                    UPDATE payments SET shift_id = (
                        SELECT s.id FROM shifts s
                        WHERE s.user_id = payments.created_by
                          AND s.opened_at <= payments.created_at
                          AND (s.closed_at IS NULL OR s.closed_at >= payments.created_at)
                        ORDER BY s.id DESC LIMIT 1
                    )
                    WHERE shift_id IS NULL AND created_by IS NOT NULL;
                    """);
        } catch (Exception ignored) {}

        if (current < 5) {
            migrateToV5();
            setVersion(5);
        }
        new MenuMigrator().migrate();
        assignStableWaiterNumbers();
        if (!db.integrityOk()) {
            throw new AppException("The local database failed its integrity check. Restore a verified backup before continuing.");
        }
    }

    private void assignStableWaiterNumbers() {
        try {
            java.util.List<Long> unnumbered = db.query(
                    "SELECT id FROM users WHERE role='WAITER' AND (waiter_no IS NULL OR waiter_no <= 0) ORDER BY id",
                    rs -> rs.getLong(1));
            if (unnumbered.isEmpty()) return;
            long maxNo = db.scalarLong("SELECT COALESCE(MAX(waiter_no), 0) FROM users WHERE role='WAITER'");
            for (Long id : unnumbered) {
                maxNo++;
                db.update("UPDATE users SET waiter_no=? WHERE id=?", maxNo, id);
            }
        } catch (Exception ignored) {}
    }

    private void migrateToV5() {
        // 1. Update restaurant location across settings & headers
        try {
            String newAddress = "Rawalpindi 6th road Satellite Town D-Block";
            db.update("""
                    UPDATE settings SET value=?
                    WHERE key='restaurant.address' AND (value IS NULL OR TRIM(value)='' OR value LIKE '%Gulberg%' OR value LIKE '%Lahore%')
                    """, newAddress);
            db.update("INSERT OR IGNORE INTO settings(key,value) VALUES('restaurant.address', ?)", newAddress);

            String header = db.queryOne("SELECT value FROM settings WHERE key='receipt.header'", rs -> rs.getString(1));
            if (header != null && (header.contains("Gulberg") || header.contains("Lahore"))) {
                String updatedHeader = header.replace("12-C, Main Boulevard, Gulberg III, Lahore", newAddress);
                db.update("UPDATE settings SET value=? WHERE key='receipt.header'", updatedHeader);
            }
        } catch (Exception ignored) {}

        // 2. Enforce exact 8-table layout on existing databases while preserving historical orders
        try {
            long floorCount = db.scalarLong("SELECT COUNT(*) FROM floors");
            if (floorCount > 0) {
                Long primaryFloor = db.queryOne("SELECT id FROM floors WHERE active=1 ORDER BY sort_order, id LIMIT 1", rs -> rs.getLong(1));
                if (primaryFloor == null) {
                    primaryFloor = db.queryOne("SELECT id FROM floors ORDER BY id LIMIT 1", rs -> rs.getLong(1));
                }
                if (primaryFloor != null) {
                    db.update("UPDATE floors SET active=CASE WHEN id=? THEN 1 ELSE 0 END", primaryFloor);
                    Long mainSection = db.queryOne("SELECT id FROM sections WHERE floor_id=? ORDER BY id LIMIT 1", rs -> rs.getLong(1), primaryFloor);

                    // Deactivate all tables outside the 8 physical tables (preserves historical order foreign keys!)
                    db.update("""
                            UPDATE dining_tables SET active=0
                            WHERE table_no NOT IN ('Table 1','Table 2','Table 3','Table 4','Table 5','Table 6','Table 7','Table 8')
                            """);

                    int[] capacities = {4, 2, 4, 2, 4, 2, 4, 2};
                    for (int i = 0; i < 8; i++) {
                        String tNo = "Table " + (i + 1);
                        int cap = capacities[i];
                        String shape = cap == 4 ? "RECTANGLE" : "ROUND";
                        int col = i % 4;
                        int row = i / 4;
                        double x = 80 + col * 210.0;
                        double y = 110 + row * 210.0;
                        double w = cap == 4 ? 130 : 95;
                        double h = 95;
                        Long existingId = db.queryOne("SELECT id FROM dining_tables WHERE table_no=?", rs -> rs.getLong(1), tNo);
                        if (existingId == null) {
                            db.insert("""
                                    INSERT INTO dining_tables(floor_id,section_id,table_no,shape,capacity,x,y,width,height,rotation,status,active)
                                    VALUES(?,?,?,?,?,?,?,?,?,0,'AVAILABLE',1)
                                    """, primaryFloor, mainSection, tNo, shape, cap, x, y, w, h);
                        } else {
                            db.update("""
                                    UPDATE dining_tables SET floor_id=?, capacity=?, shape=?, x=?, y=?, width=?, height=?, active=1
                                    WHERE id=?
                                    """, primaryFloor, cap, shape, x, y, w, h, existingId);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public boolean hasColumn(String table, String column) {
        try {
            return db.query("PRAGMA table_info(" + table + ")", rs -> rs.getString("name"))
                    .stream().anyMatch(c -> c.equalsIgnoreCase(column));
        } catch (Exception e) {
            return false;
        }
    }

    public int version() {
        try {
            String v = db.queryOne("SELECT value FROM app_meta WHERE key='schema_version'", rs -> rs.getString(1));
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) { return 0; }
    }

    private void setVersion(int v) {
        db.update("INSERT INTO app_meta(key,value) VALUES('schema_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                String.valueOf(v));
        db.update("INSERT OR IGNORE INTO app_meta(key,value) VALUES('created_at',?)", LocalDateTime.now().toString());
    }

    private String loadSchema() {
        try (InputStream in = getClass().getResourceAsStream("/db/schema.sql")) {
            if (in == null) throw new AppException("Database schema resource is missing.");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AppException("Could not load the database schema: " + e.getMessage(), e);
        }
    }

    public void verify() {
        if (!java.nio.file.Files.exists(AppPaths.database())) {
            throw new AppException("Local database file is missing.");
        }
        if (!db.integrityOk()) throw new AppException("Database integrity check failed.");
    }
}
