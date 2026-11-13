package com.restaurantpos.ui;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.controller.LoginController;
import com.restaurantpos.enums.Role;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import javafx.animation.FadeTransition;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AppWindow {
    private final Stage stage;
    private AppContext ctx;

    public AppWindow(Stage stage) { this.stage = stage; }

    public Stage stage() { return stage; }
    public AppContext context() { return ctx; }

    public void showSplash() {
        applyIcon();
        SplashView splash = new SplashView(this::onReady);
        Scene scene = new Scene(splash, 1000, 680);
        Ui.applyStyles(scene);
        stage.setTitle("Mubarak Cafe & Restaurant POS");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.centerOnScreen();
        stage.show();
    }

    private void onReady(AppContext context) {
        this.ctx = context;
        applyIcon();
        showLogin();
    }

    private void applyIcon() {
        try {
            javafx.scene.image.Image icon = BrandLogo.fxRoundedLogo(ctx != null ? ctx.settings() : null, 128, 20);
            if (icon != null) stage.getIcons().setAll(icon);
        } catch (Exception ignored) {}
    }

    public void showLogin() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/login.fxml"));
            LoginController controller = new LoginController(ctx, this);
            loader.setController(controller);
            StackPane root = loader.load();
            Scene scene = new Scene(root, 1180, 760);
            Ui.applyStyles(scene);
            stage.setScene(scene);
            stage.setResizable(true);
            stage.setMinWidth(900);
            stage.setMinHeight(520);
            var bounds = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.max(960, Math.min(1180, bounds.getWidth() * 0.92)));
            stage.setHeight(Math.max(540, Math.min(740, bounds.getHeight() * 0.92)));
            stage.setTitle("Mubarak Cafe & Restaurant — Sign in");
            stage.centerOnScreen();
            root.setOpacity(0);
            FadeTransition ft = new FadeTransition(Duration.millis(420), root);
            ft.setToValue(1);
            ft.play();
        } catch (Exception e) {
            e.printStackTrace();
            Ui.error(stage, "Login screen could not be opened", e.getMessage());
        }
    }

    public void onLoginSucceeded(User user, String password) {
        if (user.role() == com.restaurantpos.enums.Role.CASHIER) {
            ctx.shifts().ensureActiveShiftForCashier(user, ctx.session());
        }
        showShell();
    }

    private boolean forcePasswordChange(User user, String currentPassword) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Set a new owner password");
        dialog.setHeaderText("First-time security setup");
        dialog.setResizable(false);
        Label info = Ui.muted("The initial setup password must be replaced before the workspace opens. "
                + "Choose a password with at least 8 characters, including letters and numbers.");
        info.setWrapText(true);
        PasswordField current = Ui.passwordField("Current password");
        PasswordField next = Ui.passwordField("New password");
        PasswordField confirm = Ui.passwordField("Confirm new password");
        current.setText(currentPassword);
        Label error = Ui.label("", "inline-error");
        error.setVisible(false);
        error.setManaged(false);
        VBox box = new VBox(12, info, Ui.field("Current password", current), Ui.field("New password", next),
                Ui.field("Confirm new password", confirm), error);
        box.setPadding(new Insets(14, 4, 4, 4));
        box.setPrefWidth(430);
        dialog.getDialogPane().setContent(box);
        ButtonType change = new ButtonType("Change password & continue", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Sign out", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(cancel, change);
        if (dialog.getDialogPane().getScene() != null) Ui.applyStyles(dialog.getDialogPane().getScene());
        else dialog.setOnShown(e -> Ui.applyStyles(dialog.getDialogPane().getScene()));
        dialog.getDialogPane().getStyleClass().add("app-dialog");
        Button ok = (Button) dialog.getDialogPane().lookupButton(change);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.auth().changeOwnPassword(current.getText(), next.getText(), confirm.getText());
            } catch (Exception ex) {
                error.setText(ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
            }
        });
        dialog.setOnShown(e -> {
            current.requestFocus();
            current.positionCaret(current.getText().length());
            javafx.application.Platform.runLater(() -> next.requestFocus());
        });
        return dialog.showAndWait().filter(b -> b == change).isPresent();
    }

    public void showShell() {
        MainShell shell = new MainShell(this, ctx);
        Scene scene = new Scene(shell, 1400, 880);
        Ui.applyStyles(scene);
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(560);
        var bounds = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.max(1024, Math.min(1420, bounds.getWidth() * 0.96)));
        stage.setHeight(Math.max(580, Math.min(900, bounds.getHeight() * 0.96)));
        stage.setTitle("Mubarak Cafe & Restaurant — " + ctx.session().user().role().label() + " workspace");
        stage.centerOnScreen();
        shell.start();
    }

    public void logout() {
        if (ctx.session().active() && ctx.session().role() == com.restaurantpos.enums.Role.CASHIER) {
            com.restaurantpos.model.Sales.Shift s = ctx.shifts().current(ctx.session().userId());
            String prompt = s != null
                    ? "Sign out and close your active shift (#" + s.id() + ")?"
                    : "Sign out of the current session?";
            if (!Ui.confirm(stage, "Sign out", prompt)) return;
            try {
                ctx.shifts().autoCloseCashierShift(ctx.session().user(), ctx.session(), "Auto-closed on cashier logout");
            } catch (Exception ex) {
                Ui.error(stage, "Shift Closure Error",
                        "Could not close your active cashier shift cleanly: " + ex.getMessage()
                                + "\n\nSign-out was aborted to protect financial consistency. Please retry.");
                return;
            }
        } else {
            if (!Ui.confirm(stage, "Sign out", "Sign out of the current session?")) return;
        }
        ctx.auth().logout();
        showLogin();
    }

    public void openReceiptPreview(Order order) {
        Order fresh = ctx.orders().find(order.id());
        com.restaurantpos.ui.components.ReceiptPreviewDialog dialog =
                new com.restaurantpos.ui.components.ReceiptPreviewDialog(ctx, fresh != null ? fresh : order, stage, null, null);
        dialog.showAndWait();
    }
}
