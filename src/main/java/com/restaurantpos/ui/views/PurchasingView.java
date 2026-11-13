package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.Inventory.*;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class PurchasingView extends AppView {
    private final TabPane tabs = new TabPane();
    private final TableView<Supplier> supplierTable = Ui.table();
    private final TableView<PurchaseOrder> orderTable = Ui.table();
    private final TableView<Object[]> paymentTable = Ui.table();
    private final Label supplierOutstanding = Ui.muted("");

    public PurchasingView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Purchasing & Suppliers", "Purchase orders, goods receiving, supplier balances and payments.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Suppliers", suppliersTab()), new Tab("Purchase orders", ordersTab()),
                new Tab("Supplier payments", paymentsTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        supplierTable.getItems().setAll(ctx.purchasing().suppliers(false));
        orderTable.getItems().setAll(ctx.purchasing().orders());
        paymentTable.getItems().setAll(ctx.purchasing().payments(null));
    }

    private VBox suppliersTab() {
        supplierTable.getColumns().addAll(
                Ui.col("Supplier", Supplier::name, 200),
                Ui.col("Contact", s -> s.contact() == null ? "" : s.contact(), 140),
                Ui.col("Phone", s -> s.phone() == null ? "" : s.phone(), 130),
                Ui.col("Email", s -> s.email() == null ? "" : s.email(), 200),
                Ui.col("Terms", s -> s.terms() == null ? "" : s.terms(), 100),
                Ui.col("Status", s -> s.active() ? "Active" : "Inactive", 80));
        supplierTable.setPlaceholder(Ui.empty("No suppliers", "Add suppliers to create purchase orders."));
        supplierTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == null) supplierOutstanding.setText("");
            else supplierOutstanding.setText("Outstanding: " + Money.fmt(ctx.purchasing().outstanding(b.id())));
        });
        Button add = Ui.primary("Add supplier", "fas-plus", () -> supplierDialog(null));
        Button edit = Ui.secondary("Edit", "fas-edit", () -> {
            Supplier s = supplierTable.getSelectionModel().getSelectedItem();
            if (s == null) { warn("Select a supplier."); return; }
            supplierDialog(s);
        });
        Button delete = Ui.danger("Delete", "fas-trash-alt", () -> {
            Supplier s = supplierTable.getSelectionModel().getSelectedItem();
            if (s == null) { warn("Select a supplier."); return; }
            if (!ctx.canManage()) { warn("Only managers can delete suppliers."); return; }
            
            boolean hasOrders = ctx.purchasing().hasActiveOrders(s.id());
            if (hasOrders) {
                Ui.error(nav.window(), "Cannot delete supplier", 
                    s.name() + " has " + ctx.purchasing().activeOrderCount(s.id()) + " active purchase order(s). " +
                    "Please complete or cancel all orders first.");
                return;
            }
            
            boolean hasPayments = ctx.purchasing().hasPayments(s.id());
            if (hasPayments) {
                String msg = "Delete supplier \"" + s.name() + "\"?\n\n" +
                            "This supplier has payment history. This will:\n" +
                            "• Remove the supplier from the system\n" +
                            "• Keep all historical payment records intact\n\n" +
                            "This action is irreversible.";
                if (!Ui.confirm(nav.window(), "Delete supplier", msg)) return;
            } else {
                if (!Ui.confirm(nav.window(), "Delete supplier", 
                    "Delete supplier \"" + s.name() + "\"?\n\nThis action is irreversible.")) return;
            }
            
            try {
                ctx.purchasing().deleteSupplier(s.id(), ctx.session());
                refresh();
                toast("Supplier \"" + s.name() + "\" has been deleted.");
            } catch (Exception e) { 
                Ui.error(nav.window(), "Delete failed", e.getMessage());
            }
        });
        Button pay = Ui.ghost("Record payment", "fas-money-bill", () -> {
            Supplier s = supplierTable.getSelectionModel().getSelectedItem();
            if (s == null) { warn("Select a supplier."); return; }
            paymentDialog(s);
        });
        HBox bar = new HBox(8, add, edit, delete, pay, Ui.spacer(), supplierOutstanding);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, supplierTable);
        VBox.setVgrow(supplierTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox ordersTab() {
        orderTable.getColumns().addAll(
                Ui.col("PO number", PurchaseOrder::poNo, 140),
                Ui.col("Supplier", PurchaseOrder::supplierName, 180),
                Ui.col("Status", PurchaseOrder::status, 90),
                Ui.col("Ordered", p -> Ui.date(p.orderDate()), 100),
                Ui.col("Expected", p -> Ui.date(p.expectedDate()), 100),
                Ui.moneyCol("Total", PurchaseOrder::total, 110));
        orderTable.setPlaceholder(Ui.empty("No purchase orders", "Create a purchase order to restock ingredients."));
        Button create = Ui.primary("New purchase order", "fas-plus", this::purchaseDialog);
        Button edit = Ui.secondary("Edit", "fas-edit", () -> {
            PurchaseOrder po = orderTable.getSelectionModel().getSelectedItem();
            if (po == null) { warn("Select a purchase order."); return; }
            if (!"DRAFT".equals(po.status())) {
                Ui.error(nav.window(), "Cannot edit", "Only draft orders can be modified.");
                return;
            }
            editPurchaseDialog(po);
        });
        Button delete = Ui.danger("Delete", "fas-trash-alt", () -> {
            PurchaseOrder po = orderTable.getSelectionModel().getSelectedItem();
            if (po == null) { warn("Select a purchase order."); return; }
            if (!ctx.canManage()) { warn("Only managers can delete purchase orders."); return; }
            
            if (!"DRAFT".equals(po.status())) {
                Ui.error(nav.window(), "Cannot delete", "Only draft orders can be deleted. Cancel this order instead.");
                return;
            }
            
            if (Ui.confirm(nav.window(), "Delete purchase order", 
                "Delete purchase order " + po.poNo() + " from " + po.supplierName() + "?\n\n" +
                "This will permanently remove the draft order.")) {
                try {
                    ctx.purchasing().deleteOrder(po.id(), ctx.session());
                    refresh();
                    toast("Purchase order " + po.poNo() + " has been deleted.");
                } catch (Exception e) { 
                    Ui.error(nav.window(), "Delete failed", e.getMessage());
                }
            }
        });
        Button approve = Ui.secondary("Approve", "fas-check", () -> {
            PurchaseOrder po = orderTable.getSelectionModel().getSelectedItem();
            if (po == null) { warn("Select a purchase order."); return; }
            try { ctx.purchasing().approve(po.id(), ctx.session()); refresh(); toast("Purchase order approved."); }
            catch (Exception e) { Ui.error(nav.window(), e); }
        });
        Button receive = Ui.secondary("Receive stock", "fas-dolly", () -> {
            PurchaseOrder po = orderTable.getSelectionModel().getSelectedItem();
            if (po == null) { warn("Select a purchase order."); return; }
            if (!Ui.confirm(nav.window(), "Receive purchase", "Receive all outstanding lines for " + po.poNo()
                    + "?\n\nThis updates ingredient stock and average costs.")) return;
            try { ctx.purchasing().receive(po.id(), "Received via purchasing screen", ctx.session()); refresh(); toast("Stock received."); }
            catch (Exception e) { Ui.error(nav.window(), e); }
        });
        Button lines = Ui.ghost("View lines", "fas-list", () -> {
            PurchaseOrder po = orderTable.getSelectionModel().getSelectedItem();
            if (po == null) { warn("Select a purchase order."); return; }
            linesDialog(po);
        });
        HBox bar = new HBox(8, create, edit, delete, approve, receive, lines);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, orderTable);
        VBox.setVgrow(orderTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox paymentsTab() {
        paymentTable.getColumns().addAll(
                Ui.col("Supplier", r -> String.valueOf(r[1]), 180),
                Ui.moneyCol("Amount", r -> new BigDecimal(String.valueOf(r[2])), 110),
                Ui.col("Method", r -> String.valueOf(r[3]), 110),
                Ui.col("Reference", r -> r[4] == null ? "" : String.valueOf(r[4]), 150),
                Ui.col("Paid at", r -> String.valueOf(r[5]), 160),
                Ui.col("Notes", r -> r[6] == null ? "" : String.valueOf(r[6]), 200));
        paymentTable.setPlaceholder(Ui.empty("No supplier payments", "Record payments against supplier balances."));
        Button pay = Ui.primary("Record supplier payment", "fas-plus", () -> {
            Supplier s = supplierTable.getSelectionModel().getSelectedItem();
            paymentDialog(s);
        });
        Button delete = Ui.danger("Delete", "fas-trash-alt", () -> {
            Object[] p = paymentTable.getSelectionModel().getSelectedItem();
            if (p == null) { warn("Select a payment to delete."); return; }
            if (!ctx.canManage()) { warn("Only managers can delete payments."); return; }
            long paymentId = (Long) p[0];
            String supplierName = String.valueOf(p[1]);
            BigDecimal amount = new BigDecimal(String.valueOf(p[2]));
            if (Ui.confirm(nav.window(), "Delete payment", "Delete payment of " + Money.fmt(amount) + " for " + supplierName + "?\n\nThis will adjust the supplier outstanding balance.")) {
                try {
                    ctx.purchasing().deletePayment(paymentId, ctx.session());
                    refresh();
                    toast("Payment of " + Money.fmt(amount) + " has been deleted.");
                } catch (Exception e) {
                    Ui.error(nav.window(), "Delete failed", e.getMessage());
                }
            }
        });
        HBox bar = new HBox(8, pay, delete, Ui.muted("Supplier balances are calculated from approved/received purchases less payments."));
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, bar, paymentTable);
        VBox.setVgrow(paymentTable, Priority.ALWAYS);
        box.setPadding(new Insets(14));
        return box;
    }

    private void supplierDialog(Supplier existing) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Add supplier" : "Edit supplier");
        d.setHeaderText(null);
        TextField name = Ui.textField("Supplier name");
        TextField contact = Ui.textField("Contact person");
        TextField phone = Ui.textField("Phone");
        TextField email = Ui.textField("Email");
        TextField address = Ui.textField("Address");
        TextField terms = Ui.textField("Payment terms (e.g. Net 15)");
        CheckBox active = new CheckBox("Active");
        active.setSelected(existing == null || existing.active());
        if (existing != null) {
            name.setText(existing.name()); contact.setText(existing.contact()); phone.setText(existing.phone());
            email.setText(existing.email()); address.setText(existing.address()); terms.setText(existing.terms());
        }
        GridPane form = Ui.form(2);
        form.add(Ui.field("Name", name), 0, 0);
        form.add(Ui.field("Contact person", contact), 1, 0);
        form.add(Ui.field("Phone", phone), 0, 1);
        form.add(Ui.field("Email", email), 1, 1);
        form.add(Ui.field("Address", address), 0, 2, 2, 1);
        form.add(Ui.field("Payment terms", terms), 0, 3);
        form.add(active, 1, 3);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, form, error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(500);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Save supplier", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.purchasing().saveSupplier(new Supplier(existing == null ? 0 : existing.id(), name.getText(), contact.getText(),
                        phone.getText(), email.getText(), address.getText(), terms.getText(), active.isSelected()), ctx.session());
            } catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Supplier saved."); }
    }

    private void purchaseDialog() {
        purchaseOrderDialog(null);
    }

    private void editPurchaseDialog(PurchaseOrder po) {
        purchaseOrderDialog(po);
    }

    private void purchaseOrderDialog(PurchaseOrder existing) {
        List<Supplier> suppliers = ctx.purchasing().suppliers(true);
        if (suppliers.isEmpty()) { warn("Add a supplier first."); return; }
        List<Ingredient> ingredients = ctx.inventory().ingredients(true);
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "New purchase order" : "Edit purchase order " + existing.poNo());
        d.setHeaderText(null);
        ComboBox<Supplier> supplier = new ComboBox<>();
        supplier.getItems().setAll(suppliers);
        supplier.setMaxWidth(Double.MAX_VALUE);
        supplier.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Supplier s) { return s == null ? "Select supplier" : s.name(); }
            @Override public Supplier fromString(String s) { return null; }
        });
        if (existing != null) {
            suppliers.stream().filter(s -> s.id() == existing.supplierId()).findFirst().ifPresent(supplier::setValue);
        }
        DatePicker expected = Ui.datePicker(existing == null || existing.expectedDate() == null ? LocalDate.now().plusDays(2) : existing.expectedDate());
        TextField tax = Ui.textField("Tax amount (PKR)");
        tax.setText(existing == null ? "0" : Money.plain(existing.tax()));
        TextArea notes = Ui.textArea("Notes", 2);
        if (existing != null && existing.notes() != null) notes.setText(existing.notes());
        VBox lines = new VBox(7);
        Label total = Ui.label(existing == null ? "Rs 0.00" : Money.fmt(existing.total()), "h2");
        Runnable recalc = () -> {
            BigDecimal sum = Money.ZERO;
            for (var node : lines.getChildren()) {
                if (!(node instanceof HBox row)) continue;
                TextField q = (TextField) row.getChildren().get(1);
                TextField c = (TextField) row.getChildren().get(2);
                try { sum = sum.add(Money.mul(Money.of(q.getText()), Money.of(c.getText()))); } catch (Exception ignored) {}
            }
            total.setText(Money.fmt(sum.add(Money.of(tax.getText()))));
        };
        java.util.function.BiConsumer<Ingredient, BigDecimal[]> addLineFn = (defIng, defValues) -> {
            HBox row = new HBox(6);
            ComboBox<Ingredient> ing = new ComboBox<>();
            ing.getItems().setAll(ingredients);
            ing.setPrefWidth(230);
            ing.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(Ingredient i) { return i == null ? "Ingredient" : i.name() + " (" + i.unit() + ")"; }
                @Override public Ingredient fromString(String s) { return null; }
            });
            if (defIng != null) ing.setValue(defIng);
            ing.valueProperty().addListener((o, a, b) -> { if (b != null) recalc.run(); });
            TextField qty = Ui.textField("Qty");
            qty.setPrefWidth(80);
            if (defValues != null) qty.setText(defValues[0].stripTrailingZeros().toPlainString());
            TextField cost = Ui.textField("Unit cost");
            cost.setPrefWidth(100);
            if (defValues != null) cost.setText(Money.plain(defValues[1]));
            qty.textProperty().addListener((o, a, b) -> recalc.run());
            cost.textProperty().addListener((o, a, b) -> recalc.run());
            Button remove = Ui.iconButton("fas-times", "Remove line", () -> { lines.getChildren().remove(row); recalc.run(); });
            row.getChildren().addAll(ing, qty, cost, remove);
            lines.getChildren().add(row);
        };
        if (existing == null) {
            addLineFn.accept(null, null);
        } else {
            List<PurchaseLine> poLines = ctx.purchasing().lines(existing.id());
            for (PurchaseLine pl : poLines) {
                Ingredient matched = ingredients.stream().filter(i -> i.id() == pl.ingredientId()).findFirst().orElse(null);
                addLineFn.accept(matched, new BigDecimal[]{pl.qty(), pl.unitCost()});
            }
            if (poLines.isEmpty()) addLineFn.accept(null, null);
        }
        Button add = Ui.ghost("Add line", "fas-plus", () -> addLineFn.accept(null, null));
        tax.textProperty().addListener((o, a, b) -> recalc.run());
        GridPane form = Ui.form(2);
        form.add(Ui.field("Supplier", supplier), 0, 0);
        form.add(Ui.field("Expected date", expected), 1, 0);
        form.add(Ui.field("Tax", tax), 0, 1);
        form.add(Ui.field("Notes", notes), 1, 1);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        HBox totalRow = new HBox(10, Ui.muted("Purchase total"), Ui.spacer(), total);
        totalRow.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(12, form, new Separator(), Ui.label("PURCHASE LINES", "field-label"), lines, add, totalRow, error);
        content.setPadding(new Insets(10, 8, 10, 8));
        content.setPrefWidth(650);

        ScrollPane contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(false);
        contentScroll.setPannable(true);
        contentScroll.getStyleClass().add("edge-to-edge");
        var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double maxDialogHeight = Math.max(380, Math.min(540, screenBounds.getHeight() - 170));
        contentScroll.setMaxHeight(maxDialogHeight);
        contentScroll.setPrefHeight(Math.min(540, maxDialogHeight));
        d.setResizable(true);
        d.getDialogPane().setContent(contentScroll);
        ButtonType save = new ButtonType(existing == null ? "Create purchase order" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (supplier.getValue() == null) throw new IllegalArgumentException("Select a supplier.");
                Map<Long, BigDecimal[]> map = new LinkedHashMap<>();
                for (var node : lines.getChildren()) {
                    HBox row = (HBox) node;
                    ComboBox<?> ing = (ComboBox<?>) row.getChildren().get(0);
                    Ingredient selected = (Ingredient) ing.getValue();
                    if (selected == null) throw new IllegalArgumentException("Select an ingredient on every line.");
                    TextField q = (TextField) row.getChildren().get(1);
                    TextField c = (TextField) row.getChildren().get(2);
                    BigDecimal qty = Money.of(q.getText());
                    BigDecimal cost = Money.of(c.getText());
                    if (qty.signum() <= 0) throw new IllegalArgumentException("Purchase quantity must be greater than zero.");
                    map.put(selected.id(), new BigDecimal[]{qty, cost});
                }
                if (existing == null) {
                    ctx.purchasing().createPurchaseOrder(supplier.getValue().id(), expected.getValue(), Money.of(tax.getText()),
                            notes.getText(), map, ctx.session());
                } else {
                    ctx.purchasing().updatePurchaseOrder(existing.id(), supplier.getValue().id(), expected.getValue(), Money.of(tax.getText()),
                            notes.getText(), map, ctx.session());
                }
            } catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) {
            refresh();
            toast(existing == null ? "Purchase order created as draft." : "Purchase order " + existing.poNo() + " updated.");
        }
    }

    private void linesDialog(PurchaseOrder po) {
        Dialog<Void> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(po.poNo() + " — " + po.supplierName());
        d.setResizable(true);
        TableView<PurchaseLine> table = Ui.table();
        table.getColumns().addAll(
                Ui.col("Ingredient", PurchaseLine::ingredientName, 220),
                Ui.col("Ordered qty", l -> l.qty().stripTrailingZeros().toPlainString(), 110),
                Ui.moneyCol("Unit cost", PurchaseLine::unitCost, 110),
                Ui.moneyCol("Line total", PurchaseLine::lineTotal, 110),
                Ui.col("Received", l -> l.receivedQty().stripTrailingZeros().toPlainString(), 100));
        table.getItems().setAll(ctx.purchasing().lines(po.id()));
        table.setPrefSize(720, 320);
        VBox box = new VBox(10, Ui.muted("Status: " + po.status() + "  ·  Total " + Money.fmt(po.total())), table);
        box.setPadding(new Insets(10, 2, 2, 2));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        d.showAndWait();
    }

    private void paymentDialog(Supplier preset) {
        List<Supplier> suppliers = ctx.purchasing().suppliers(true);
        if (suppliers.isEmpty()) { warn("Add a supplier first."); return; }
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Supplier payment");
        d.setHeaderText(null);
        ComboBox<Supplier> supplier = new ComboBox<>();
        supplier.getItems().setAll(suppliers);
        supplier.setValue(preset == null ? suppliers.get(0) : suppliers.stream().filter(s -> s.id() == preset.id()).findFirst().orElse(suppliers.get(0)));
        supplier.setMaxWidth(Double.MAX_VALUE);
        supplier.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Supplier s) { return s == null ? "Supplier" : s.name(); }
            @Override public Supplier fromString(String s) { return null; }
        });
        Label outstanding = Ui.muted("");
        Runnable refreshOutstanding = () -> {
            if (supplier.getValue() != null) outstanding.setText("Outstanding balance: " + Money.fmt(ctx.purchasing().outstanding(supplier.getValue().id())));
        };
        supplier.valueProperty().addListener((o, a, b) -> refreshOutstanding.run());
        refreshOutstanding.run();
        ComboBox<PurchaseOrder> po = new ComboBox<>();
        po.getItems().add(null);
        po.getItems().addAll(ctx.purchasing().orders().stream().filter(p -> p.supplierId() == supplier.getValue().id()).toList());
        po.setMaxWidth(Double.MAX_VALUE);
        po.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(PurchaseOrder p) { return p == null ? "No specific PO" : p.poNo() + " · " + Money.fmt(p.total()); }
            @Override public PurchaseOrder fromString(String s) { return null; }
        });
        TextField amount = Ui.textField("Amount");
        ComboBox<String> method = new ComboBox<>();
        method.getItems().addAll("Cash", "Bank Transfer", "Cheque", "Easypaisa", "JazzCash");
        method.setValue("Bank Transfer");
        method.setMaxWidth(Double.MAX_VALUE);
        TextField reference = Ui.textField("Reference / cheque number");
        TextField notes = Ui.textField("Notes");
        GridPane form = Ui.form(2);
        form.add(Ui.field("Supplier", supplier), 0, 0);
        form.add(Ui.field("Purchase order", po), 1, 0);
        form.add(Ui.field("Amount", amount), 0, 1);
        form.add(Ui.field("Payment method", method), 1, 1);
        form.add(Ui.field("Reference", reference), 0, 2);
        form.add(Ui.field("Notes", notes), 1, 2);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, outstanding, form, error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(540);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Record payment", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (supplier.getValue() == null) throw new IllegalArgumentException("Select a supplier.");
                ctx.purchasing().addPayment(supplier.getValue().id(), po.getValue() == null ? null : po.getValue().id(),
                        Money.of(amount.getText()), method.getValue(), reference.getText(), ctx.session());
            } catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Supplier payment recorded."); }
    }
}
