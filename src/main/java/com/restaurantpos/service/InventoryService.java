package com.restaurantpos.service;

import com.restaurantpos.dao.InventoryRepository;
import com.restaurantpos.enums.Role;
import com.restaurantpos.enums.StockMoveType;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Inventory.Ingredient;
import com.restaurantpos.model.Inventory.StockMovement;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class InventoryService {
    private final InventoryRepository inventory;
    private final AuditService audit;

    public InventoryService(InventoryRepository inventory, AuditService audit) {
        this.inventory = inventory;
        this.audit = audit;
    }

    public List<Ingredient> ingredients(boolean activeOnly) { return inventory.ingredients(activeOnly); }
    public List<Ingredient> lowStock() { return inventory.lowStock(); }
    public BigDecimal stockValue() { return inventory.stockValue(); }
    public List<StockMovement> movements(Long ingredientId, int limit) { return inventory.movements(ingredientId, limit); }

    public long saveIngredient(Ingredient i, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.INVENTORY);
        Validators.required(i.name(), "Ingredient name");
        Validators.required(i.sku(), "Ingredient SKU");
        if (i.avgCost().signum() < 0) throw new AppException("Average cost cannot be negative.");
        if (i.minQty().signum() < 0 || i.maxQty().signum() < 0) throw new AppException("Stock levels cannot be negative.");
        long id = inventory.saveIngredient(i);
        audit.log(i.id() == 0 ? "INGREDIENT_CREATED" : "INGREDIENT_UPDATED", "ingredients", id, i.name());
        checkLowStock();
        return id;
    }

    public boolean ingredientHasRecipes(long ingredientId) {
        return inventory.ingredientHasRecipes(ingredientId);
    }

    public boolean ingredientHasPurchases(long ingredientId) {
        return inventory.ingredientHasPurchases(ingredientId);
    }

    public void deleteIngredient(long ingredientId, Session session) {
        Access.requireManager(session);
        Ingredient ing = inventory.ingredient(ingredientId);
        if (ing == null) throw new AppException("Ingredient not found.");
        if (inventory.ingredientHasRecipes(ingredientId)) {
            throw new AppException("Cannot delete ingredient used in product recipes. Remove it from recipes or deactivate it.");
        }
        if (inventory.ingredientHasPurchases(ingredientId)) {
            throw new AppException("Cannot delete ingredient referenced by approved or received purchase orders. Deactivate it instead.");
        }
        inventory.deleteIngredient(ingredientId);
        audit.log("INGREDIENT_DELETED", "ingredients", ingredientId, ing.name());
        checkLowStock();
    }

    public void adjust(long ingredientId, BigDecimal quantityDelta, String reason, Session session) {
        Access.requireManager(session);
        if (reason == null || reason.isBlank()) throw new AppException("An adjustment reason is required.");
        if (quantityDelta.signum() == 0) throw new AppException("Adjustment quantity cannot be zero.");
        var ing = inventory.ingredient(ingredientId);
        if (ing == null) throw new AppException("Ingredient not found.");
        inventory.adjustStock(ingredientId, quantityDelta, StockMoveType.ADJUSTMENT, "MANUAL", null, reason, session.username(), ing.avgCost());
        audit.log("STOCK_ADJUSTED", "ingredients", ingredientId, Money.plain(quantityDelta) + " — " + reason);
        checkLowStock();
    }

    public void wastage(long ingredientId, BigDecimal qty, String reason, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.INVENTORY);
        if (qty.signum() <= 0) throw new AppException("Wastage quantity must be greater than zero.");
        if (reason == null || reason.isBlank()) throw new AppException("A wastage reason is required.");
        inventory.addWastage(ingredientId, qty, reason, session.username());
        audit.log("WASTAGE_RECORDED", "ingredients", ingredientId, Money.plain(qty) + " — " + reason);
        audit.notify("WASTAGE", "Wastage recorded", Money.plain(qty) + " wasted: " + reason, "WARNING");
        checkLowStock();
    }

    public void stockCount(Map<Long, BigDecimal> counted, String notes, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.INVENTORY);
        if (counted.isEmpty()) throw new AppException("Enter at least one counted quantity.");
        for (BigDecimal v : counted.values()) if (v.signum() < 0) throw new AppException("Counted quantities cannot be negative.");
        inventory.saveStockCount(counted, session.username(), notes == null ? "" : notes);
        audit.log("STOCK_COUNT", "stock_counts", null, "Counted " + counted.size() + " ingredient(s)");
        checkLowStock();
    }

    public List<Object[]> wastage(LocalDate from, LocalDate to) { return inventory.wastage(from, to); }

    public void checkLowStock() {
        List<Ingredient> low = inventory.lowStock();
        for (Ingredient i : low) {
            String msg = i.out() ? "Out of stock" : "Running low";
            audit.notify("LOW_STOCK", msg + ": " + i.name(),
                    i.name() + " — " + i.stockQty().toPlainString() + " " + i.unit() + " remaining (minimum " + i.minQty().toPlainString() + ").",
                    i.out() ? "CRITICAL" : "WARNING");
        }
    }
}
