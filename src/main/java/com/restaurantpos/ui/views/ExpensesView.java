package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.model.Inventory.Expense;
import com.restaurantpos.model.Inventory.ExpenseCategory;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

public final class ExpensesView extends AppView {
    private final DatePicker from = Ui.datePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker to = Ui.datePicker(LocalDate.now());
    private final ComboBox<ExpenseCategory> categoryFilter = new ComboBox<>();
    private final TableView<Expense> table = Ui.table();
    private final Label total = Ui.label("", "h2");
    private final Label count = Ui.muted("");

    public ExpensesView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Expenses", "Rent, utilities, salaries and other operating costs.",
                Ui.primary("Record expense", "fas-plus", () -> expenseDialog(null)),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        categoryFilter.setPrefWidth(190);
        categoryFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ExpenseCategory c) { return c == null ? "All categories" : c.name(); }
            @Override public ExpenseCategory fromString(String s) { return null; }
        });
        categoryFilter.setOnAction(e -> load());
        from.valueProperty().addListener((o, a, b) -> load());
        to.valueProperty().addListener((o, a, b) -> load());
        Button addCategoryBtn = Ui.button("Add category", "fas-tags", "ghost-button", this::categoryDialog);
        addCategoryBtn.setVisible(ctx.canManage());
        addCategoryBtn.setManaged(ctx.canManage());
        FlowPane filters = new FlowPane(8, 8, from, to, categoryFilter,
                addCategoryBtn,
                count, total);
        filters.setAlignment(Pos.CENTER_LEFT);

        table.getColumns().addAll(
                Ui.col("Date", e -> Ui.date(e.expenseDate()), 105),
                Ui.col("Category", Expense::categoryName, 160),
                Ui.moneyCol("Amount", Expense::amount, 110),
                Ui.col("Method", Expense::paymentMethod, 115),
                Ui.col("Description", e -> e.description() == null ? "" : e.description(), 280),
                Ui.col("Responsible", e -> e.responsible() == null ? "" : e.responsible(), 140),
                Ui.col("Status", Expense::status, 90),
                Ui.col("Invoice", e -> e.invoicePath() == null || e.invoicePath().isBlank() ? "—" : "Attached", 80));
        table.setPlaceholder(Ui.empty("No expenses recorded", "Record operating expenses to calculate operating profit."));
        table.setRowFactory(tv -> {
            TableRow<Expense> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null && ctx.canManage()) {
                    expenseDialog(row.getItem());
                }
            });
            return row;
        });
        Button editBtn = Ui.secondary("Edit selected", "fas-edit", () -> {
            Expense e = table.getSelectionModel().getSelectedItem();
            if (e == null) { warn("Select an expense."); return; }
            expenseDialog(e);
        });
        editBtn.setVisible(ctx.canManage());
        editBtn.setManaged(ctx.canManage());
        Button deleteBtn = Ui.ghost("Delete selected", "fas-trash-alt", () -> {
            Expense e = table.getSelectionModel().getSelectedItem();
            if (e == null) { warn("Select an expense."); return; }
            if (!ctx.session().isOwner()) { warn("Only the owner can delete an expense."); return; }
            if (!Ui.confirm(nav.window(), "Delete expense", "Delete this expense record? This action is audited.")) return;
            try { ctx.expenses().delete(e.id(), ctx.session()); refresh(); toast("Expense deleted."); }
            catch (Exception ex) { Ui.error(nav.window(), ex); }
        });
        deleteBtn.setVisible(ctx.session().isOwner());
        deleteBtn.setManaged(ctx.session().isOwner());
        HBox actions = new HBox(8,
                editBtn,
                deleteBtn,
                Ui.muted(ctx.canManage() ? "Double-click an expense to edit." : "Click 'Record expense' to add a new expense."));
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(filters, actions, table);
        refresh();
    }

    @Override
    public void refresh() {
        ExpenseCategory selected = categoryFilter.getValue();
        List<ExpenseCategory> categories = ctx.expenses().categories();
        categoryFilter.getItems().setAll(categories);
        if (selected != null) categories.stream().filter(c -> c.id() == selected.id()).findFirst().ifPresent(categoryFilter::setValue);
        else categoryFilter.setValue(null);
        load();
    }

    private void load() {
        List<Expense> expenses = ctx.expenses().list(from.getValue(), to.getValue(),
                categoryFilter.getValue() == null ? null : categoryFilter.getValue().id());
        table.getItems().setAll(expenses);
        total.setText("Total " + Money.fmt(expenses.stream().map(Expense::amount).reduce(Money.ZERO, java.math.BigDecimal::add)));
        count.setText(expenses.size() + " expense(s)");
    }

    private void categoryDialog() {
        String name = Ui.prompt(nav.window(), "Expense category", "Add an operating expense category.", "Category name", "");
        if (name == null || name.isBlank()) return;
        try { ctx.expenses().saveCategory(name, ctx.session()); refresh(); toast("Category added."); }
        catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void expenseDialog(Expense existing) {
        openExpenseDialog(ctx, nav, existing, () -> {
            refresh();
            toast("Expense saved.");
        });
    }

    /**
     * Opens the Record Expense dialog from either the Admin or Cashier panel.
     */
    public static void openRecordDialog(AppContext ctx, Navigator nav, Runnable onSaved) {
        openExpenseDialog(ctx, nav, null, () -> {
            if (onSaved != null) onSaved.run();
            nav.showToast("Expense saved.", "info");
        });
    }

    private static void openExpenseDialog(AppContext ctx, Navigator nav, Expense existing, Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Record expense" : "Edit expense");
        d.setHeaderText(null);
        ComboBox<ExpenseCategory> category = new ComboBox<>();
        category.getItems().setAll(ctx.expenses().categories());
        category.setMaxWidth(Double.MAX_VALUE);
        category.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ExpenseCategory c) { return c == null ? "Select category" : c.name(); }
            @Override public ExpenseCategory fromString(String s) { return null; }
        });
        TextField amount = Ui.textField("Amount (PKR)");
        DatePicker date = Ui.datePicker(existing == null ? LocalDate.now() : existing.expenseDate());
        ComboBox<String> method = new ComboBox<>();
        method.getItems().addAll("Cash", "Bank Transfer", "Card", "Easypaisa", "JazzCash", "Cheque");
        method.setValue(existing == null ? "Cash" : existing.paymentMethod());
        method.setMaxWidth(Double.MAX_VALUE);
        TextField description = Ui.textField("Description");
        TextField responsible = Ui.textField("Responsible person");
        TextField invoice = Ui.textField("Invoice attachment path");
        if (existing != null) {
            category.getItems().stream().filter(c -> c.id() == existing.categoryId()).findFirst().ifPresent(category::setValue);
            amount.setText(Money.plain(existing.amount()));
            description.setText(existing.description()); responsible.setText(existing.responsible());
            invoice.setText(existing.invoicePath());
        } else if (ctx.session() != null && ctx.session().user() != null) {
            responsible.setText(ctx.session().user().fullName());
        }
        Button attach = Ui.secondary("Attach invoice", "fas-paperclip", () -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.png", "*.jpg", "*.jpeg"));
            File f = fc.showOpenDialog(nav.window());
            if (f != null) invoice.setText(f.getAbsolutePath());
        });
        GridPane form = Ui.form(2);
        form.add(Ui.field("Category", category), 0, 0);
        form.add(Ui.field("Amount", amount), 1, 0);
        form.add(Ui.field("Date", date), 0, 1);
        form.add(Ui.field("Payment method", method), 1, 1);
        form.add(Ui.field("Description", description), 0, 2, 2, 1);
        form.add(Ui.field("Responsible person", responsible), 0, 3);
        form.add(Ui.field("Invoice attachment", invoice), 1, 3);
        form.add(attach, 1, 4);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        String who = ctx.session() != null && ctx.session().user() != null
                ? ctx.session().user().fullName() + " (" + ctx.session().role().label() + ")"
                : "Current user";
        VBox box = new VBox(12, form, error, Ui.muted("Recorded by " + who + " and logged in the audit trail."));
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(600);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType(existing == null ? "Record expense" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (category.getValue() == null) throw new IllegalArgumentException("Select a category.");
                if (existing == null) {
                    ctx.expenses().add(category.getValue().id(), Money.of(amount.getText()), date.getValue(), method.getValue(),
                            description.getText(), responsible.getText(), invoice.getText(), ctx.session());
                } else {
                    ctx.expenses().update(existing.id(), category.getValue().id(), Money.of(amount.getText()), date.getValue(),
                            method.getValue(), description.getText(), responsible.getText(), "APPROVED", ctx.session());
                }
            } catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent() && onSaved != null) {
            onSaved.run();
        }
    }
}
