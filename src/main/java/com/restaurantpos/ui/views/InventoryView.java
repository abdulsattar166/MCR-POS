package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.Inventory.Ingredient;
import com.restaurantpos.model.Inventory.StockMovement;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InventoryView extends AppView {
    private final TabPane tabs = new TabPane();
    private final TableView<Ingredient> ingredientTable = Ui.table();
    private final TableView<StockMovement> movementTable = Ui.table();
    private final ComboBox<Ingredient> movementFilter = new ComboBox<>();
    private final TableView<Object[]> wastageTable = Ui.table();
    private final Label stockValue = Ui.label("", "h2");
    private final Label lowStock = Ui.chip("", "chip-red");

    public InventoryView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Inventory", "Ingredients, stock valuation, movements, wastage and physical counts.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Ingredients", ingredientsTab()), new Tab("Stock movements", movementsTab()),
                new Tab("Wastage", wastageTab()), new Tab("Stock count", countTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        List<Ingredient> ingredients = ctx.inventory().ingredients(false);
        ingredientTable.getItems().setAll(ingredients);
        stockValue.setText("Stock value " + Money.fmt(ctx.inventory().stockValue()));
        long low = ingredients.stream().filter(Ingredient::low).count();
        lowStock.setText(low + " low-stock item(s)");
        lowStock.getStyleClass().setAll("chip", low > 0 ? "chip-red" : "chip-green");
        Ingredient selected = movementFilter.getValue();
        movementFilter.getItems().setAll(ingredients);
        movementFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Ingredient i) { return i == null ? "All ingredients" : i.name(); }
            @Override public Ingredient fromString(String s) { return null; }
        });
        if (selected != null) ingredients.stream().filter(i -> i.id() == selected.id()).findFirst().ifPresent(movementFilter::setValue);
        loadMovements();
        loadWastage();
    }

    private VBox ingredientsTab() {
        ingredientTable.getColumns().addAll(
                Ui.col("SKU", Ingredient::sku, 90),
                Ui.col("Ingredient", Ingredient::name, 190),
                Ui.col("Unit", Ingredient::unit, 60),
                Ui.col("Stock", i -> i.stockQty().stripTrailingZeros().toPlainString(), 80),
                Ui.col("Min", i -> i.minQty().stripTrailingZeros().toPlainString(), 60),
                Ui.moneyCol("Avg cost", Ingredient::avgCost, 90),
                Ui.moneyCol("Value", Ingredient::value, 100),
                Ui.col("Status", i -> i.out() ? "Out of stock" : i.low() ? "Low" : "OK", 100));
        ingredientTable.setPlaceholder(Ui.empty("No ingredients", "Add ingredients to use recipes and stock control."));
        Button add = Ui.primary("Add ingredient", "fas-plus", () -> ingredientDialog(null));
        Button edit = Ui.secondary("Edit", "fas-edit", () -> {
            Ingredient i = ingredientTable.getSelectionModel().getSelectedItem();
            if (i == null) { warn("Select an ingredient."); return; }
            ingredientDialog(i);
        });
        Button adjust = Ui.secondary("Adjust stock", "fas-sliders-h", () -> {
            Ingredient i = ingredientTable.getSelectionModel().getSelectedItem();
            if (i == null) { warn("Select an ingredient."); return; }
            adjustDialog(i);
        });
        Button waste = Ui.ghost("Record wastage", "fas-trash", () -> {
            Ingredient i = ingredientTable.getSelectionModel().getSelectedItem();
            if (i == null) { warn("Select an ingredient."); return; }
            wastageDialog(i);
        });
        Button delete = Ui.ghost("Delete", "fas-trash-alt", () -> {
            Ingredient i = ingredientTable.getSelectionModel().getSelectedItem();
            if (i == null) { warn("Select an ingredient to delete."); return; }
            
            // Check if ingredient is used in recipes
            boolean usedInRecipes = ctx.inventory().ingredientHasRecipes(i.id());
            if (usedInRecipes) {
                Ui.error(nav.window(), "Cannot delete ingredient", 
                    i.name() + " is used in product recipes. " +
                    "Remove it from all recipes first or deactivate it instead.");
                return;
            }
            
            String msg = "Permanently delete ingredient \"" + i.name() + "\"?\n\n" +
                        "This will remove:\n" +
                        "• The ingredient from inventory\n" +
                        "• All stock movement history\n" +
                        "• Current stock value: " + Money.fmt(i.value()) + "\n\n" +
                        "This action cannot be undone.";
            if (Ui.confirm(nav.window(), "Delete ingredient", msg)) {
                try {
                    ctx.inventory().deleteIngredient(i.id(), ctx.session());
                    refresh();
                    toast("Ingredient \"" + i.name() + "\" has been deleted.");
                } catch (Exception e) { 
                    Ui.error(nav.window(), "Delete failed", e.getMessage());
                }
            }
        });
        HBox bar = new HBox(8, add, edit, adjust, waste, delete, Ui.spacer(), lowStock, stockValue);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, ingredientTable);
        VBox.setVgrow(ingredientTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox movementsTab() {
        movementTable.getColumns().addAll(
                Ui.col("Date", m -> Ui.dateTime(m.createdAt()), 140),
                Ui.col("Ingredient", StockMovement::ingredientName, 170),
                Ui.col("Type", StockMovement::type, 150),
                Ui.col("Quantity", m -> m.qty().stripTrailingZeros().toPlainString(), 90),
                Ui.moneyCol("Unit cost", StockMovement::unitCost, 100),
                Ui.col("Reference", m -> (m.refType() == null ? "" : m.refType()) + (m.refId() == null ? "" : " #" + m.refId()), 130),
                Ui.col("By", StockMovement::createdBy, 140));
        movementTable.setPlaceholder(Ui.empty("No stock movements", "Receiving, sales consumption and adjustments appear here."));
        movementFilter.setPrefWidth(220);
        movementFilter.setOnAction(e -> loadMovements());
        HBox bar = new HBox(9, Ui.fieldLabel("Ingredient"), movementFilter, Ui.spacer(),
                Ui.muted("Newest 400 movements"));
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, movementTable);
        VBox.setVgrow(movementTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox wastageTab() {
        wastageTable.getColumns().addAll(
                Ui.col("Date", r -> Ui.dateTime(java.time.LocalDateTime.parse(String.valueOf(r[7]).replace(' ', 'T'))), 140),
                Ui.col("Ingredient", r -> String.valueOf(r[1]), 170),
                Ui.col("Qty", r -> String.valueOf(r[2]) + " " + r[3], 90),
                Ui.moneyCol("Cost", r -> new BigDecimal(String.valueOf(r[4])), 100),
                Ui.col("Reason", r -> String.valueOf(r[5]), 240),
                Ui.col("By", r -> String.valueOf(r[6]), 140));
        wastageTable.setPlaceholder(Ui.empty("No wastage recorded", "Log spoilage and preparation waste here."));
        Button add = Ui.primary("Record wastage", "fas-plus", () -> {
            Ingredient i = ingredientTable.getSelectionModel().getSelectedItem();
            if (i == null) { warn("Select an ingredient from the Ingredients tab first."); return; }
            wastageDialog(i);
        });
        HBox bar = new HBox(10, add, Ui.muted("Wastage is deducted from stock and valued at average cost."));
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, wastageTable);
        VBox.setVgrow(wastageTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox countTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(14));
        box.getChildren().addAll(Ui.h2("Physical stock count"),
                Ui.muted("Enter counted quantities for any ingredients you have counted. Differences are recorded as audited stock-count movements."));
        VBox rows = new VBox(6);
        for (Ingredient i : ctx.inventory().ingredients(true)) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            Label name = Ui.label(i.name(), "h3");
            name.setPrefWidth(190);
            Label system = Ui.muted("System: " + i.stockQty().stripTrailingZeros().toPlainString() + " " + i.unit());
            system.setPrefWidth(180);
            TextField counted = Ui.textField("Counted " + i.unit());
            counted.setPrefWidth(150);
            counted.setUserData(new Object[]{i.id(), i.unit()});
            row.getChildren().addAll(name, system, counted);
            row.setUserData(counted);
            rows.getChildren().add(row);
        }
        TextArea notes = Ui.textArea("Count notes", 2);
        Button save = Ui.primary("Post stock count", "fas-clipboard-check", () -> {
            Map<Long, BigDecimal> counted = new LinkedHashMap<>();
            for (var node : rows.getChildren()) {
                HBox row = (HBox) node;
                TextField field = (TextField) row.getChildren().get(2);
                if (field.getText() == null || field.getText().isBlank()) continue;
                Object[] meta = (Object[]) field.getUserData();
                try { counted.put((Long) meta[0], Money.of(field.getText())); }
                catch (Exception ex) { Ui.error(nav.window(), "Invalid quantity", "Check the counted quantity for " + ((HBox) node).getChildren().get(0)); return; }
            }
            try {
                if (counted.isEmpty()) throw new IllegalArgumentException("Enter at least one counted quantity.");
                ctx.inventory().stockCount(counted, notes.getText(), ctx.session());
                refresh();
                toast("Stock count posted.");
            } catch (Exception e) { Ui.error(nav.window(), e); }
        });
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(380);
        scroll.getStyleClass().add("edge-to-edge");
        box.getChildren().addAll(scroll, notes, save);
        return box;
    }

    private void loadMovements() {
        movementTable.getItems().setAll(ctx.inventory().movements(
                movementFilter.getValue() == null ? null : movementFilter.getValue().id(), 400));
    }

    private void loadWastage() {
        wastageTable.getItems().setAll(ctx.inventory().wastage(LocalDate.now().minusDays(90), LocalDate.now()));
    }

    private void ingredientDialog(Ingredient existing) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Add ingredient" : "Edit " + existing.name());
        d.setHeaderText(null);
        TextField sku = Ui.textField("SKU");
        TextField name = Ui.textField("Ingredient name");
        ComboBox<String> unit = new ComboBox<>();
        unit.getItems().addAll("pcs", "kg", "g", "L", "ml", "pack");
        unit.setEditable(true);
        unit.setMaxWidth(Double.MAX_VALUE);
        TextField min = Ui.textField("Minimum level");
        TextField max = Ui.textField("Maximum level");
        TextField cost = Ui.textField("Average unit cost");
        TextField opening = Ui.textField("Opening stock (new ingredient only)");
        CheckBox active = new CheckBox("Active");
        if (existing != null) {
            sku.setText(existing.sku()); name.setText(existing.name()); unit.setValue(existing.unit());
            min.setText(Money.plain(existing.minQty())); max.setText(Money.plain(existing.maxQty()));
            cost.setText(Money.plain(existing.avgCost())); active.setSelected(existing.active());
            opening.setDisable(true); opening.setText("0");
        } else {
            min.setText("0"); max.setText("0"); cost.setText("0"); opening.setText("0"); active.setSelected(true);
        }
        GridPane form = Ui.form(2);
        form.add(Ui.field("SKU", sku), 0, 0);
        form.add(Ui.field("Name", name), 1, 0);
        form.add(Ui.field("Unit", unit), 0, 1);
        form.add(Ui.field("Average cost", cost), 1, 1);
        form.add(Ui.field("Minimum", min), 0, 2);
        form.add(Ui.field("Maximum", max), 1, 2);
        form.add(Ui.field("Opening stock", opening), 0, 3);
        form.add(active, 1, 3);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, form, error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(480);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Save ingredient", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                long id = ctx.inventory().saveIngredient(new Ingredient(existing == null ? 0 : existing.id(),
                        sku.getText().trim().toUpperCase(), name.getText(), unit.getValue(), existing == null ? Money.ZERO : existing.stockQty(),
                        Money.of(min.getText()), Money.of(max.getText()), Money.of(cost.getText()),
                        active.isSelected()), ctx.session());
                if (existing == null && Money.of(opening.getText()).signum() > 0) {
                    ctx.inventory().adjust(id, Money.of(opening.getText()), "Opening stock", ctx.session());
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Ingredient saved."); }
    }

    private void adjustDialog(Ingredient i) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Adjust " + i.name());
        d.setHeaderText(null);
        TextField delta = Ui.textField("Quantity change (+ or -)");
        TextArea reason = Ui.textArea("Reason (required)", 2);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, Ui.muted("Current stock: " + i.stockQty().stripTrailingZeros().toPlainString() + " " + i.unit()
                + "  ·  average cost " + Money.fmt(i.avgCost())), Ui.field("Adjustment", delta), Ui.field("Reason", reason), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(430);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Post adjustment", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try { ctx.inventory().adjust(i.id(), Money.of(delta.getText()), reason.getText(), ctx.session()); }
            catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Stock adjusted."); }
    }

    private void wastageDialog(Ingredient i) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Record wastage — " + i.name());
        d.setHeaderText(null);
        TextField qty = Ui.textField("Quantity wasted");
        TextArea reason = Ui.textArea("Reason (required)", 2);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, Ui.muted("Wastage is valued at the current average cost of " + Money.fmt(i.avgCost()) + " per " + i.unit() + "."),
                Ui.field("Quantity (" + i.unit() + ")", qty), Ui.field("Reason", reason), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(430);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Record wastage", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try { ctx.inventory().wastage(i.id(), Money.of(qty.getText()), reason.getText(), ctx.session()); }
            catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Wastage recorded."); }
    }
}
