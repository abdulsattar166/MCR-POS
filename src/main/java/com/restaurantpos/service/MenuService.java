package com.restaurantpos.service;

import com.restaurantpos.dao.MenuRepository;
import com.restaurantpos.enums.Role;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.*;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.RecipeLine;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MenuService {
    private final MenuRepository menu;
    private final AuditService audit;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public MenuService(MenuRepository menu, AuditService audit) {
        this.menu = menu;
        this.audit = audit;
    }

    public void addMenuChangeListener(Runnable listener) {
        if (listener != null && !changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public void removeMenuChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public void notifyMenuChanged() {
        Images.clearCache();
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception ignored) {}
        }
    }

    public List<Category> categories(boolean activeOnly) { return menu.categories(activeOnly); }
    public List<Product> products(boolean activeOnly, Long categoryId) { return menu.products(activeOnly, categoryId); }
    public List<Product> sellable() { return menu.sellableProducts(); }
    public Product product(long id) { return menu.product(id); }

    public long saveCategory(Category c, Session session) {
        Access.requireManager(session);
        Validators.required(c.name(), "Category name");
        Validators.max(c.name(), 60, "Category name");
        long id = menu.saveCategory(c);
        audit.log(c.id() == 0 ? "CATEGORY_CREATED" : "CATEGORY_UPDATED", "categories", id, c.name());
        notifyMenuChanged();
        return id;
    }

    public void deactivateCategory(long id, Session session) {
        Access.requireManager(session);
        menu.saveCategory(new Category(id, categoryName(id), "", null, 0, false));
        audit.log("CATEGORY_DEACTIVATED", "categories", id, "");
        notifyMenuChanged();
    }

    public void deleteCategory(long id, Session session) {
        Access.requireManager(session);
        // Check if category has products
        List<Product> products = menu.products(true, id);
        if (!products.isEmpty()) {
            throw new AppException("Cannot delete category with " + products.size() + " product(s). Remove products first.");
        }
        String name = categoryName(id);
        menu.deleteCategory(id);
        audit.log("CATEGORY_DELETED", "categories", id, name);
        notifyMenuChanged();
    }

    public String categoryName(long id) {
        return menu.categories(false).stream().filter(c -> c.id() == id).map(Category::name).findFirst().orElse("");
    }

    public long saveProduct(Product p, Session session) {
        Access.requireManager(session);
        Validators.required(p.name(), "Food name");
        Validators.required(p.sku(), "SKU");
        Validators.max(p.name(), 100, "Food name");
        if (p.price().signum() < 0) throw new AppException("Selling price cannot be negative.");
        if (p.cost().signum() < 0) throw new AppException("Cost cannot be negative.");
        if (p.categoryId() <= 0) throw new AppException("Select a category.");
        if (p.taxRate().signum() < 0 || p.taxRate().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new AppException("Tax rate must be between 0 and 100.");
        }
        if (menu.skuExistsExcluding(p.sku(), p.id())) {
            throw new AppException("A product with SKU \"" + p.sku().trim() + "\" already exists.");
        }
        if (menu.productNameExistsInCategory(p.name(), p.categoryId(), p.id())) {
            throw new AppException("An active product named \"" + p.name().trim() + "\" already exists in this category.");
        }
        long id = menu.saveProduct(p, session.userId());
        audit.log(p.id() == 0 ? "PRODUCT_CREATED" : "PRODUCT_UPDATED", "products", id,
                p.name() + " @ " + Money.plain(p.price()));
        notifyMenuChanged();
        return id;
    }

    public void setAvailable(long productId, boolean available, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        menu.setAvailable(productId, available);
        audit.log("PRODUCT_AVAILABILITY", "products", productId, available ? "Available" : "Unavailable");
        notifyMenuChanged();
    }

    public void deactivateProduct(long productId, Session session) {
        Access.requireManager(session);
        menu.setActive(productId, false);
        menu.setAvailable(productId, false);
        audit.log("PRODUCT_DEACTIVATED", "products", productId, "");
        notifyMenuChanged();
    }

    /**
     * Removes a product safely: soft-deletes (deactivates) if referenced by historical orders so past receipts remain intact,
     * otherwise deletes permanently. Returns true if permanently deleted, false if soft-deleted.
     */
    public boolean removeProduct(long productId, Session session) {
        Access.requireManager(session);
        Product p = menu.product(productId);
        if (p == null) throw new AppException("Product not found.");
        if (menu.productHasOrders(productId)) {
            menu.setActive(productId, false);
            menu.setAvailable(productId, false);
            audit.log("PRODUCT_ARCHIVED", "products", productId, p.name() + " (soft-deleted to preserve order history)");
            notifyMenuChanged();
            return false;
        }
        menu.deleteProductSizes(productId);
        menu.deleteProductAddOns(productId);
        menu.deleteRecipeLines(productId);
        menu.deleteProduct(productId);
        audit.log("PRODUCT_DELETED", "products", productId, p.name());
        notifyMenuChanged();
        return true;
    }

    public void deleteProduct(long productId, Session session) {
        Access.requireManager(session);
        Product p = menu.product(productId);
        if (p == null) throw new AppException("Product not found.");
        if (menu.productHasOrders(productId)) {
            throw new AppException("Cannot delete product used in orders. Deactivate it instead.");
        }
        
        // Delete related data
        menu.deleteProductSizes(productId);
        menu.deleteProductAddOns(productId);
        menu.deleteRecipeLines(productId);
        menu.deleteProduct(productId);
        
        audit.log("PRODUCT_DELETED", "products", productId, p.name());
        notifyMenuChanged();
    }

    public String importFoodImage(Path file, String sku) { return Images.importFood(file, sku); }

    public java.util.Map<Long, List<Size>> allActiveSizes() { return menu.allActiveSizes(); }
    public java.util.Map<Long, List<AddOn>> allActiveAddOns() { return menu.allActiveAddOns(); }
    public List<Size> sizes(long productId) { return menu.sizes(productId); }
    public List<AddOn> addOns(long productId) { return menu.addOns(productId); }
    public List<AddOn> allAddOns() { return menu.allAddOns(); }

    public void addSize(long productId, String name, BigDecimal extra, Session session) {
        Access.requireManager(session);
        Validators.required(name, "Size / portion name");
        menu.addSize(productId, name.trim(), Money.nz(extra));
        audit.log("PRODUCT_SIZE_ADDED", "products", productId, name + " +" + Money.plain(extra));
        notifyMenuChanged();
    }

    public long addPortionWithPrice(long productId, String name, BigDecimal fixedPrice, Session session) {
        Access.requireManager(session);
        Validators.required(name, "Portion name");
        if (fixedPrice == null || fixedPrice.signum() < 0) {
            throw new AppException("Portion price cannot be negative.");
        }
        long id = menu.addSize(productId, name.trim(), Money.ZERO, null, null, fixedPrice);
        audit.log("PRODUCT_PORTION_ADDED", "products", productId, name + " @ " + Money.plain(fixedPrice));
        notifyMenuChanged();
        return id;
    }

    public void updatePortion(long variantId, String name, BigDecimal extraPrice, BigDecimal fixedPrice, Session session) {
        Access.requireManager(session);
        Validators.required(name, "Portion name");
        menu.updateSize(variantId, name.trim(), Money.nz(extraPrice), fixedPrice);
        audit.log("PRODUCT_PORTION_UPDATED", "product_variants", variantId, name + (fixedPrice != null ? " @ " + Money.plain(fixedPrice) : ""));
        notifyMenuChanged();
    }

    public void removePortion(long variantId, Session session) {
        Access.requireManager(session);
        menu.deactivateSize(variantId);
        audit.log("PRODUCT_PORTION_REMOVED", "product_variants", variantId, "");
        notifyMenuChanged();
    }

    public void configureVariantPrice(long variantId, BigDecimal sellingPrice, Session session) {
        Access.requireManager(session);
        menu.configureVariantPrice(variantId, sellingPrice, session.userId());
        audit.log("VARIANT_PRICE_CONFIGURED", "product_variants", variantId, "Configured price: " + Money.plain(sellingPrice));
        notifyMenuChanged();
    }

    public void addAddOn(String name, BigDecimal price, BigDecimal cost, Session session) {
        Access.requireManager(session);
        Validators.required(name, "Add-on name");
        if (price.signum() < 0) throw new AppException("Add-on price cannot be negative.");
        long id = menu.addAddOn(name.trim(), Money.nz(price), Money.nz(cost));
        audit.log("ADDON_CREATED", "addons", id, name);
        notifyMenuChanged();
    }

    public void linkAddOn(long productId, long addonId, Session session) {
        Access.requireManager(session);
        menu.linkAddOn(productId, addonId);
        audit.log("ADDON_LINKED", "products", productId, "Add-on #" + addonId);
        notifyMenuChanged();
    }

    public List<RecipeLine> recipe(long productId) { return menu.recipeLines(productId); }
    public BigDecimal recipeCost(long productId) { return menu.recipeCost(productId); }

    public void addRecipeLine(long productId, long ingredientId, BigDecimal qty, String unit, Session session) {
        Access.requireManager(session);
        if (qty.signum() <= 0) throw new AppException("Recipe quantity must be greater than zero.");
        long id = menu.addRecipeLine(productId, null, ingredientId, qty, unit);
        audit.log("RECIPE_UPDATED", "products", productId, "Ingredient #" + ingredientId + " x " + qty + " " + unit);
    }

    public void removeRecipeLine(long id, long productId, Session session) {
        Access.requireManager(session);
        menu.deleteRecipeLine(id);
        audit.log("RECIPE_LINE_REMOVED", "products", productId, "Recipe line " + id);
    }

    public List<Customer> customers(String search) { return menu.customers(search); }

    public long saveCustomer(Customer customer, Session session) {
        Validators.required(customer.name(), "Customer name");
        Validators.phone(customer.phone(), "Customer phone", true);
        long id = menu.saveCustomer(customer);
        audit.log("CUSTOMER_SAVED", "customers", id, customer.name());
        return id;
    }

    public BigDecimal margin(BigDecimal price, BigDecimal cost) {
        if (price == null || price.signum() <= 0) return Money.ZERO;
        return Money.percent(Money.div(Money.sub(price, cost), price), BigDecimal.valueOf(100));
    }
}
