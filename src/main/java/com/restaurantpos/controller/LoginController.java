package com.restaurantpos.controller;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.enums.Role;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.ui.AppWindow;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

public final class LoginController {
    @FXML private VBox brandPane;
    @FXML private VBox loginCard;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField visiblePasswordField;
    @FXML private CheckBox showPasswordCheck;
    @FXML private CheckBox rememberCheck;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;
    @FXML private Hyperlink forgotLink;
    @FXML private javafx.scene.image.ImageView logoView;
    @FXML private Label versionLabel;
    @FXML private Label dataPathLabel;
    @FXML private Label offlineBadge;

    private final AppContext ctx;
    private final AppWindow window;
    private final Preferences prefs = Preferences.userNodeForPackage(LoginController.class);
    private boolean busy;

    public LoginController(AppContext ctx, AppWindow window) {
        this.ctx = ctx;
        this.window = window;
    }

    @FXML
    public void initialize() {
        logoView.setImage(BrandLogo.fxRoundedLogo(ctx.settings(), 84, 14));
        logoView.setFitWidth(84);
        logoView.setFitHeight(84);
        logoView.setPreserveRatio(true);
        logoView.setSmooth(true);
        versionLabel.setText("v" + ctx.settings().appVersion());
        dataPathLabel.setText("Data directory: " + AppPaths.root());
        offlineBadge.setText("● LOCAL DATABASE · OFFLINE READY");

        visiblePasswordField.managedProperty().bind(visiblePasswordField.visibleProperty());
        passwordField.managedProperty().bind(passwordField.visibleProperty());
        visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());
        showPasswordCheck.selectedProperty().addListener((obs, old, selected) -> togglePassword(selected));

        String remembered = prefs.get("username", "");
        if (!remembered.isBlank()) {
            usernameField.setText(remembered);
            rememberCheck.setSelected(true);
            Platform.runLater(() -> passwordField.requestFocus());
        } else {
            Platform.runLater(() -> usernameField.requestFocus());
        }

        loginButton.setOnAction(e -> login());
        passwordField.setOnAction(e -> login());
        visiblePasswordField.setOnAction(e -> login());
        usernameField.setOnAction(e -> passwordField.requestFocus());
        forgotLink.setOnAction(e -> Ui.info(window.stage(), "Password help",
                "Ask the restaurant owner to reset your password from Staff & Cashiers.\n\n"
                        + "The owner can reset credentials without exposing or storing any plaintext password.\n"
                        + "If you are the owner and locked out, restore a verified local backup or contact your installer."));

        Animations.fadeIn(brandPane, 700, 80);
        Animations.slideUp(loginCard, 620, 180, 22);
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());
    }

    private void togglePassword(boolean show) {
        passwordField.setVisible(!show);
        visiblePasswordField.setVisible(show);
        if (show) {
            visiblePasswordField.requestFocus();
            visiblePasswordField.positionCaret(visiblePasswordField.getText().length());
        } else {
            passwordField.requestFocus();
            passwordField.positionCaret(passwordField.getText().length());
        }
    }

    private void login() {
        if (busy) return;
        hideError();
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (username.isBlank() || password.isEmpty()) {
            showError("Enter both your username and password.");
            return;
        }
        busy = true;
        loginButton.setDisable(true);
        loginButton.setText("Signing in...");
        passwordField.setDisable(true);

        Task<User> task = new Task<>() {
            @Override protected User call() { return ctx.auth().login(username, password); }
        };
        task.setOnSucceeded(e -> {
            busy = false;
            if (rememberCheck.isSelected()) prefs.put("username", username.toLowerCase());
            else prefs.remove("username");
            User user = task.getValue();
            window.onLoginSucceeded(user, password);
        });
        task.setOnFailed(e -> {
            busy = false;
            loginButton.setDisable(false);
            loginButton.setText("Sign in");
            passwordField.setDisable(false);
            Throwable ex = task.getException();
            showError(ex == null ? "Sign in failed." : (ex.getMessage() == null ? "Sign in failed." : ex.getMessage()));
            shake();
            passwordField.selectAll();
            passwordField.requestFocus();
        });
        Thread t = new Thread(task, "login");
        t.setDaemon(true);
        t.start();
    }

    private void shake() {
        Translate t = new Translate(0, 0);
        loginCard.getTransforms().add(t);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(0), e -> t.setX(0)),
                new KeyFrame(Duration.millis(60), e -> t.setX(-7)),
                new KeyFrame(Duration.millis(130), e -> t.setX(6)),
                new KeyFrame(Duration.millis(200), e -> t.setX(-4)),
                new KeyFrame(Duration.millis(260), e -> t.setX(0))
        );
        tl.setOnFinished(e -> loginCard.getTransforms().remove(t));
        tl.play();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
    }
}
