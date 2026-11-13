package com.restaurantpos.dao;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.security.PasswordHasher;
import com.restaurantpos.util.Images;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SeedData {
    private final Database db = Database.get();

    public void seed() {
        Images.generateLogo();
        seedSettings();
        seedUsers();
        if (db.scalarLong("SELECT COUNT(*) FROM categories WHERE active=1") == 0) seedMenu();
        if (db.scalarLong("SELECT COUNT(*) FROM ingredients") == 0) seedIngredientsAndRecipes();
        if (db.scalarLong("SELECT COUNT(*) FROM floors") == 0) seedFloors();
        if (db.scalarLong("SELECT COUNT(*) FROM suppliers") == 0) seedSuppliers();
        if (db.scalarLong("SELECT COUNT(*) FROM expense_categories") == 0) seedExpenses();
        seedPrinters();
        if (db.scalarLong("SELECT COUNT(*) FROM promotions") == 0) {
            db.insert("INSERT INTO promotions(name,type,value,active) VALUES('Weekend 10%','PERCENT',10,1)");
        }
    }


    private void seedSettings() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("restaurant.name", "Mubarak Cafe & Restaurant");
        s.put("restaurant.address", "Rawalpindi 6th Road, Satellite Town, D-Block");
        s.put("restaurant.phone", "+92 42 3577 8899");
        s.put("restaurant.logo", com.restaurantpos.util.BrandLogo.OFFICIAL_RELATIVE_PATH);
        s.put("restaurant.tagline", "Cafe favourites and flame-grilled classics, made with care.");
        s.put("currency", "PKR");
        s.put("tax.rate", "0");
        s.put("tax.enabled", "true");
        s.put("service.charge.rate", "0");
        s.put("delivery.charge.default", "100");
        s.put("order.prefix", "MB");
        s.put("receipt.footer", "Powered by ZS Sattar");
        s.put("receipt.header", "MUBARAK CAFE & RESTAURANT\nRawalpindi 6th Road, Satellite Town, D-Block\n+92 42 3577 8899");
        s.put("receipt.delay.seconds", "3");
        s.put("receipt.show_qr", "true");
        s.put("session.timeout.minutes", "30");
        s.put("discount.max.percent", "20");
        s.put("refund.cashier.limit", "0");
        s.put("refund.owner.limit", "100000");
        s.put("allow.partial.payment", "false");
        s.put("kitchen.auto.send", "AFTER_PAYMENT");
        s.put("kitchen.delay.minutes", "20");
        s.put("inventory.consumption", "ON_KITCHEN_SEND");
        s.put("inventory.valuation", "WEIGHTED_AVERAGE");
        s.put("backup.auto", "false");
        s.put("google.credentials.path", AppPaths.config().resolve("client_secret.json").toString());
        s.put("google.token.path", AppPaths.config().resolve("drive-token.json").toString());
        s.put("google.drive.folder", "1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH");
        s.put("app.version", "1.0.0");
        s.put("business.hours", "11:00 AM - 12:00 AM");
        s.put("sideorder.types", "TAKEAWAY,SIDE_ORDER,PICKUP,DELIVERY");
        s.put("notification.lowstock", "true");
        s.put("notification.printer", "true");
        s.put("notification.backup", "true");
        for (Map.Entry<String, String> e : s.entrySet()) {
            db.update("INSERT OR IGNORE INTO settings(key,value) VALUES(?,?)", e.getKey(), e.getValue());
        }
        rebrandLegacyDefaults();
    }

    private void rebrandLegacyDefaults() {
        rebrand("restaurant.name", "Ember Grill", "Mubarak Cafe & Restaurant");
        rebrand("restaurant.address", "12-C, Main Boulevard, Gulberg III, Lahore", "Rawalpindi 6th Road, Satellite Town, D-Block");
        rebrand("restaurant.address", "Rawalpindi 6th road Satellite Town D-Block", "Rawalpindi 6th Road, Satellite Town, D-Block");
        rebrand("restaurant.tagline", "Flame-grilled favourites, served fresh.", "Cafe favourites and flame-grilled classics, made with care.");
        rebrand("order.prefix", "EG", "MB");
        rebrand("receipt.header", "EMBER GRILL\n12-C, Main Boulevard, Gulberg III, Lahore\n+92 42 3577 8899",
                "MUBARAK CAFE & RESTAURANT\nRawalpindi 6th Road, Satellite Town, D-Block\n+92 42 3577 8899");
        rebrand("receipt.header", "MUBARAK CAFE & RESTAURANT\n12-C, Main Boulevard, Gulberg III, Lahore\n+92 42 3577 8899",
                "MUBARAK CAFE & RESTAURANT\nRawalpindi 6th Road, Satellite Town, D-Block\n+92 42 3577 8899");
        rebrand("receipt.header", "MUBARAK CAFE & RESTAURANT\nRawalpindi 6th road Satellite Town D-Block\n+92 42 3577 8899",
                "MUBARAK CAFE & RESTAURANT\nRawalpindi 6th Road, Satellite Town, D-Block\n+92 42 3577 8899");
        rebrand("receipt.footer", "Thank you for dining with Ember Grill!\nPlease visit again.",
                "Powered by ZS Sattar");
        rebrand("receipt.footer", "Thank you for dining with Mubarak Cafe & Restaurant!\nWe look forward to welcoming you again.",
                "Powered by ZS Sattar");
        rebrand("google.drive.folder", "1mUE-DOBppqCQDxvE21gzmqBqi_KvIKTQ", "1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH");
        db.update("UPDATE settings SET value=? WHERE key='google.drive.folder' AND (value IS NULL OR TRIM(value)='' OR value='1mUE-DOBppqCQDxvE21gzmqBqi_KvIKTQ')",
                "1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH");
    }

    private void rebrand(String key, String oldValue, String newValue) {
        db.update("UPDATE settings SET value=? WHERE key=? AND value=?", newValue, key, oldValue);
    }

    private void seedUsers() {
        if (db.scalarLong("SELECT COUNT(*) FROM users") == 0) {
            createUser("food_cashier@gmail.com", "cashier@1122", "Front Cashier", "CASHIER", false);
            createUser("food_admin@gmail.com", "admin@1122", "Restaurant Owner", "OWNER", false);
        }
    }

    private void createUser(String username, String password, String name, String role, boolean mustChange) {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt);
        Integer waiterNo = "WAITER".equals(role)
                ? (int) (db.scalarLong("SELECT COALESCE(MAX(waiter_no), 0) FROM users WHERE role='WAITER'") + 1)
                : null;
        db.insert("""
                INSERT INTO users(username,password_hash,salt,full_name,role,active,must_change_password,waiter_no,created_at)
                VALUES(?,?,?,?,?,1,?,?,?)
                """, username, hash, salt, name, role, mustChange, waiterNo, LocalDateTime.now());
    }

    private long category(String name, String description, int order) {
        return db.insert("INSERT INTO categories(name,description,sort_order,active) VALUES(?,?,?,1)", name, description, order);
    }

    private long product(long categoryId, String sku, String name, String description, double cost, double price,
                         String station, int prep, double tax) {
        Images.generateFoodImage(sku, name, categoryName(categoryId), (int) (Math.abs(sku.hashCode()) % 50));
        return db.insert("""
                INSERT INTO products(category_id,sku,name,description,image_path,cost,price,prep_minutes,station,available,
                tax_rate,track_stock,active,created_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,1,1,?)
                """, categoryId, sku, name, description, Images.foodRelative(sku), cost, price, prep, station, tax, LocalDateTime.now());
    }

    private String categoryName(long id) {
        String n = db.queryOne("SELECT name FROM categories WHERE id=?", rs -> rs.getString(1), id);
        return n == null ? "Menu" : n;
    }

    private void seedMenu() {
        new MenuMigrator().migrate();
        seedIngredientsAndRecipes();
    }

    private void seedIngredientsAndRecipes() {
        if (db.scalarLong("SELECT COUNT(*) FROM ingredients") > 0) return;

        Map<String, Long> ing = new LinkedHashMap<>();
        ing.put("MILK", ingredient("ING-001", "Fresh Milk", "L", 150, 20, 300, 180));
        ing.put("CHICKEN_FILLET", ingredient("ING-002", "Chicken Fillet", "pcs", 140, 30, 300, 120));
        ing.put("TEA", ingredient("ING-003", "Tea Leaves (Patti)", "kg", 40, 5, 80, 850));
        ing.put("SUGAR", ingredient("ING-004", "Sugar", "kg", 80, 10, 150, 140));
        ing.put("FLOUR", ingredient("ING-005", "Atta / Maida Flour", "kg", 120, 20, 250, 110));
        ing.put("GHEE", ingredient("ING-006", "Desi Ghee / Butter", "kg", 50, 10, 100, 1600));
        ing.put("OIL", ingredient("ING-007", "Cooking Oil", "L", 80, 15, 150, 450));
        ing.put("BEEF", ingredient("ING-008", "Fresh Beef", "kg", 90, 20, 200, 950));
        ing.put("RICE", ingredient("ING-009", "Basmati Rice", "kg", 150, 25, 300, 280));
        ing.put("POTATO", ingredient("ING-010", "Potatoes (Aloo)", "kg", 110, 20, 200, 80));
        ing.put("ONION", ingredient("ING-011", "Onions & Tomatoes", "kg", 95, 20, 180, 120));
        ing.put("LOBYA", ingredient("ING-012", "Parachinari Red Beans (Lobya)", "kg", 75, 15, 150, 320));
        ing.put("EGG", ingredient("ING-013", "Farm Eggs", "pcs", 360, 50, 600, 25));
        ing.put("BUN", ingredient("ING-014", "Burger Buns", "pcs", 120, 20, 200, 30));
        ing.put("PACK", ingredient("ING-015", "Food Packaging Box", "pcs", 500, 80, 1000, 15));
        ing.put("CHICKEN", ingredient("ING-016", "Fresh Chicken Meat", "kg", 140, 30, 300, 420));

        Long chai = db.queryOne("SELECT id FROM products WHERE sku='TEA-001'", rs -> rs.getLong(1));
        if (chai != null) {
            recipe(chai, ing.get("MILK"), "0.15", "L");
            recipe(chai, ing.get("TEA"), "0.01", "kg");
            recipe(chai, ing.get("SUGAR"), "0.015", "kg");
        }
        Long zinger = db.queryOne("SELECT id FROM products WHERE sku='BUR-001'", rs -> rs.getLong(1));
        if (zinger != null) {
            recipe(zinger, ing.get("BUN"), "1", "pcs");
            recipe(zinger, ing.get("CHICKEN_FILLET"), "1", "pcs");
            recipe(zinger, ing.get("OIL"), "0.05", "L");
            recipe(zinger, ing.get("PACK"), "1", "pcs");
        }
        Long biryani = db.queryOne("SELECT id FROM products WHERE sku='RCE-003'", rs -> rs.getLong(1));
        if (biryani != null) {
            recipe(biryani, ing.get("RICE"), "0.20", "kg");
            recipe(biryani, ing.get("CHICKEN"), "0.12", "kg");
            recipe(biryani, ing.get("POTATO"), "0.08", "kg");
            recipe(biryani, ing.get("PACK"), "1", "pcs");
        }
        Long lobya = db.queryOne("SELECT id FROM products WHERE sku='SIG-001'", rs -> rs.getLong(1));
        if (lobya != null) {
            recipe(lobya, ing.get("LOBYA"), "0.20", "kg");
            recipe(lobya, ing.get("OIL"), "0.04", "L");
            recipe(lobya, ing.get("ONION"), "0.05", "kg");
        }
    }

    private void linkAddon(long productId, long addonId) {
        db.update("INSERT OR IGNORE INTO product_addons(product_id,addon_id) VALUES(?,?)", productId, addonId);
    }

    private long ingredient(String sku, String name, String unit, double stock, double min, double max, double cost) {
        return db.insert("INSERT INTO ingredients(sku,name,unit,stock_qty,min_qty,max_qty,avg_cost,active) VALUES(?,?,?,?,?,?,?,1)",
                sku, name, unit, stock, min, max, cost);
    }

    private void recipe(long productId, long ingredientId, String qty, String unit) {
        db.update("INSERT OR REPLACE INTO recipes(product_id,variant_id,ingredient_id,qty,unit) VALUES(?,NULL,?,?,?)",
                productId, ingredientId, new BigDecimal(qty), unit);
    }

    private void seedFloors() {
        long ground = db.insert("INSERT INTO floors(name,description,width,height,active,sort_order) VALUES(?,?,?,?,1,1)",
                "Main Dining Floor", "Main dining area with 8 seating tables", 960, 560);
        long main = db.insert("INSERT INTO sections(floor_id,name) VALUES(?,?)", ground, "Main Hall");

        int[] capacities = {4, 2, 4, 2, 4, 2, 4, 2};
        for (int i = 0; i < 8; i++) {
            int col = i % 4;
            int row = i / 4;
            int cap = capacities[i];
            String shape = cap == 4 ? "RECTANGLE" : "ROUND";
            double x = 80 + col * 210.0;
            double y = 110 + row * 210.0;
            double w = cap == 4 ? 130 : 95;
            double h = 95;
            table(ground, main, "Table " + (i + 1), shape, cap, x, y, w, h);
        }
    }

    private void table(long floor, long section, String no, String shape, int cap, double x, double y, double w, double h) {
        db.insert("""
                INSERT INTO dining_tables(floor_id,section_id,table_no,shape,capacity,x,y,width,height,rotation,status,active)
                VALUES(?,?,?,?,?,?,?,?,?,0,'AVAILABLE',1)
                """, floor, section, no, shape, cap, x, y, w, h);
    }

    private void seedSuppliers() {
        db.insert("INSERT INTO suppliers(name,contact,phone,email,address,terms,active) VALUES(?,?,?,?,?,?,1)",
                "Metro Cash & Carry", "Imran Ali", "+92 300 1234567", "orders@metro.example", "Thokar Niaz Baig, Lahore", "Net 15");
        db.insert("INSERT INTO suppliers(name,contact,phone,email,address,terms,active) VALUES(?,?,?,?,?,?,1)",
                "Fresh Farm Poultry", "Sajid Hussain", "+92 321 7654321", "sales@freshfarm.example", "Multan Road, Lahore", "Cash on delivery");
        db.insert("INSERT INTO suppliers(name,contact,phone,email,address,terms,active) VALUES(?,?,?,?,?,?,1)",
                "Al-Habib Packaging", "Bilal Ahmed", "+92 333 5551212", "info@alhabib.example", "Badami Bagh, Lahore", "Net 7");
    }

    private void seedExpenses() {
        for (String name : new String[]{"Rent", "Electricity & Gas", "Salaries & Wages", "Internet & Telephone",
                "Transport & Delivery", "Cleaning Supplies", "Equipment Maintenance", "Marketing",
                "Packaging", "Miscellaneous"}) {
            db.update("INSERT OR IGNORE INTO expense_categories(name) VALUES(?)", name);
        }
    }

    private void seedPrinters() {
        if (db.scalarLong("SELECT COUNT(*) FROM printer_configs") > 0) return;
        db.insert("INSERT INTO printer_configs(name,role,system_printer,paper_width,cut_enabled,active) VALUES('Cashier Receipt Printer','CASHIER','',80,1,1)");
        db.insert("INSERT INTO printer_configs(name,role,system_printer,paper_width,cut_enabled,active) VALUES('Kitchen Ticket Printer','KITCHEN','',80,0,1)");
    }
}
