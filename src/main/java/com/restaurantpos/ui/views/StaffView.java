package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.Role;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class StaffView extends AppView {
    private final TableView<User> table = Ui.table();
    private final TableView<Object[]> performance = Ui.table();
    private final TableView<Object[]> loginHistory = Ui.table();
    private final Label count = Ui.muted("");

    public StaffView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Staff & Cashiers", "Accounts, roles, access status and cashier performance.",
                Ui.primary("Add employee", "fas-user-plus", () -> employeeDialog(null)),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        table.getColumns().addAll(
                Ui.col("Name", User::fullName, 190),
                Ui.col("Username", User::username, 220),
                Ui.col("Role", u -> u.role().label(), 120),
                Ui.col("Status", u -> u.active() ? "Active" : "Inactive", 80),
                Ui.col("Last login", u -> Ui.dateTime(u.lastLogin()), 140));
        table.setPlaceholder(Ui.empty("No employees", "Add cashiers, inventory staff and managers."));
        table.setPrefHeight(280);

        FlowPane actions = new FlowPane(8, 8,
                Ui.secondary("Edit", "fas-edit", () -> {
                    User u = table.getSelectionModel().getSelectedItem();
                    if (u == null) { warn("Select an employee."); return; }
                    employeeDialog(u);
                }),
                Ui.secondary("Deactivate", "fas-user-slash", () -> {
                    User u = table.getSelectionModel().getSelectedItem();
                    if (u == null) { warn("Select an employee."); return; }
                    if (!u.active()) { warn(u.fullName() + " is already inactive."); return; }
                    String msg = "Deactivate " + u.fullName() + "?\n\n" +
                            "This will prevent them from logging in. Their historical records will be preserved.";
                    if (Ui.confirm(nav.window(), "Deactivate employee", msg)) {
                        try { 
                            ctx.employees().deactivate(u.id(), ctx.session()); 
                            refresh(); 
                            toast(u.fullName() + " has been deactivated and can no longer log in."); 
                        }
                        catch (Exception e) { Ui.error(nav.window(), e); }
                    }
                }),
                Ui.secondary("Reactivate", "fas-user-check", () -> {
                    User u = table.getSelectionModel().getSelectedItem();
                    if (u == null) { warn("Select an employee."); return; }
                    if (u.active()) { warn(u.fullName() + " is already active."); return; }
                    if (Ui.confirm(nav.window(), "Reactivate employee", "Reactivate " + u.fullName() + "?")) {
                        try {
                            ctx.employees().update(u.id(), u.fullName(), u.role(), true, u.photoPath(), ctx.session());
                            refresh();
                            toast(u.fullName() + " has been reactivated.");
                        } catch (Exception e) { Ui.error(nav.window(), e); }
                    }
                }),
                Ui.secondary("Reset password", "fas-key", () -> {
                    User u = table.getSelectionModel().getSelectedItem();
                    if (u == null) { warn("Select an employee."); return; }
                    resetDialog(u);
                }),
                Ui.ghost("Upload photo", "fas-camera", () -> {
                    User u = table.getSelectionModel().getSelectedItem();
                    if (u == null) { warn("Select an employee."); return; }
                    File f = Ui.chooseImage(nav.window());
                    if (f == null) return;
                    try {
                        String path = Images.importStaff(f.toPath(), u.username());
                        ctx.employees().update(u.id(), u.fullName(), u.role(), u.active(), path, ctx.session());
                        refresh(); toast("Employee photo updated.");
                    } catch (Exception e) { Ui.error(nav.window(), e); }
                }),
                Ui.spacer(), count);
        actions.setAlignment(Pos.CENTER_LEFT);

        performance.getColumns().addAll(
                Ui.col("Cashier", r -> String.valueOf(r[0]), 160),
                Ui.col("Orders", r -> String.valueOf(r[1]), 70),
                Ui.moneyCol("Sales", r -> (BigDecimal) r[2], 110),
                Ui.moneyCol("Discounts", r -> (BigDecimal) r[3], 110),
                Ui.col("Cancellations", r -> String.valueOf(r[4]), 100),
                Ui.moneyCol("Refunds", r -> (BigDecimal) r[5], 105));
        performance.setPlaceholder(Ui.empty("No performance data", "Completed orders will appear here."));
        performance.setPrefHeight(180);

        loginHistory.getColumns().addAll(
                Ui.col("Time", r -> String.valueOf(r[4]), 145),
                Ui.col("Username", r -> String.valueOf(r[1]), 220),
                Ui.col("Result", r -> ((Boolean) r[2]) ? "Success" : "Failed", 80),
                Ui.col("Detail", r -> String.valueOf(r[3]), 260));
        loginHistory.setPlaceholder(Ui.empty("No login activity", "Sign-in attempts are recorded for security."));
        loginHistory.setPrefHeight(160);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox team = new VBox(10, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        tabs.getTabs().addAll(new Tab("Team & access", team), new Tab("Cashier performance", performance),
                new Tab("Login history", loginHistory));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        List<User> users = ctx.employees().all(ctx.session());
        table.getItems().setAll(users);
        count.setText(users.size() + " account(s)  ·  " + users.stream().filter(User::active).count() + " active");
        LocalDate from = LocalDate.now().withDayOfMonth(1);
        performance.getItems().setAll(ctx.reportRepo().cashierPerformance(from, LocalDate.now()));
        List<?> attempts = ctx.employees().loginHistory(100, ctx.session());
        List<Object[]> rows = new java.util.ArrayList<>();
        for (Object a : attempts) {
            if (a instanceof com.restaurantpos.dao.UserRepository.LoginAttempt la) {
                rows.add(new Object[]{la.id(), la.username(), la.success(), la.detail(), Ui.dateTime(la.createdAt())});
            }
        }
        loginHistory.getItems().setAll(rows);
    }

    private void employeeDialog(User existing) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Add employee" : "Edit " + existing.fullName());
        d.setHeaderText(null);
        TextField username = Ui.textField("Username (email)");
        TextField fullName = Ui.textField("Full name");
        ComboBox<Role> role = new ComboBox<>();
        role.getItems().setAll(Role.values());
        role.setValue(existing == null ? Role.CASHIER : existing.role());
        role.setMaxWidth(Double.MAX_VALUE);
        role.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Role r) { return r == null ? "" : r.label(); }
            @Override public Role fromString(String s) { return null; }
        });
        PasswordField password = Ui.passwordField("Temporary password");
        CheckBox active = new CheckBox("Account active");
        active.setSelected(existing == null || existing.active());
        String[] photo = {existing == null ? null : existing.photoPath()};
        Label photoName = Ui.muted(photo[0] == null ? "No photo" : photo[0]);
        Button pick = Ui.secondary("Choose photo", "fas-camera", () -> {
            File f = Ui.chooseImage(nav.window());
            if (f != null) {
                try {
                    photo[0] = Images.importStaff(f.toPath(), existing == null ? "staff" : existing.username());
                    photoName.setText(photo[0]);
                } catch (Exception e) { Ui.error(nav.window(), e); }
            }
        });
        if (existing != null) {
            username.setText(existing.username());
            username.setDisable(true);
            fullName.setText(existing.fullName());
            active.setSelected(existing.active());
            password.setDisable(true);
            password.setPromptText("Use Reset password after saving");
        }
        GridPane form = Ui.form(2);
        form.add(Ui.field("Full name", fullName), 0, 0);
        form.add(Ui.field("Username / email", username), 1, 0);
        form.add(Ui.field("Role", role), 0, 1);
        form.add(Ui.field("Temporary password", password), 1, 1);
        form.add(active, 0, 2);
        form.add(new HBox(8, pick, photoName), 1, 2);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, form, error,
                Ui.muted("New accounts must change their temporary password on first sign-in. Passwords are stored only as salted hashes."));
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(560);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType(existing == null ? "Create employee" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (existing == null) {
                    com.restaurantpos.service.AuthService.validatePassword(password.getText(), password.getText());
                    ctx.employees().create(username.getText(), fullName.getText(), role.getValue(), password.getText(),
                            active.isSelected(), photo[0], ctx.session());
                } else {
                    ctx.employees().update(existing.id(), fullName.getText(), role.getValue(), active.isSelected(), photo[0], ctx.session());
                }
            } catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Employee saved."); }
    }

    private void resetDialog(User user) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Reset password — " + user.fullName());
        d.setHeaderText(null);
        PasswordField next = Ui.passwordField("New temporary password");
        PasswordField confirm = Ui.passwordField("Confirm password");
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, Ui.muted("The employee will be required to choose a new password after signing in with this temporary password."),
                Ui.field("New password", next), Ui.field("Confirm password", confirm), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(400);
        d.getDialogPane().setContent(box);
        ButtonType reset = new ButtonType("Reset password", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, reset);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(reset);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try { ctx.employees().resetPassword(user.id(), next.getText(), confirm.getText(), ctx.session()); }
            catch (Exception ex) { error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true); ev.consume(); }
        });
        if (d.showAndWait().filter(b -> b == reset).isPresent()) { refresh(); toast("Password reset. Employee must change it on next login."); }
    }
}
