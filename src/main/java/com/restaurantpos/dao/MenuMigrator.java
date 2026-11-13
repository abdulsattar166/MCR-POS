package com.restaurantpos.dao;

import com.restaurantpos.util.Images;
import com.restaurantpos.util.MenuAssetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idempotent menu migrator and updater.
 * Replaces old hardcoded/demo menus with the authoritative 5 categories and 115 dishes from the official PDF.
 * Preserves historical orders and transaction records safely.
 */
public final class MenuMigrator {
    private static final Logger log = LoggerFactory.getLogger(MenuMigrator.class);
    public static final int MANIFEST_VERSION = 6;
    private final Database db = Database.get();

    private int currentManifestVersion() {
        try {
            String v = db.queryOne("SELECT value FROM app_meta WHERE key='menu_manifest_version'", rs -> rs.getString(1));
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private void setManifestVersion(int version) {
        db.update("INSERT INTO app_meta(key,value) VALUES('menu_manifest_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                String.valueOf(version));
    }

    public void migrate() {
        log.info("Starting authoritative menu migration to official PDF menu...");

        // 1. Ensure local image assets exist
        try {
            MenuAssetManager.ensureAllAssetsExist();
        } catch (Exception e) {
            log.warn("Warning during asset initialization: {}", e.getMessage());
        }

        int currentVer = currentManifestVersion();
        long existingActiveProducts = 0;
        try {
            existingActiveProducts = db.scalarLong("SELECT COUNT(*) FROM products WHERE active=1");
        } catch (Exception ignored) {}

        // Respect dynamic Menu Management edits across restarts once upgraded to current manifest version
        if (currentVer >= MANIFEST_VERSION && existingActiveProducts > 0) {
            log.info("Menu manifest v{} already applied ({} active products). Preserving dynamic menu edits.",
                    currentVer, existingActiveProducts);
            return;
        }

        // 2. Read manifest
        ManifestData manifest = loadManifest();
        if (manifest == null || manifest.categories.isEmpty() || manifest.products.isEmpty()) {
            throw new com.restaurantpos.exception.AppException("Failed to load menu_manifest.json for migration.");
        }

        boolean upgradingExisting = existingActiveProducts > 0 && currentVer < MANIFEST_VERSION;

        db.txRun(conn -> {
            // 3. Insert or update manifest categories
            Map<String, Long> categoryIds = new HashMap<>();
            for (ManifestCategory mc : manifest.categories) {
                Long existingId = db.queryOne("SELECT id FROM categories WHERE name=? COLLATE NOCASE",
                        rs -> rs.getLong(1), mc.name);
                if (existingId == null) {
                    long id = db.insert("""
                            INSERT INTO categories(name, description, image_path, sort_order, active)
                            VALUES(?, ?, ?, ?, 1)
                            """, mc.name, mc.description, mc.imagePath, mc.sortOrder);
                    categoryIds.put(mc.name.toLowerCase(Locale.ROOT), id);
                } else {
                    if (!upgradingExisting) {
                        db.update("""
                                UPDATE categories SET description=?, image_path=?, sort_order=?, active=1
                                WHERE id=?
                                """, mc.description, mc.imagePath, mc.sortOrder, existingId);
                    }
                    categoryIds.put(mc.name.toLowerCase(Locale.ROOT), existingId);
                }
            }

            if (!upgradingExisting) {
                // Deactivate any categories not in the manifest on initial baseline migration
                db.update("""
                        UPDATE categories SET active=0
                        WHERE name NOT IN (
                            'Chai & Nashta', 'Rice & Drinks', 'BBQ & Fast Food', 'Handi & Salan', 'Signature Dishes',
                            'Parachinari Lobya / Karahi', 'Parachinari Lobya Seekh Karahi', 'Cultural Tikka Seekh / Karahi', 'Parachinari Chawal (Kurrmywaly Wrejy)'
                        )
                        """);
            }

            // 4. Insert or update products
            Set<String> activeSkus = new HashSet<>();
            for (ManifestProduct mp : manifest.products) {
                activeSkus.add(mp.sku.toUpperCase(Locale.ROOT));
                Long catId = categoryIds.get(mp.category.toLowerCase(Locale.ROOT));
                if (catId == null) {
                    throw new IllegalStateException("Category not found for product: " + mp.category);
                }

                // Check by SKU first, then by exact name within category
                Long prodId = db.queryOne("SELECT id FROM products WHERE sku=?", rs -> rs.getLong(1), mp.sku);
                if (prodId == null) {
                    prodId = db.queryOne("SELECT id FROM products WHERE name=? COLLATE NOCASE AND category_id=?", rs -> rs.getLong(1), mp.name, catId);
                }

                if (prodId == null) {
                    prodId = db.insert("""
                            INSERT INTO products(category_id, sku, name, description, image_path, cost, price,
                            prep_minutes, station, available, tax_rate, track_stock, active, created_at)
                            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 1, 1, ?)
                            """, catId, mp.sku, mp.name, mp.description, mp.imagePath,
                            mp.cost, mp.price, mp.prepMinutes, mp.station, LocalDateTime.now());
                } else if (!upgradingExisting || "CAK-001".equalsIgnoreCase(mp.sku) || "PAR-015".equalsIgnoreCase(mp.sku) || "PAK-001".equalsIgnoreCase(mp.sku)) {
                    db.update("""
                            UPDATE products SET category_id=?, sku=?, name=?, description=?, image_path=?,
                            cost=?, price=?, prep_minutes=?, station=?, available=1, active=1
                            WHERE id=?
                            """, catId, mp.sku, mp.name, mp.description, mp.imagePath,
                            mp.cost, mp.price, mp.prepMinutes, mp.station, prodId);
                }

                // Variants
                if (mp.variants != null && !mp.variants.isEmpty()) {
                    for (ManifestVariant mv : mp.variants) {
                        Long varId = db.queryOne("SELECT id FROM product_variants WHERE product_id=? AND name=?",
                                rs -> rs.getLong(1), prodId, mv.name);
                        if (varId == null) {
                            db.insert("""
                                    INSERT INTO product_variants(product_id, name, extra_price, min_price, max_price, price, cost, active)
                                    VALUES(?, ?, ?, ?, ?, ?, 0, 1)
                                    """, prodId, mv.name, mv.extraPrice != null ? mv.extraPrice : BigDecimal.ZERO,
                                    mv.minPrice, mv.maxPrice, mv.price);
                        } else if (!upgradingExisting || "PAK-001".equalsIgnoreCase(mp.sku)) {
                            db.update("""
                                    UPDATE product_variants SET extra_price=?, min_price=?, max_price=?,
                                    price = COALESCE(price, ?), active=1
                                    WHERE id=?
                                    """, mv.extraPrice != null ? mv.extraPrice : BigDecimal.ZERO,
                                    mv.minPrice, mv.maxPrice, mv.price, varId);
                        }
                    }
                }
            }

            if (!upgradingExisting && !activeSkus.isEmpty()) {
                // 5. Safely deactivate legacy non-manifest products during initial migration only
                // (Preserves historical orders referencing them, while future custom products remain active!)
                String placeholders = String.join(",", Collections.nCopies(activeSkus.size(), "?"));
                String deactSql = "UPDATE products SET active=0, available=0 WHERE UPPER(sku) NOT IN (" + placeholders + ")";
                db.update(deactSql, activeSkus.toArray());
            }

            setManifestVersion(MANIFEST_VERSION);
        });

        long activeCats = db.scalarLong("SELECT COUNT(*) FROM categories WHERE active=1");
        long activeProds = db.scalarLong("SELECT COUNT(*) FROM products WHERE active=1");
        log.info("Authoritative menu migration finished successfully: {} categories, {} products active.",
                activeCats, activeProds);
    }

    private ManifestData loadManifest() {
        try (InputStream in = getClass().getResourceAsStream("/menu/menu_manifest.json")) {
            if (in == null) return null;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseManifest(text);
        } catch (Exception e) {
            log.error("Could not parse menu manifest", e);
            return null;
        }
    }

    private ManifestData parseManifest(String json) {
        ManifestData md = new ManifestData();

        // 1. Categories
        int catStart = json.indexOf("\"categories\":");
        int prodStart = json.indexOf("\"products\":");
        if (catStart >= 0 && prodStart > catStart) {
            String catChunk = json.substring(catStart, prodStart);
            Pattern p = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"description\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sortOrder\"\\s*:\\s*(\\d+)\\s*,\\s*\"imagePath\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(catChunk);
            while (m.find()) {
                ManifestCategory mc = new ManifestCategory();
                mc.name = m.group(1);
                mc.description = m.group(2);
                mc.sortOrder = Integer.parseInt(m.group(3));
                mc.imagePath = m.group(4);
                md.categories.add(mc);
            }
        }

        // 2. Products
        if (prodStart >= 0) {
            String prodChunk = json.substring(prodStart);
            // Split product blocks by "category": "
            String[] rawProducts = prodChunk.split("\"category\"\\s*:\\s*\"");
            for (int i = 1; i < rawProducts.length; i++) {
                String block = rawProducts[i];
                ManifestProduct mp = new ManifestProduct();
                int catEnd = block.indexOf('"');
                if (catEnd > 0) {
                    mp.category = block.substring(0, catEnd);
                }
                mp.sku = findString(block, "\"sku\"\\s*:\\s*\"([^\"]+)\"");
                mp.name = findString(block, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                mp.description = findString(block, "\"description\"\\s*:\\s*\"([^\"]+)\"");
                mp.price = new BigDecimal(findNumber(block, "\"price\"\\s*:\\s*([0-9.]+)"));
                mp.cost = new BigDecimal(findNumber(block, "\"cost\"\\s*:\\s*([0-9.]+)"));
                mp.station = findString(block, "\"station\"\\s*:\\s*\"([^\"]+)\"");
                mp.prepMinutes = Integer.parseInt(findNumber(block, "\"prepMinutes\"\\s*:\\s*(\\d+)"));
                mp.imagePath = findString(block, "\"imagePath\"\\s*:\\s*\"([^\"]+)\"");

                // Parse variants if present
                int varIdx = block.indexOf("\"variants\":");
                if (varIdx >= 0) {
                    int varEnd = block.indexOf(']', varIdx);
                    String varChunk = varEnd > varIdx ? block.substring(varIdx, varEnd) : block.substring(varIdx);
                    Pattern objPattern = Pattern.compile("\\{([^}]+)\\}");
                    Matcher om = objPattern.matcher(varChunk);
                    while (om.find()) {
                        String vBlock = om.group(1);
                        ManifestVariant mv = new ManifestVariant();
                        mv.name = findString(vBlock, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                        String extra = findString(vBlock, "\"extraPrice\"\\s*:\\s*([0-9.]+)");
                        mv.extraPrice = extra != null ? new BigDecimal(extra) : BigDecimal.ZERO;
                        String min = findString(vBlock, "\"minPrice\"\\s*:\\s*([0-9.]+)");
                        mv.minPrice = min != null ? new BigDecimal(min) : null;
                        String max = findString(vBlock, "\"maxPrice\"\\s*:\\s*([0-9.]+)");
                        mv.maxPrice = max != null ? new BigDecimal(max) : null;
                        String pr = findString(vBlock, "\"price\"\\s*:\\s*([0-9.]+)");
                        mv.price = pr != null ? new BigDecimal(pr) : null;
                        if (mv.name != null) {
                            mp.variants.add(mv);
                        }
                    }
                }

                if (mp.sku != null && mp.name != null) {
                    md.products.add(mp);
                }
            }
        }

        return md;
    }

    private String findString(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String findNumber(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "0";
    }

    public static class ManifestData {
        public List<ManifestCategory> categories = new ArrayList<>();
        public List<ManifestProduct> products = new ArrayList<>();
    }

    public static class ManifestCategory {
        public String name;
        public String description;
        public int sortOrder;
        public String imagePath;
    }

    public static class ManifestProduct {
        public String category;
        public String sku;
        public String name;
        public String description;
        public BigDecimal price;
        public BigDecimal cost;
        public String station = "Kitchen";
        public int prepMinutes = 10;
        public String imagePath;
        public List<ManifestVariant> variants = new ArrayList<>();
    }

    public static class ManifestVariant {
        public String name;
        public BigDecimal extraPrice = BigDecimal.ZERO;
        public BigDecimal minPrice;
        public BigDecimal maxPrice;
        public BigDecimal price;
    }
}
