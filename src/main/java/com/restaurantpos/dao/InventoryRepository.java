package com.restaurantpos.dao;

import com.restaurantpos.enums.StockMoveType;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Inventory.*;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InventoryRepository {
    private final Database db = Database.get();

    private Ingredient mapIngredient(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Ingredient(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("unit"),
                Rows.bd(rs, "stock_qty"), Rows.bd(rs, "min_qty"), Rows.bd(rs, "max_qty"), Rows.bd(rs, "avg_cost"),
                Rows.bool(rs, "active"));
    }

    private Supplier mapSupplier(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Supplier(rs.getLong("id"), rs.getString("name"), rs.getString("contact"), rs.getString("phone"),
                rs.getString("email"), rs.getString("address"), rs.getString("terms"), Rows.bool(rs, "active"));
    }

    // ---------- ingredients ----------
    public List<Ingredient> ingredients(boolean activeOnly) {
        String sql = "SELECT * FROM ingredients" + (activeOnly ? " WHERE active=1" : "") + " ORDER BY name";
        return db.query(sql, this::mapIngredient);
    }

    public Ingredient ingredient(long id) {
        return db.queryOne("SELECT * FROM ingredients WHERE id=?", this::mapIngredient, id);
    }

    public long saveIngredient(Ingredient i) {
        if (i.id() == 0) {
            return db.insert("""
                    INSERT INTO ingredients(sku,name,unit,stock_qty,min_qty,max_qty,avg_cost,active) VALUES(?,?,?,?,?,?,?,?)
                    """, i.sku(), i.name(), i.unit(), i.stockQty(), i.minQty(), i.maxQty(), i.avgCost(), i.active());
        }
        db.update("UPDATE ingredients SET sku=?, name=?, unit=?, min_qty=?, max_qty=?, avg_cost=?, active=? WHERE id=?",
                i.sku(), i.name(), i.unit(), i.minQty(), i.maxQty(), i.avgCost(), i.active(), i.id());
        return i.id();
    }

    public List<Ingredient> lowStock() {
        return db.query("SELECT * FROM ingredients WHERE active=1 AND stock_qty<=min_qty ORDER BY (stock_qty-min_qty), name",
                this::mapIngredient);
    }

    public BigDecimal stockValue() {
        return Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(stock_qty*avg_cost),0) FROM ingredients WHERE active=1"));
    }

    public void adjustStock(long ingredientId, BigDecimal signedQty, StockMoveType type, String refType, Long refId,
                            String notes, String user, BigDecimal unitCost) {
        Ingredient ing = ingredient(ingredientId);
        if (ing == null) throw new AppException("Ingredient not found.");
        BigDecimal newQty = Money.nz(ing.stockQty().add(signedQty));
        if (newQty.signum() < 0) newQty = BigDecimal.ZERO;
        BigDecimal cost = unitCost == null ? ing.avgCost() : unitCost;
        if (type == StockMoveType.PURCHASE && signedQty.signum() > 0 && unitCost != null) {
            BigDecimal oldValue = Money.mul(ing.stockQty(), ing.avgCost());
            BigDecimal newValue = Money.mul(signedQty, unitCost);
            BigDecimal totalQty = ing.stockQty().add(signedQty);
            if (totalQty.signum() > 0) {
                cost = oldValue.add(newValue).divide(totalQty, 4, RoundingMode.HALF_UP);
            }
        }
        db.update("UPDATE ingredients SET stock_qty=?, avg_cost=? WHERE id=?", newQty, cost, ingredientId);
        db.insert("""
                INSERT INTO stock_movements(ingredient_id,type,qty,unit_cost,ref_type,ref_id,notes,created_by,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, ingredientId, type.name(), signedQty, cost, refType, refId, notes, user, LocalDateTime.now());
    }

    public List<StockMovement> movements(Long ingredientId, int limit) {
        String sql = """
                SELECT m.*, i.name AS ingredient_name FROM stock_movements m JOIN ingredients i ON i.id=m.ingredient_id
                """ + (ingredientId == null ? "" : " WHERE m.ingredient_id=?") + " ORDER BY m.id DESC LIMIT ?";
        if (ingredientId == null) {
            return db.query(sql, this::mapMovement, limit);
        }
        return db.query(sql, this::mapMovement, ingredientId, limit);
    }

    private StockMovement mapMovement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StockMovement(rs.getLong("id"), rs.getLong("ingredient_id"), rs.getString("ingredient_name"),
                rs.getString("type"), Rows.bd(rs, "qty"), Rows.bd(rs, "unit_cost"), rs.getString("ref_type"),
                Rows.longOrNull(rs, "ref_id"), rs.getString("notes"), rs.getString("created_by"), Rows.dt(rs, "created_at"));
    }

    public boolean consumptionExists(long orderId) {
        return db.scalarLong("""
                SELECT COUNT(*) FROM stock_movements WHERE ref_type='ORDER' AND ref_id=? AND type='SALE_CONSUMPTION'
                """, orderId) > 0;
    }

    /** Deducts ingredients according to product recipes. Idempotent per order. */
    public BigDecimal consumeForOrder(long orderId, String user) {
        if (consumptionExists(orderId)) return Money.ZERO;
        List<Map<String, Object>> required = new ArrayList<>();
        BigDecimal cost = Money.ZERO;
        var consumption = new LinkedHashMap<Long, BigDecimal>();
        var costMap = new LinkedHashMap<Long, BigDecimal>();
        var items = db.query("SELECT * FROM order_items WHERE order_id=? AND status='ACTIVE'",
                rs -> new Object[]{rs.getLong("product_id"), rs.getInt("qty"), rs.getString("addons")}, orderId);
        for (Object[] item : items) {
            long productId = (Long) item[0];
            int qty = (Integer) item[1];
            List<BigDecimal[]> lines = db.query("""
                    SELECT r.ingredient_id, r.qty FROM recipes r WHERE r.product_id=?
                    """, rs -> new BigDecimal[]{BigDecimal.valueOf(rs.getLong(1)), new BigDecimal(rs.getString(2))}, productId);
            for (BigDecimal[] line : lines) {
                long ingId = line[0].longValue();
                BigDecimal total = line[1].multiply(BigDecimal.valueOf(qty));
                consumption.merge(ingId, total, BigDecimal::add);
            }
        }
        for (Map.Entry<Long, BigDecimal> e : consumption.entrySet()) {
            Ingredient ing = ingredient(e.getKey());
            if (ing == null) continue;
            BigDecimal lineCost = Money.mul(e.getValue(), ing.avgCost());
            cost = cost.add(lineCost);
            db.update("UPDATE ingredients SET stock_qty = MAX(0, stock_qty - ?) WHERE id=?", e.getValue(), e.getKey());
            db.insert("""
                    INSERT INTO stock_movements(ingredient_id,type,qty,unit_cost,ref_type,ref_id,notes,created_by,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, e.getKey(), StockMoveType.SALE_CONSUMPTION.name(), e.getValue().negate(), ing.avgCost(),
                    "ORDER", orderId, "Recipe consumption for order #" + orderId, user, LocalDateTime.now());
        }
        return cost;
    }

    public void reverseConsumption(long orderId, String user, String reason) {
        boolean reversed = db.scalarLong("""
                SELECT COUNT(*) FROM stock_movements WHERE ref_type='ORDER_REVERSAL' AND ref_id=?
                """, orderId) > 0;
        if (reversed) return;
        List<Object[]> rows = db.query("""
                SELECT ingredient_id, SUM(qty), MAX(unit_cost) FROM stock_movements
                WHERE ref_type='ORDER' AND ref_id=? AND type='SALE_CONSUMPTION' GROUP BY ingredient_id
                """, rs -> new Object[]{rs.getLong(1), new BigDecimal(rs.getString(2)), new BigDecimal(rs.getString(3))}, orderId);
        for (Object[] row : rows) {
            long ingId = (Long) row[0];
            BigDecimal qty = ((BigDecimal) row[1]).negate();
            BigDecimal cost = (BigDecimal) row[2];
            if (qty.signum() <= 0) continue;
            db.update("UPDATE ingredients SET stock_qty = stock_qty + ? WHERE id=?", qty, ingId);
            db.insert("""
                    INSERT INTO stock_movements(ingredient_id,type,qty,unit_cost,ref_type,ref_id,notes,created_by,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """, ingId, StockMoveType.ADJUSTMENT.name(), qty, cost, "ORDER_REVERSAL", orderId,
                    reason, user, LocalDateTime.now());
        }
    }

    public void addWastage(long ingredientId, BigDecimal qty, String reason, String user) {
        Ingredient ing = ingredient(ingredientId);
        if (ing == null) throw new AppException("Ingredient not found.");
        BigDecimal cost = Money.mul(qty, ing.avgCost());
        db.insert("INSERT INTO wastage(ingredient_id,qty,reason,cost,created_by,created_at) VALUES(?,?,?,?,?,?)",
                ingredientId, qty, reason, cost, user, LocalDateTime.now());
        adjustStock(ingredientId, qty.negate(), StockMoveType.WASTAGE, "WASTAGE", null, reason, user, ing.avgCost());
    }

    public List<Object[]> wastage(LocalDate from, LocalDate to) {
        return db.query("""
                SELECT w.id, i.name, w.qty, i.unit, w.cost, w.reason, w.created_by, w.created_at
                FROM wastage w JOIN ingredients i ON i.id=w.ingredient_id
                WHERE date(w.created_at) BETWEEN ? AND ? ORDER BY w.id DESC
                """, rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)}, from.toString(), to.toString());
    }

    public void saveStockCount(Map<Long, BigDecimal> counted, String user, String notes) {
        long countId = db.insert("INSERT INTO stock_counts(counted_at,counted_by,notes) VALUES(?,?,?)",
                LocalDateTime.now(), user, notes);
        for (Map.Entry<Long, BigDecimal> e : counted.entrySet()) {
            Ingredient ing = ingredient(e.getKey());
            if (ing == null) continue;
            db.insert("INSERT INTO stock_count_items(count_id,ingredient_id,system_qty,counted_qty) VALUES(?,?,?,?)",
                    countId, e.getKey(), ing.stockQty(), e.getValue());
            BigDecimal diff = e.getValue().subtract(ing.stockQty());
            if (diff.signum() != 0) {
                db.update("UPDATE ingredients SET stock_qty=? WHERE id=?", e.getValue(), e.getKey());
                db.insert("""
                        INSERT INTO stock_movements(ingredient_id,type,qty,unit_cost,ref_type,ref_id,notes,created_by,created_at)
                        VALUES(?,?,?,?,?,?,?,?,?)
                        """, e.getKey(), StockMoveType.COUNT.name(), diff, ing.avgCost(), "STOCK_COUNT", countId,
                        notes, user, LocalDateTime.now());
            }
        }
    }

    // ---------- suppliers ----------
    public List<Supplier> suppliers(boolean activeOnly) {
        String sql = "SELECT * FROM suppliers" + (activeOnly ? " WHERE active=1" : "") + " ORDER BY name";
        return db.query(sql, this::mapSupplier);
    }

    public long saveSupplier(Supplier s) {
        if (s.id() == 0) {
            return db.insert("INSERT INTO suppliers(name,contact,phone,email,address,terms,active) VALUES(?,?,?,?,?,?,?)",
                    s.name(), s.contact(), s.phone(), s.email(), s.address(), s.terms(), s.active());
        }
        db.update("UPDATE suppliers SET name=?, contact=?, phone=?, email=?, address=?, terms=?, active=? WHERE id=?",
                s.name(), s.contact(), s.phone(), s.email(), s.address(), s.terms(), s.active(), s.id());
        return s.id();
    }

    // ---------- purchasing ----------
    public List<PurchaseOrder> purchaseOrders() {
        return db.query("""
                SELECT p.*, s.name AS supplier_name FROM purchase_orders p JOIN suppliers s ON s.id=p.supplier_id
                ORDER BY p.id DESC
                """, rs -> new PurchaseOrder(rs.getLong("id"), rs.getString("po_no"), rs.getLong("supplier_id"),
                rs.getString("supplier_name"), rs.getString("status"), Rows.date(rs, "order_date"),
                Rows.date(rs, "expected_date"), Rows.bd(rs, "subtotal"), Rows.bd(rs, "tax"), Rows.bd(rs, "total"),
                rs.getString("notes"), rs.getString("created_by")));
    }

    public List<PurchaseLine> purchaseLines(long poId) {
        return db.query("""
                SELECT pi.*, i.name AS ingredient_name FROM purchase_items pi JOIN ingredients i ON i.id=pi.ingredient_id
                WHERE pi.po_id=? ORDER BY pi.id
                """, rs -> new PurchaseLine(rs.getLong("id"), rs.getLong("po_id"), rs.getLong("ingredient_id"),
                rs.getString("ingredient_name"), Rows.bd(rs, "qty"), Rows.bd(rs, "unit_cost"),
                Rows.bd(rs, "received_qty")), poId);
    }

    public long createPurchaseOrder(String poNo, long supplierId, String status, LocalDate orderDate,
                                    LocalDate expectedDate, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                                    String notes, String createdBy, Map<Long, BigDecimal[]> lines) {
        long id = db.insert("""
                INSERT INTO purchase_orders(po_no,supplier_id,status,order_date,expected_date,subtotal,tax,total,notes,created_by,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, poNo, supplierId, status, orderDate, expectedDate, subtotal, tax, total, notes, createdBy, LocalDateTime.now());
        for (Map.Entry<Long, BigDecimal[]> e : lines.entrySet()) {
            db.insert("INSERT INTO purchase_items(po_id,ingredient_id,qty,unit_cost) VALUES(?,?,?,?)",
                    id, e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        return id;
    }

    public void approvePurchase(long poId) {
        db.update("UPDATE purchase_orders SET status='APPROVED' WHERE id=? AND status='DRAFT'", poId);
    }

    public void receivePurchase(long poId, String user, String notes) {
        List<PurchaseLine> lines = purchaseLines(poId);
        boolean any = false;
        for (PurchaseLine line : lines) {
            BigDecimal remaining = line.qty().subtract(line.receivedQty());
            if (remaining.signum() > 0) {
                db.update("UPDATE purchase_items SET received_qty=? WHERE id=?", line.qty(), line.id());
                adjustStock(line.ingredientId(), remaining, StockMoveType.PURCHASE, "PO", poId,
                        "Received purchase " + poId, user, line.unitCost());
                any = true;
            }
        }
        if (any) {
            db.insert("INSERT INTO goods_receipts(po_id,received_at,received_by,notes) VALUES(?,?,?,?)",
                    poId, LocalDateTime.now(), user, notes);
            db.update("UPDATE purchase_orders SET status='RECEIVED' WHERE id=?", poId);
        }
    }

    public List<Object[]> supplierPayments(Long supplierId) {
        return db.query("""
                SELECT sp.id, s.name, sp.amount, sp.method, sp.ref, sp.paid_at, sp.notes
                FROM supplier_payments sp JOIN suppliers s ON s.id=sp.supplier_id
                """ + (supplierId == null ? "" : " WHERE sp.supplier_id=?") + " ORDER BY sp.id DESC",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)},
                supplierId == null ? new Object[0] : new Object[]{supplierId});
    }

    public void addSupplierPayment(long supplierId, Long poId, BigDecimal amount, String method, String ref, String notes) {
        db.update("INSERT INTO supplier_payments (supplier_id, po_id, amount, method, ref, notes, paid_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                supplierId, poId, amount, method, ref, notes, LocalDateTime.now());
    }

    public long savePurchaseOrder(PurchaseOrder po) {
        return db.insert("INSERT INTO purchase_orders (po_no, supplier_id, status, order_date, expected_date, subtotal, tax, total, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.poNo(), po.supplierId(), po.status(), po.orderDate(), po.expectedDate(), po.subtotal(), po.tax(), po.total(), po.notes(), po.createdBy());
    }

    public void savePurchaseLine(long poId, PurchaseLine line) {
        db.update("INSERT INTO purchase_items (po_id, ingredient_id, qty, unit_cost) VALUES (?, ?, ?, ?)",
                poId, line.ingredientId(), line.qty(), line.unitCost());
    }

    public void updatePurchaseOrder(long poId, long supplierId, LocalDate expectedDate, BigDecimal subtotal,
                                    BigDecimal tax, BigDecimal total, String notes, Map<Long, BigDecimal[]> lines) {
        db.txRun(c -> {
            db.update("""
                    UPDATE purchase_orders SET supplier_id=?, expected_date=?, subtotal=?, tax=?, total=?, notes=?
                    WHERE id=? AND status='DRAFT'
                    """, supplierId, expectedDate, subtotal, tax, total, notes, poId);
            db.update("DELETE FROM purchase_items WHERE po_id=?", poId);
            for (Map.Entry<Long, BigDecimal[]> e : lines.entrySet()) {
                db.insert("INSERT INTO purchase_items(po_id,ingredient_id,qty,unit_cost) VALUES(?,?,?,?)",
                        poId, e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
        });
    }

    public boolean ingredientHasRecipes(long ingredientId) {
        return db.queryOne("SELECT COUNT(*) FROM recipes WHERE ingredient_id=?", rs -> rs.getInt(1) > 0, ingredientId);
    }

    public boolean ingredientHasPurchases(long ingredientId) {
        return db.queryOne("""
                SELECT COUNT(*) FROM purchase_items pi
                JOIN purchase_orders po ON po.id=pi.po_id
                WHERE pi.ingredient_id=? AND po.status IN ('APPROVED','RECEIVED')
                """, rs -> rs.getInt(1) > 0, ingredientId);
    }

    public void deleteIngredient(long ingredientId) {
        db.txRun(c -> {
            db.update("DELETE FROM stock_movements WHERE ingredient_id=?", ingredientId);
            db.update("DELETE FROM stock_count_items WHERE ingredient_id=?", ingredientId);
            db.update("DELETE FROM wastage WHERE ingredient_id=?", ingredientId);
            db.update("DELETE FROM purchase_items WHERE ingredient_id=?", ingredientId);
            db.update("DELETE FROM ingredients WHERE id=?", ingredientId);
        });
    }

    public boolean hasActiveOrders(long supplierId) {
        return db.queryOne("SELECT COUNT(*) FROM purchase_orders WHERE supplier_id=? AND status IN ('DRAFT','APPROVED','RECEIVED')",
                rs -> rs.getInt(1) > 0, supplierId);
    }

    public int activeOrderCount(long supplierId) {
        return db.queryOne("SELECT COUNT(*) FROM purchase_orders WHERE supplier_id=? AND status IN ('DRAFT','APPROVED','RECEIVED')",
                rs -> rs.getInt(1), supplierId);
    }

    public boolean hasPayments(long supplierId) {
        return db.queryOne("SELECT COUNT(*) FROM supplier_payments WHERE supplier_id=?", rs -> rs.getInt(1) > 0, supplierId);
    }

    public void deleteSupplier(long supplierId) {
        db.update("DELETE FROM suppliers WHERE id=?", supplierId);
    }

    public void deletePurchaseOrder(long poId) {
        db.update("DELETE FROM purchase_orders WHERE id=?", poId);
    }

    public void deleteSupplierPayment(long paymentId) {
        db.update("DELETE FROM supplier_payments WHERE id=?", paymentId);
    }

    public BigDecimal supplierOutstanding(long supplierId) {
        BigDecimal purchases = Money.nz(db.scalarDecimal("""
                SELECT COALESCE(SUM(total),0) FROM purchase_orders WHERE supplier_id=? AND status IN ('APPROVED','RECEIVED')
                """, supplierId));
        BigDecimal paid = Money.nz(db.scalarDecimal("SELECT COALESCE(SUM(amount),0) FROM supplier_payments WHERE supplier_id=?", supplierId));
        return Money.sub(purchases, paid);
    }
}
