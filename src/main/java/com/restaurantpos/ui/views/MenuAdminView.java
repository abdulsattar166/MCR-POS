package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.Catalog.*;
import com.restaurantpos.model.Inventory.Ingredient;
import com.restaurantpos.model.Inventory.RecipeLine;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.reporting.CsvExporter;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportData.Column;
import java.nio.charset.StandardCharsets;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MenuAdminView extends AppView {
    private final TabPane tabs = new TabPane();
    private final TableView<Category> categoryTable = Ui.table();
    private final TableView<Product> productTable = Ui.table();
    private final ComboBox<Category> categoryFilter = new ComboBox<>();
    private final TextField search = Ui.textField("Search food item or SKU...");
    private final ComboBox<Product> recipeProduct = new ComboBox<>();
    private final TableView<RecipeLine> recipeTable = Ui.table();
    private final Label recipeSummary = Ui.muted("");

    public MenuAdminView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Menu Management", "Categories, food items, images, prices, add-ons and production recipes.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Categories", categoriesTab()), new Tab("Food items", productsTab()),
                new Tab("Recipes & cost", recipesTab()));
        tabs.getStyleClass().add("menu-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        List<Category> categories = ctx.menu().categories(false);
        Category selected = categoryFilter.getValue();
        categoryFilter.getItems().setAll(categories);
        categoryFilter.getItems().add(0, null);
        categoryFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Category c) { return c == null ? "All categories" : c.name(); }
            @Override public Category fromString(String s) { return null; }
        });
        if (selected != null) categoryFilter.getItems().stream().filter(c -> c != null && c.id() == selected.id())
                .findFirst().ifPresent(categoryFilter::setValue);
        else categoryFilter.setValue(null);
        categoryTable.getItems().setAll(categories);
        loadProducts();
        List<Product> products = ctx.menu().products(true, null);
        Product selectedProduct = recipeProduct.getValue();
        recipeProduct.getItems().setAll(products);
        recipeProduct.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Product p) { return p == null ? "Select a food item" : p.name(); }
            @Override public Product fromString(String s) { return null; }
        });
        if (selectedProduct != null) products.stream().filter(p -> p.id() == selectedProduct.id()).findFirst().ifPresent(recipeProduct::setValue);
        if (recipeProduct.getValue() == null && !products.isEmpty()) recipeProduct.setValue(products.get(0));
        loadRecipe();
    }

    private VBox categoriesTab() {
        categoryTable.getColumns().addAll(
                Ui.col("Category", Category::name, 180),
                Ui.col("Description", c -> c.description() == null ? "" : c.description(), 300),
                Ui.col("Order", c -> String.valueOf(c.sortOrder()), 60),
                Ui.col("Status", c -> c.active() ? "Active" : "Inactive", 80));
        categoryTable.setPlaceholder(Ui.empty("No categories", "Add your first menu category."));
        Button add = Ui.primary("Add category", "fas-plus", () -> categoryDialog(null));
        Button edit = Ui.secondary("Edit", "fas-edit", () -> {
            Category c = categoryTable.getSelectionModel().getSelectedItem();
            if (c == null) { warn("Select a category."); return; }
            categoryDialog(c);
        });
        Button image = Ui.secondary("Change image", "fas-image", () -> {
            Category c = categoryTable.getSelectionModel().getSelectedItem();
            if (c == null) { warn("Select a category."); return; }
            File f = Ui.chooseImage(nav.window());
            if (f != null) {
                try {
                    String path = Images.importFood(f.toPath(), "category_" + c.id());
                    ctx.menu().saveCategory(new Category(c.id(), c.name(), c.description(), path, c.sortOrder(), c.active()), ctx.session());
                    refresh();
                    toast("Category image updated.");
                } catch (Exception e) { Ui.error(nav.window(), e); }
            }
        });
        Button deleteCategory = Ui.ghost("Delete", "fas-trash-alt", () -> {
            Category c = categoryTable.getSelectionModel().getSelectedItem();
            if (c == null) { warn("Select a category to delete."); return; }
            
            // Check if category has products
            List<Product> productsInCategory = ctx.menu().products(false, c.id());
            if (!productsInCategory.isEmpty()) {
                Ui.error(nav.window(), "Cannot delete category", 
                    c.name() + " contains " + productsInCategory.size() + " product(s). " +
                    "Please move or delete all products first.");
                return;
            }
            
            String msg = "Delete category \"" + c.name() + "\"?\n\n" +
                        "This action cannot be undone. The category will be permanently removed.";
            if (Ui.confirm(nav.window(), "Delete category", msg)) {
                try {
                    ctx.menu().deleteCategory(c.id(), ctx.session());
                    refresh();
                    toast("Category \"" + c.name() + "\" has been deleted.");
                } catch (Exception e) { 
                    Ui.error(nav.window(), "Delete failed", e.getMessage());
                }
            }
        });
        HBox bar = new HBox(8, add, edit, image, deleteCategory, Ui.spacer(),
                Ui.muted("Images are stored in the local images folder."));
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, categoryTable);
        VBox.setVgrow(categoryTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox productsTab() {
        productTable.getColumns().addAll(
                Ui.col("SKU", Product::sku, 95),
                Ui.col("Food item", Product::name, 200),
                Ui.col("Category", Product::categoryName, 130),
                Ui.moneyCol("Cost", Product::cost, 80),
                Ui.moneyCol("Price", Product::price, 85),
                Ui.col("Margin", p -> ctx.menu().margin(p.price(), p.cost()).setScale(1, java.math.RoundingMode.HALF_UP) + "%", 65),
                Ui.col("Available", p -> p.available() ? "Yes" : "No", 75),
                Ui.col("Status", p -> p.active() ? "Active" : "Inactive", 75));
        productTable.setPlaceholder(Ui.empty("No food items", "Add your first menu item."));
        categoryFilter.setOnAction(e -> loadProducts());
        categoryFilter.setPrefWidth(170);
        search.setPrefWidth(230);
        Ui.bindSearch(search, this::loadProducts);
        Button add = Ui.primary("Add food item", "fas-plus", () -> productDialog(null));
        Button edit = Ui.secondary("Edit", "fas-edit", () -> {
            Product p = productTable.getSelectionModel().getSelectedItem();
            if (p == null) { warn("Select a food item."); return; }
            productDialog(p);
        });
        Button options = Ui.secondary("Sizes & add-ons", "fas-list", this::optionsDialog);
        Button toggle = Ui.ghost("Mark unavailable", "fas-ban", () -> {
            Product p = productTable.getSelectionModel().getSelectedItem();
            if (p == null) { warn("Select a food item."); return; }
            ctx.menu().setAvailable(p.id(), !p.available(), ctx.session());
            refresh();
            toast(p.name() + (p.available() ? " marked unavailable." : " is available again."));
        });
        Button deactivate = Ui.ghost("Deactivate", "fas-ban", () -> {
            Product p = productTable.getSelectionModel().getSelectedItem();
            if (p == null) { warn("Select a food item."); return; }
            if (!Ui.confirm(nav.window(), "Deactivate food item", "Deactivate " + p.name() + "? Historical orders keep their original details.")) return;
            try { ctx.menu().deactivateProduct(p.id(), ctx.session()); refresh(); toast("Food item deactivated."); }
            catch (Exception e) { Ui.error(nav.window(), e); }
        });
        Button deleteProduct = Ui.ghost("Remove Item", "fas-trash-alt", () -> {
            Product p = productTable.getSelectionModel().getSelectedItem();
            if (p == null) { warn("Select a food item to remove."); return; }

            boolean hasOrders = ctx.orderRepo().productHasOrders(p.id());
            String msg = hasOrders
                    ? "Remove \"" + p.name() + "\" from the active menu?\n\n"
                      + "Because this product appears in historical orders, it will be deactivated & archived cleanly:\n"
                      + "• It will no longer appear in New Order or searches.\n"
                      + "• Past receipts, financial totals, and order history will remain 100% intact."
                    : "Permanently remove \"" + p.name() + "\"?\n\n"
                      + "This product has no historical orders and will be permanently deleted along with its variants and recipe links.";
            if (Ui.confirm(nav.window(), "Remove Menu Item", msg)) {
                try {
                    boolean hardDeleted = ctx.menu().removeProduct(p.id(), ctx.session());
                    refresh();
                    toast(hardDeleted
                            ? "Product \"" + p.name() + "\" permanently removed."
                            : "Product \"" + p.name() + "\" archived from menu (historical orders preserved).");
                } catch (Exception e) {
                    Ui.error(nav.window(), "Remove failed", e.getMessage());
                }
            }
        });
        Button export = Ui.ghost("Export CSV", "fas-file-csv", this::exportMenu);
        Button importCsv = Ui.ghost("Import CSV", "fas-file-import", this::importMenu);
        HBox bar = new HBox(8, categoryFilter, search, add, edit, options, toggle, deactivate, deleteProduct, export, importCsv);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, productTable);
        VBox.setVgrow(productTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox recipesTab() {
        recipeProduct.setPrefWidth(280);
        recipeProduct.setOnAction(e -> loadRecipe());
        Button addLine = Ui.primary("Add ingredient", "fas-plus", () -> recipeLineDialog());
        Button remove = Ui.ghost("Remove line", "fas-trash-alt", () -> {
            RecipeLine line = recipeTable.getSelectionModel().getSelectedItem();
            if (line == null) { warn("Select a recipe line."); return; }
            ctx.menu().removeRecipeLine(line.id(), line.productId(), ctx.session());
            loadRecipe();
        });
        HBox bar = new HBox(10, Ui.fieldLabel("Food item"), recipeProduct, addLine, remove, Ui.spacer(), recipeSummary);
        bar.setAlignment(Pos.CENTER_LEFT);
        recipeTable.getColumns().addAll(
                Ui.col("Ingredient", RecipeLine::ingredientName, 220),
                Ui.col("Qty per serving", r -> r.qty().stripTrailingZeros().toPlainString() + " " + r.unit(), 130),
                Ui.moneyCol("Unit cost", RecipeLine::unitCost, 110),
                Ui.moneyCol("Line cost", RecipeLine::cost, 110));
        recipeTable.setPlaceholder(Ui.empty("No recipe lines", "Add ingredients to calculate the production cost."));
        Label note = Ui.muted("Recipe costs use the current weighted-average ingredient cost. Selling price and margin are shown for the selected item.");
        note.setWrapText(true);
        VBox box = new VBox(12, bar, recipeTable, note);
        VBox.setVgrow(recipeTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private void loadProducts() {
        Category category = categoryFilter.getValue();
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        List<Product> products = ctx.menu().products(true, category == null ? null : category.id()).stream()
                .filter(p -> q.isBlank() || p.name().toLowerCase().contains(q) || p.sku().toLowerCase().contains(q)).toList();
        productTable.getItems().setAll(products);
    }

    private void loadRecipe() {
        Product p = recipeProduct.getValue();
        if (p == null) {
            recipeTable.getItems().clear();
            recipeSummary.setText("");
            return;
        }
        List<RecipeLine> lines = ctx.menu().recipe(p.id());
        recipeTable.getItems().setAll(lines);
        BigDecimal cost = lines.stream().map(RecipeLine::cost).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal margin = ctx.menu().margin(p.effectivePrice(), cost);
        recipeSummary.setText("Recipe cost " + Money.fmt(cost) + "  ·  Price " + Money.fmt(p.effectivePrice())
                + "  ·  Margin " + margin.setScale(1, java.math.RoundingMode.HALF_UP) + "%");
        recipeSummary.getStyleClass().setAll(margin.compareTo(BigDecimal.valueOf(20)) < 0 ? "danger-text" : "success-text", "h3");
    }

    private void categoryDialog(Category existing) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Add category" : "Edit category");
        d.setHeaderText(null);
        TextField name = Ui.textField("Category name");
        TextArea desc = Ui.textArea("Description", 2);
        Spinner<Integer> sort = new Spinner<>(0, 999, existing == null ? 10 : existing.sortOrder());
        CheckBox active = new CheckBox("Active (visible on the POS)");
        active.setSelected(existing == null || existing.active());
        String[] imagePath = {existing == null ? null : existing.imagePath()};
        StackPane preview = Ui.imagePane(imagePath[0], 180, 100, name.getText());
        Button pick = Ui.secondary("Choose image", "fas-image", () -> {
            File f = Ui.chooseImage(nav.window());
            if (f != null) {
                try {
                    imagePath[0] = Images.importFood(f.toPath(), "category_" + (existing == null ? "new" : existing.id()));
                    StackPane updated = Ui.imagePane(imagePath[0], 180, 100, "?");
                    preview.getChildren().setAll(updated.getChildren());
                } catch (Exception e) { Ui.error(nav.window(), e); }
            }
        });
        if (existing != null) {
            name.setText(existing.name());
            desc.setText(existing.description() == null ? "" : existing.description());
        }
        VBox box = new VBox(11, preview, pick, Ui.field("Name", name), Ui.field("Description", desc),
                Ui.field("Display order", sort), active);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(420);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Save category", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Label error = Ui.label("", "inline-error");
        box.getChildren().add(error);
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.menu().saveCategory(new Category(existing == null ? 0 : existing.id(), name.getText(), desc.getText(),
                        imagePath[0], sort.getValue(), active.isSelected()), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Category saved."); }
    }

    private void productDialog(Product existing) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Add food item" : "Edit " + existing.name());
        d.setHeaderText(null);
        TextField name = Ui.textField("Food name");
        TextField sku = Ui.textField("SKU / code");
        ComboBox<Category> category = new ComboBox<>();
        category.getItems().setAll(ctx.menu().categories(true));
        category.setMaxWidth(Double.MAX_VALUE);
        category.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Category c) { return c == null ? "" : c.name(); }
            @Override public Category fromString(String s) { return null; }
        });
        TextArea desc = Ui.textArea("Description shown to cashiers", 2);
        TextField cost = Ui.textField("Cost per serving");
        TextField price = Ui.textField("Selling price");
        TextField promo = Ui.textField("Promotional price (optional)");
        TextField tax = Ui.textField("Tax rate %");
        TextField prep = Ui.textField("Prep minutes");
        ComboBox<String> station = new ComboBox<>();
        station.getItems().addAll("Grill", "Fryer", "Pizza", "Sandwich", "Kitchen", "Beverage", "Dessert");
        station.setMaxWidth(Double.MAX_VALUE);
        CheckBox available = new CheckBox("Available for sale");
        CheckBox active = new CheckBox("Active");
        CheckBox track = new CheckBox("Track stock via recipe");
        String[] imagePath = {existing == null ? null : existing.imagePath()};
        StackPane preview = Ui.imagePane(imagePath[0], 220, 130, name.getText());
        Button pick = Ui.secondary("Choose image", "fas-image", () -> {
            File f = Ui.chooseImage(nav.window());
            if (f != null) {
                try {
                    String skuValue = sku.getText() == null || sku.getText().isBlank() ? "food_" + System.currentTimeMillis() : sku.getText();
                    imagePath[0] = Images.importFood(f.toPath(), skuValue);
                    StackPane updated = Ui.imagePane(imagePath[0], 220, 130, "?");
                    preview.getChildren().setAll(updated.getChildren());
                } catch (Exception e) { Ui.error(nav.window(), e); }
            }
        });
        if (existing != null) {
            name.setText(existing.name());
            sku.setText(existing.sku());
            category.getItems().stream().filter(c -> c.id() == existing.categoryId()).findFirst().ifPresent(category::setValue);
            desc.setText(existing.description() == null ? "" : existing.description());
            cost.setText(Money.plain(existing.cost()));
            price.setText(Money.plain(existing.price()));
            promo.setText(existing.promoPrice() == null ? "" : Money.plain(existing.promoPrice()));
            tax.setText(existing.taxRate().stripTrailingZeros().toPlainString());
            prep.setText(String.valueOf(existing.prepMinutes()));
            station.setValue(existing.station());
            available.setSelected(existing.available());
            active.setSelected(existing.active());
            track.setSelected(existing.trackStock());
        } else {
            cost.setText("0"); price.setText("0"); tax.setText("0"); prep.setText("10");
            station.setValue("Kitchen"); available.setSelected(true); active.setSelected(true);
        }
        GridPane form = Ui.form(2);
        form.add(Ui.field("Food name", name), 0, 0);
        form.add(Ui.field("SKU", sku), 1, 0);
        form.add(Ui.field("Category", category), 0, 1);
        form.add(Ui.field("Prep station", station), 1, 1);
        form.add(Ui.field("Description", desc), 0, 2, 2, 1);
        form.add(Ui.field("Cost per serving", cost), 0, 3);
        form.add(Ui.field("Selling price", price), 1, 3);
        form.add(Ui.field("Promo price", promo), 0, 4);
        form.add(Ui.field("Tax rate %", tax), 1, 4);
        form.add(Ui.field("Prep minutes", prep), 0, 5);
        form.add(new VBox(4, available, active, track), 1, 5);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, preview, pick, form, error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(620);
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setFitToHeight(false);
        sp.setPannable(true);
        sp.getStyleClass().add("edge-to-edge");
        var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double maxDialogHeight = Math.max(380, Math.min(540, screenBounds.getHeight() - 170));
        sp.setMaxHeight(maxDialogHeight);
        sp.setPrefHeight(Math.min(540, maxDialogHeight));
        d.setResizable(true);
        d.getDialogPane().setContent(sp);
        ButtonType save = new ButtonType("Save food item", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (category.getValue() == null) throw new IllegalArgumentException("Select a category.");
                BigDecimal promoValue = promo.getText() == null || promo.getText().isBlank() ? null : Money.of(promo.getText());
                ctx.menu().saveProduct(new Product(existing == null ? 0 : existing.id(), category.getValue().id(),
                        category.getValue().name(), sku.getText().trim().toUpperCase(), name.getText(), desc.getText(), imagePath[0],
                        Money.of(cost.getText()), Money.of(price.getText()), promoValue,
                        Integer.parseInt(prep.getText().trim()), station.getValue(), available.isSelected(),
                        Money.of(tax.getText()), track.isSelected(), active.isSelected()), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Food item saved."); }
    }

    private void optionsDialog() {
        Product p = productTable.getSelectionModel().getSelectedItem();
        if (p == null) { warn("Select a food item."); return; }
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(p.name() + " — Portions, Sizes & Add-ons");
        d.setHeaderText(null);
        d.setResizable(true);

        TableView<Size> sizes = Ui.table();
        sizes.getColumns().addAll(
                Ui.col("Portion / Size", Size::name, 140),
                Ui.col("Allowed Range", s -> {
                    if (s.isRanged()) return "Rs. " + Money.plain(s.minPrice()) + " – " + Money.plain(s.maxPrice());
                    return s.price() != null ? "Fixed (Rs. " + Money.plain(s.price()) + ")" : "Extra (+" + Money.fmt(s.extraPrice()) + ")";
                }, 130),
                Ui.col("Configured Price", s -> {
                    if (s.isRanged()) {
                        return s.isConfigured() ? Money.fmt(s.price()) : "Not Configured";
                    }
                    if (s.price() != null) return Money.fmt(s.price());
                    return Money.fmt(p.effectivePrice().add(s.extraPrice()));
                }, 120),
                Ui.col("Status", s -> {
                    if (s.isRanged()) {
                        return s.isConfigured() ? "Configured" : "Config Req.";
                    }
                    return "Ready";
                }, 90)
        );
        sizes.getItems().setAll(ctx.menu().sizes(p.id()));
        sizes.setPrefHeight(180);

        Button configPriceBtn = Ui.primary("Configure Selling Price", "fas-tags", () -> {
            Size sel = sizes.getSelectionModel().getSelectedItem();
            if (sel == null) { warn("Select a portion option to configure its selling price."); return; }
            configurePortionPriceDialog(p, sel, () -> sizes.getItems().setAll(ctx.menu().sizes(p.id())));
        });
        Button removePortionBtn = Ui.ghost("Remove Portion", "fas-trash-alt", () -> {
            Size sel = sizes.getSelectionModel().getSelectedItem();
            if (sel == null) { warn("Select a portion option to remove."); return; }
            if (Ui.confirm(nav.window(), "Remove Portion", "Remove portion \"" + sel.name() + "\" from " + p.name() + "?")) {
                ctx.menu().removePortion(sel.id(), ctx.session());
                sizes.getItems().setAll(ctx.menu().sizes(p.id()));
                toast("Portion removed.");
            }
        });

        TableView<AddOn> addons = Ui.table();
        addons.getColumns().addAll(Ui.col("Add-on", AddOn::name, 170), Ui.moneyCol("Price", AddOn::price, 90));
        addons.getItems().setAll(ctx.menu().addOns(p.id()));
        addons.setPrefHeight(180);

        TextField sizeName = Ui.textField("Portion name (e.g. 250 g, 1 kg)");
        TextField sizePrice = Ui.textField("Price (PKR)");
        CheckBox asFixedPrice = new CheckBox("Fixed portion price");
        asFixedPrice.setSelected(true);
        Button addSize = Ui.secondary("Add portion", "fas-plus", () -> {
            try {
                BigDecimal val = Money.of(sizePrice.getText());
                if (asFixedPrice.isSelected()) {
                    ctx.menu().addPortionWithPrice(p.id(), sizeName.getText(), val, ctx.session());
                } else {
                    ctx.menu().addSize(p.id(), sizeName.getText(), val, ctx.session());
                }
                sizes.getItems().setAll(ctx.menu().sizes(p.id()));
                sizeName.clear(); sizePrice.clear();
            } catch (Exception e) { Ui.error(nav.window(), e); }
        });

        ComboBox<AddOn> allAddons = new ComboBox<>();
        allAddons.getItems().setAll(ctx.menu().allAddOns());
        allAddons.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(AddOn a) { return a == null ? "Select add-on" : a.name() + " (+" + Money.fmt(a.price()) + ")"; }
            @Override public AddOn fromString(String s) { return null; }
        });
        Button link = Ui.secondary("Link add-on", "fas-link", () -> {
            if (allAddons.getValue() == null) return;
            ctx.menu().linkAddOn(p.id(), allAddons.getValue().id(), ctx.session());
            addons.getItems().setAll(ctx.menu().addOns(p.id()));
            toast("Add-on linked.");
        });
        TextField newAddon = Ui.textField("Create new add-on name");
        TextField newPrice = Ui.textField("Price");
        Button create = Ui.ghost("Create add-on", "fas-plus", () -> {
            try {
                ctx.menu().addAddOn(newAddon.getText(), Money.of(newPrice.getText()), Money.ZERO, ctx.session());
                allAddons.getItems().setAll(ctx.menu().allAddOns());
                newAddon.clear(); newPrice.clear();
            } catch (Exception e) { Ui.error(nav.window(), e); }
        });

        VBox left = new VBox(8, Ui.h3("Portions & Sizes"), sizes, new HBox(8, configPriceBtn, removePortionBtn),
                new HBox(6, sizeName, sizePrice, addSize), asFixedPrice);
        VBox right = new VBox(8, Ui.h3("Add-ons"), addons, new HBox(6, allAddons, link), new HBox(6, newAddon, newPrice, create));
        HBox content = new HBox(20, left, right);
        content.setPadding(new Insets(10, 2, 2, 2));
        HBox.setHgrow(left, Priority.ALWAYS); HBox.setHgrow(right, Priority.ALWAYS);
        left.setPrefWidth(490); right.setPrefWidth(380);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");

        d.getDialogPane().setContent(scroll);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        d.showAndWait();
    }

    private void configurePortionPriceDialog(Product p, Size size, Runnable onUpdated) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Configure Selling Price — " + size.name());
        d.setHeaderText(null);

        String rangeInfo = size.isRanged()
                ? "Allowed Range: " + Money.fmt(size.minPrice()) + " – " + Money.fmt(size.maxPrice())
                : "Enter the selling price for this portion.";
        Label info = Ui.muted(rangeInfo);

        TextField priceField = Ui.textField("Selling price (PKR)");
        if (size.price() != null) {
            priceField.setText(Money.plain(size.price()));
        } else if (size.isRanged()) {
            priceField.setPromptText("Enter price between " + Money.plain(size.minPrice()) + " and " + Money.plain(size.maxPrice()));
        }

        Label err = Ui.label("", "inline-error");
        err.setVisible(false); err.setManaged(false);

        VBox form = new VBox(10, Ui.fieldLabel(p.name() + " — " + size.name()), info, Ui.field("Selling Price *", priceField), err);
        form.setPrefWidth(380);
        form.setPadding(new Insets(12));

        d.getDialogPane().setContent(form);
        ButtonType saveBtnType = new ButtonType("Save Selling Price", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveBtnType);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));

        Button saveBtn = (Button) d.getDialogPane().lookupButton(saveBtnType);
        saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                BigDecimal sp = Money.of(priceField.getText());
                if (size.isRanged()) {
                    if (sp.compareTo(size.minPrice()) < 0 || sp.compareTo(size.maxPrice()) > 0) {
                        throw new IllegalArgumentException("Selling price " + Money.fmt(sp) + " is outside the allowed range of "
                                + Money.fmt(size.minPrice()) + " – " + Money.fmt(size.maxPrice()) + ".");
                    }
                }
                ctx.menu().configureVariantPrice(size.id(), sp, ctx.session());
                toast("Selling price for " + size.name() + " set to " + Money.fmt(sp) + ".");
                if (onUpdated != null) onUpdated.run();
            } catch (Exception ex) {
                err.setText(ex.getMessage());
                err.setVisible(true);
                err.setManaged(true);
                ev.consume();
            }
        });

        d.showAndWait();
    }

    private void recipeLineDialog() {
        Product p = recipeProduct.getValue();
        if (p == null) { warn("Select a food item."); return; }
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Add recipe ingredient");
        d.setHeaderText(null);
        ComboBox<Ingredient> ingredient = new ComboBox<>();
        ingredient.getItems().setAll(ctx.inventory().ingredients(true));
        ingredient.setMaxWidth(Double.MAX_VALUE);
        ingredient.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Ingredient i) { return i == null ? "Select ingredient" : i.name() + " (" + i.unit() + ")"; }
            @Override public Ingredient fromString(String s) { return null; }
        });
        TextField qty = Ui.textField("Quantity per serving");
        TextField unit = Ui.textField("Unit (pcs, kg, L)");
        ingredient.valueProperty().addListener((o, a, b) -> { if (b != null) unit.setText(b.unit()); });
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, Ui.field("Ingredient", ingredient), Ui.field("Quantity", qty), Ui.field("Unit", unit), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(400);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Add to recipe", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (ingredient.getValue() == null) throw new IllegalArgumentException("Select an ingredient.");
                ctx.menu().addRecipeLine(p.id(), ingredient.getValue().id(), Money.of(qty.getText()),
                        unit.getText() == null || unit.getText().isBlank() ? ingredient.getValue().unit() : unit.getText(), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { loadRecipe(); toast("Recipe updated."); }
    }

    private void exportMenu() {
        try {
            List<Product> products = ctx.menu().products(false, null);
            ReportData data = new ReportData("MENU_EXPORT", "Menu Export", "All food items",
                    List.of(Column.text("SKU", 14), Column.text("Name", 28), Column.text("Category", 20),
                            Column.text("Description", 40), Column.text("Cost", 12), Column.text("Price", 12),
                            Column.text("Promo Price", 12), Column.text("Tax %", 8), Column.text("Prep Min", 9),
                            Column.text("Station", 14), Column.text("Available", 10), Column.text("Active", 8)),
                    products.stream().map(p -> new Object[]{p.sku(), p.name(), p.categoryName(),
                            p.description() == null ? "" : p.description(), p.cost().toPlainString(), p.price().toPlainString(),
                            p.promoPrice() == null ? "" : p.promoPrice().toPlainString(), p.taxRate().toPlainString(),
                            String.valueOf(p.prepMinutes()), p.station(), p.available() ? "Yes" : "No",
                            p.active() ? "Yes" : "No"}).toList());
            Path path = CsvExporter.export(data, AppPaths.csv());
            ctx.audit().log("MENU_EXPORTED", "products", null, path.getFileName().toString());
            toast("Menu CSV saved: " + path.getFileName());
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void importMenu() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV menu file", "*.csv"));
        File f = fc.showOpenDialog(nav.window());
        if (f == null) return;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) throw new IllegalArgumentException("The selected file is empty.");
            int start = 0;
            for (int i = 0; i < lines.size(); i++) {
                String[] cells = parseCsv(lines.get(i).replace("\uFEFF", ""));
                if (cells.length > 0 && cells[0].trim().equalsIgnoreCase("SKU")) { start = i + 1; break; }
            }
            List<Object[]> parsed = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            Map<String, String> seen = new java.util.HashMap<>();
            List<Category> categories = ctx.menu().categories(false);
            List<Product> existing = ctx.menu().products(false, null);
            for (int i = start; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                String[] c = parseCsv(lines.get(i));
                if (c.length < 5) { errors.add("Line " + (i + 1) + ": not enough columns."); continue; }
                String sku = c[0].trim();
                String name = c.length > 1 ? c[1].trim() : "";
                String category = c.length > 2 ? c[2].trim() : "";
                if (sku.isBlank() || name.isBlank() || category.isBlank()) {
                    errors.add("Line " + (i + 1) + ": SKU, name and category are required."); continue;
                }
                if (seen.containsKey(sku.toUpperCase())) { errors.add("Line " + (i + 1) + ": duplicate SKU " + sku + "."); continue; }
                seen.put(sku.toUpperCase(), sku);
                Category cat = categories.stream().filter(x -> x.name().equalsIgnoreCase(category)).findFirst().orElse(null);
                if (cat == null) { errors.add("Line " + (i + 1) + ": unknown category '" + category + "'."); continue; }
                try {
                    BigDecimal cost = Money.of(c.length > 4 ? c[4] : "0");
                    BigDecimal price = Money.of(c.length > 5 ? c[5] : "0");
                    BigDecimal promo = c.length > 6 && !c[6].isBlank() ? Money.of(c[6]) : null;
                    BigDecimal tax = c.length > 7 ? Money.of(c[7]) : Money.ZERO;
                    int prep = c.length > 8 && !c[8].isBlank() ? Integer.parseInt(c[8].trim()) : 10;
                    String desc = c.length > 3 ? c[3] : "";
                    String station = c.length > 9 && !c[9].isBlank() ? c[9] : "Kitchen";
                    boolean available = c.length <= 10 || !c[10].equalsIgnoreCase("No");
                    boolean active = c.length <= 11 || !c[11].equalsIgnoreCase("No");
                    Product old = existing.stream().filter(p -> p.sku().equalsIgnoreCase(sku)).findFirst().orElse(null);
                    parsed.add(new Object[]{old == null ? "New" : "Update", sku, name, category,
                            cost, price, available, desc, promo, tax, prep, station, active, cat.id(), old});
                } catch (Exception ex) {
                    errors.add("Line " + (i + 1) + ": " + ex.getMessage());
                }
            }
            if (parsed.isEmpty()) throw new IllegalArgumentException("No valid rows found. " + String.join(" ", errors));
            Dialog<ButtonType> d = new Dialog<>();
            d.initOwner(nav.window());
            d.setTitle("Import menu preview");
            d.setHeaderText(null);
            TableView<Object[]> preview = Ui.table();
            preview.getColumns().addAll(
                    Ui.col("Action", r -> String.valueOf(r[0]), 70),
                    Ui.col("SKU", r -> String.valueOf(r[1]), 100),
                    Ui.col("Name", r -> String.valueOf(r[2]), 180),
                    Ui.col("Category", r -> String.valueOf(r[3]), 120),
                    Ui.moneyCol("Cost", r -> (BigDecimal) r[4], 80),
                    Ui.moneyCol("Price", r -> (BigDecimal) r[5], 80),
                    Ui.col("Available", r -> ((Boolean) r[6]) ? "Yes" : "No", 75));
            preview.getItems().setAll(parsed);
            preview.setPrefSize(760, 360);
            Label note = Ui.muted(parsed.size() + " valid row(s). Existing SKUs are updated; new SKUs are created. "
                    + (errors.isEmpty() ? "" : errors.size() + " row(s) skipped: " + String.join(" | ", errors)));
            note.setWrapText(true);
            VBox box = new VBox(10, note, preview);
            box.setPadding(new Insets(10, 2, 2, 2));
            d.getDialogPane().setContent(box);
            ButtonType apply = new ButtonType("Import " + parsed.size() + " item(s)", ButtonBar.ButtonData.OK_DONE);
            d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, apply);
            if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
            else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
            if (d.showAndWait().filter(b -> b == apply).isEmpty()) return;
            for (Object[] row : parsed) {
                Product old = (Product) row[14];
                long catId = (Long) row[13];
                String sku = String.valueOf(row[1]);
                String name = String.valueOf(row[2]);
                String categoryName = String.valueOf(row[3]);
                String imagePath = old == null ? null : old.imagePath();
                if (imagePath == null) {
                    Images.generateFoodImage(sku, name, categoryName, Math.abs(sku.hashCode()) % 50);
                    imagePath = Images.foodRelative(sku);
                }
                ctx.menu().saveProduct(new Product(old == null ? 0 : old.id(), catId, categoryName, sku, name,
                        String.valueOf(row[7]), imagePath, (BigDecimal) row[4], (BigDecimal) row[5],
                        (BigDecimal) row[8], (Integer) row[10], String.valueOf(row[11]), (Boolean) row[6],
                        (BigDecimal) row[9], old != null && old.trackStock(), (Boolean) row[12]), ctx.session());
            }
            ctx.audit().log("MENU_IMPORTED", "products", null, parsed.size() + " row(s) imported from " + f.getName());
            refresh();
            toast(parsed.size() + " menu item(s) imported.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(cur.toString()); cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(String[]::new);
    }
}
