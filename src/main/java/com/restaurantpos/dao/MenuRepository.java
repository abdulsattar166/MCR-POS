package com.restaurantpos.dao;

import com.restaurantpos.model.Catalog.*;
import com.restaurantpos.model.Inventory.*;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MenuRepository {
    private final Database db = Database.get();

    private Category mapCategory(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Category(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("image_path"), rs.getInt("sort_order"), Rows.bool(rs, "active"));
    }

    private Product mapProduct(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Product(rs.getLong("id"), rs.getLong("category_id"), rs.getString("category_name"),
                rs.getString("sku"), rs.getString("name"), rs.getString("description"), rs.getString("image_path"),
                Rows.bd(rs, "cost"), Rows.bd(rs, "price"), Rows.bdNullable(rs, "promo_price"),
                rs.getInt("prep_minutes"), rs.getString("station"), Rows.bool(rs, "available"),
                Rows.bd(rs, "tax_rate"), Rows.bool(rs, "track_stock"), Rows.bool(rs, "active"));
    }

    public List<Category> categories(boolean activeOnly) {
        String sql = "SELECT * FROM categories" + (activeOnly ? " WHERE active=1" : "") + " ORDER BY sort_order, name";
        return db.query(sql, this::mapCategory);
    }

    public long saveCategory(Category c) {
        if (c.id() == 0) {
            return db.insert("INSERT INTO categories(name,description,image_path,sort_order,active) VALUES(?,?,?,?,?)",
                    c.name(), c.description(), c.imagePath(), c.sortOrder(), c.active());
        }
        db.update("UPDATE categories SET name=?, description=?, image_path=?, sort_order=?, active=? WHERE id=?",
                c.name(), c.description(), c.imagePath(), c.sortOrder(), c.active(), c.id());
        return c.id();
    }

    public void deleteCategory(long id) {
        long products = db.scalarLong("SELECT COUNT(*) FROM products WHERE category_id=?", id);
        if (products > 0) throw new com.restaurantpos.exception.AppException("This category contains food items. Deactivate it instead.");
        db.update("DELETE FROM categories WHERE id=?", id);
    }

    public List<Product> products(boolean activeOnly, Long categoryId) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.*, c.name AS category_name FROM products p JOIN categories c ON c.id=p.category_id WHERE 1=1
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (activeOnly) { sql.append(" AND p.active=1 AND c.active=1"); }
        if (categoryId != null) { sql.append(" AND p.category_id=?"); args.add(categoryId); }
        sql.append(" ORDER BY c.sort_order, p.name");
        return db.query(sql.toString(), this::mapProduct, args.toArray());
    }

    public List<Product> sellableProducts() {
        return db.query("""
                SELECT p.*, c.name AS category_name FROM products p JOIN categories c ON c.id=p.category_id
                WHERE p.active=1 AND p.available=1 AND c.active=1 ORDER BY c.sort_order, p.name
                """, this::mapProduct);
    }

    public Product product(long id) {
        return db.queryOne("SELECT p.*, c.name AS category_name FROM products p JOIN categories c ON c.id=p.category_id WHERE p.id=?",
                this::mapProduct, id);
    }

    public long saveProduct(Product p, Long userId) {
        if (p.id() == 0) {
            return db.insert("""
                    INSERT INTO products(category_id,sku,name,description,image_path,cost,price,promo_price,prep_minutes,
                    station,available,tax_rate,track_stock,active,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, p.categoryId(), p.sku(), p.name(), p.description(), p.imagePath(), p.cost(), p.price(),
                    p.promoPrice() == null || p.promoPrice().signum() == 0 ? null : p.promoPrice(), p.prepMinutes(),
                    p.station(), p.available(), p.taxRate(), p.trackStock(), p.active(), LocalDateTime.now());
        }
        Product old = product(p.id());
        if (old != null && old.price().compareTo(p.price()) != 0) {
            db.insert("INSERT INTO price_history(product_id,old_price,new_price,user_id,created_at) VALUES(?,?,?,?,?)",
                    p.id(), old.price(), p.price(), userId, LocalDateTime.now());
        }
        db.update("""
                UPDATE products SET category_id=?, sku=?, name=?, description=?, image_path=?, cost=?, price=?,
                promo_price=?, prep_minutes=?, station=?, available=?, tax_rate=?, track_stock=?, active=? WHERE id=?
                """, p.categoryId(), p.sku(), p.name(), p.description(), p.imagePath(), p.cost(), p.price(),
                p.promoPrice() == null || p.promoPrice().signum() == 0 ? null : p.promoPrice(), p.prepMinutes(),
                p.station(), p.available(), p.taxRate(), p.trackStock(), p.active(), p.id());
        return p.id();
    }

    public void setAvailable(long id, boolean available) {
        db.update("UPDATE products SET available=? WHERE id=?", available, id);
    }

    public void setActive(long id, boolean active) {
        db.update("UPDATE products SET active=? WHERE id=?", active, id);
    }

    public boolean productHasOrders(long productId) {
        return db.queryOne("SELECT COUNT(*) FROM order_items WHERE product_id=?", rs -> rs.getInt(1) > 0, productId);
    }

    public boolean skuExistsExcluding(String sku, long excludeProductId) {
        return db.scalarLong("SELECT COUNT(*) FROM products WHERE UPPER(sku)=UPPER(?) AND id<>?", sku.trim(), excludeProductId) > 0;
    }

    public boolean productNameExistsInCategory(String name, long categoryId, long excludeProductId) {
        return db.scalarLong("SELECT COUNT(*) FROM products WHERE LOWER(TRIM(name))=LOWER(TRIM(?)) AND category_id=? AND active=1 AND id<>?",
                name, categoryId, excludeProductId) > 0;
    }

    public void deleteProduct(long id) {
        db.update("DELETE FROM products WHERE id=?", id);
    }

    public void deleteProductSizes(long productId) {
        db.update("DELETE FROM product_variants WHERE product_id=?", productId);
    }

    public void deleteProductAddOns(long productId) {
        db.update("DELETE FROM product_addons WHERE product_id=?", productId);
    }

    public void deleteRecipeLines(long productId) {
        db.update("DELETE FROM recipes WHERE product_id=?", productId);
    }

    public void updateStockTracked(long id, boolean tracked) {
        db.update("UPDATE products SET track_stock=? WHERE id=?", tracked, id);
    }

    public java.util.Map<Long, List<Size>> allActiveSizes() {
        List<Size> all = db.query("SELECT * FROM product_variants WHERE active=1 ORDER BY id",
                rs -> new Size(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
                        Rows.bd(rs, "extra_price"),
                        Rows.bdNullable(rs, "min_price"),
                        Rows.bdNullable(rs, "max_price"),
                        Rows.bdNullable(rs, "price"),
                        Rows.bool(rs, "active")));
        return all.stream().collect(java.util.stream.Collectors.groupingBy(Size::productId));
    }

    public java.util.Map<Long, List<AddOn>> allActiveAddOns() {
        List<AddOnWithProduct> all = db.query("""
                SELECT a.*, pa.product_id AS linked_prod_id FROM addons a JOIN product_addons pa ON pa.addon_id=a.id
                WHERE a.active=1 ORDER BY a.name
                """, rs -> new AddOnWithProduct(rs.getLong("linked_prod_id"),
                new AddOn(rs.getLong("id"), rs.getString("name"), Rows.bd(rs, "price"),
                        Rows.bd(rs, "cost"), Rows.bool(rs, "active"))));
        java.util.Map<Long, List<AddOn>> map = new java.util.HashMap<>();
        for (AddOnWithProduct item : all) {
            map.computeIfAbsent(item.productId(), k -> new java.util.ArrayList<>()).add(item.addon());
        }
        return map;
    }

    private record AddOnWithProduct(long productId, AddOn addon) {}

    public List<Size> sizes(long productId) {
        return db.query("SELECT * FROM product_variants WHERE product_id=? AND active=1 ORDER BY id",
                rs -> new Size(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
                        Rows.bd(rs, "extra_price"),
                        Rows.bdNullable(rs, "min_price"),
                        Rows.bdNullable(rs, "max_price"),
                        Rows.bdNullable(rs, "price"),
                        Rows.bool(rs, "active")), productId);
    }

    public long addSize(long productId, String name, BigDecimal extraPrice) {
        return addSize(productId, name, extraPrice, null, null, null);
    }

    public long addSize(long productId, String name, BigDecimal extraPrice, BigDecimal minPrice, BigDecimal maxPrice, BigDecimal price) {
        return db.insert("INSERT INTO product_variants(product_id,name,extra_price,min_price,max_price,price) VALUES(?,?,?,?,?,?)",
                productId, name, extraPrice, minPrice, maxPrice, price);
    }

    public void updateSize(long variantId, String name, BigDecimal extraPrice, BigDecimal price) {
        db.update("UPDATE product_variants SET name=?, extra_price=?, price=? WHERE id=?",
                name, extraPrice, price, variantId);
    }

    public void deactivateSize(long variantId) {
        db.update("UPDATE product_variants SET active=0 WHERE id=?", variantId);
    }

    public void configureVariantPrice(long variantId, BigDecimal sellingPrice, Long userId) {
        Size size = db.queryOne("SELECT * FROM product_variants WHERE id=?",
                rs -> new Size(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
                        Rows.bd(rs, "extra_price"),
                        Rows.bdNullable(rs, "min_price"),
                        Rows.bdNullable(rs, "max_price"),
                        Rows.bdNullable(rs, "price"),
                        Rows.bool(rs, "active")), variantId);
        if (size == null) throw new com.restaurantpos.exception.AppException("Portion option not found.");
        if (size.isRanged()) {
            if (sellingPrice == null) throw new com.restaurantpos.exception.AppException("Selling price is required.");
            if (sellingPrice.compareTo(size.minPrice()) < 0 || sellingPrice.compareTo(size.maxPrice()) > 0) {
                throw new com.restaurantpos.exception.AppException("Configured price " + Money.fmt(sellingPrice)
                        + " is outside the allowed range of " + Money.fmt(size.minPrice()) + " – " + Money.fmt(size.maxPrice()) + ".");
            }
        }
        db.update("UPDATE product_variants SET price=? WHERE id=?", sellingPrice, variantId);
    }

    public List<AddOn> addOns(long productId) {
        return db.query("""
                SELECT a.* FROM addons a JOIN product_addons pa ON pa.addon_id=a.id
                WHERE pa.product_id=? AND a.active=1 ORDER BY a.name
                """, rs -> new AddOn(rs.getLong("id"), rs.getString("name"), Rows.bd(rs, "price"),
                Rows.bd(rs, "cost"), Rows.bool(rs, "active")), productId);
    }

    public List<AddOn> allAddOns() {
        return db.query("SELECT * FROM addons ORDER BY name",
                rs -> new AddOn(rs.getLong("id"), rs.getString("name"), Rows.bd(rs, "price"), Rows.bd(rs, "cost"),
                        Rows.bool(rs, "active")));
    }

    public long addAddOn(String name, BigDecimal price, BigDecimal cost) {
        return db.insert("INSERT INTO addons(name,price,cost) VALUES(?,?,?)", name, price, cost);
    }

    public void linkAddOn(long productId, long addonId) {
        db.update("INSERT OR IGNORE INTO product_addons(product_id,addon_id) VALUES(?,?)", productId, addonId);
    }

    public List<RecipeLine> recipeLines(long productId) {
        return db.query("""
                SELECT r.*, p.name AS product_name, i.name AS ingredient_name, i.avg_cost AS unit_cost
                FROM recipes r JOIN products p ON p.id=r.product_id JOIN ingredients i ON i.id=r.ingredient_id
                WHERE r.product_id=? ORDER BY i.name
                """, rs -> new RecipeLine(rs.getLong("id"), rs.getLong("product_id"), rs.getString("product_name"),
                rs.getLong("ingredient_id"), rs.getString("ingredient_name"), Rows.bd(rs, "qty"),
                rs.getString("unit"), Rows.bd(rs, "unit_cost")), productId);
    }

    public BigDecimal recipeCost(long productId) {
        return Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(r.qty * i.avg_cost),0) FROM recipes r JOIN ingredients i ON i.id=r.ingredient_id
                WHERE r.product_id=?
                """, productId));
    }

    public long addRecipeLine(long productId, Long variantId, long ingredientId, BigDecimal qty, String unit) {
        return db.insert("INSERT OR REPLACE INTO recipes(product_id,variant_id,ingredient_id,qty,unit) VALUES(?,?,?,?,?)",
                productId, variantId, ingredientId, qty, unit);
    }

    public void deleteRecipeLine(long id) { db.update("DELETE FROM recipes WHERE id=?", id); }

    public List<Customer> customers(String search) {
        if (search == null || search.isBlank()) {
            return db.query("SELECT * FROM customers ORDER BY name", this::mapCustomer);
        }
        String q = "%" + search + "%";
        return db.query("SELECT * FROM customers WHERE name LIKE ? OR phone LIKE ? ORDER BY name", this::mapCustomer, q, q);
    }

    private Customer mapCustomer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Customer(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("address"),
                rs.getString("email"), rs.getString("notes"), Rows.dt(rs, "created_at"));
    }

    public long saveCustomer(Customer c) {
        if (c.id() == 0) {
            return db.insert("INSERT INTO customers(name,phone,address,email,notes,created_at) VALUES(?,?,?,?,?,?)",
                    c.name(), c.phone(), c.address(), c.email(), c.notes(), LocalDateTime.now());
        }
        db.update("UPDATE customers SET name=?, phone=?, address=?, email=?, notes=? WHERE id=?",
                c.name(), c.phone(), c.address(), c.email(), c.notes(), c.id());
        return c.id();
    }

    public String ingredientUnit(long id) {
        String u = db.queryOne("SELECT unit FROM ingredients WHERE id=?", rs -> rs.getString(1), id);
        return u == null ? "pcs" : u;
    }

    public List<Ingredient> ingredients(boolean activeOnly) {
        String sql = "SELECT * FROM ingredients" + (activeOnly ? " WHERE active=1" : "") + " ORDER BY name";
        return db.query(sql, rs -> new Ingredient(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                rs.getString("unit"), Rows.bd(rs, "stock_qty"), Rows.bd(rs, "min_qty"), Rows.bd(rs, "max_qty"),
                Rows.bd(rs, "avg_cost"), Rows.bool(rs, "active")));
    }
}
