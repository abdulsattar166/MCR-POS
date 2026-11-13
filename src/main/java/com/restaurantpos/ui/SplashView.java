package com.restaurantpos.ui;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.function.Consumer;

public final class SplashView extends StackPane {
    private final Consumer<AppContext> onReady;
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label("Starting...");
    private final VBox center;
    private final Label error = new Label();
    private final HBox recovery;

    public SplashView(Consumer<AppContext> onReady) {
        this.onReady = onReady;
        getStyleClass().add("splash-root");

        Region glow = new Region();
        glow.getStyleClass().add("splash-glow");
        glow.setPrefSize(620, 620);
        glow.setMaxSize(620, 620);

        ImageView logo = new ImageView();
        logo.setFitWidth(148); logo.setFitHeight(148); logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setEffect(new DropShadow(30, Color.rgb(240, 164, 77, 0.35)));
        logo.setImage(BrandLogo.fxRoundedLogo(null, 148, 22));
        logo.setOpacity(0);
        logo.setScaleX(0.86); logo.setScaleY(0.86);

        Label title = new Label("MUBARAK");
        title.getStyleClass().add("splash-title");
        Label sub = new Label("CAFE & RESTAURANT  •  Restaurant POS");
        sub.getStyleClass().add("splash-sub");
        HBox badges = new HBox(10,
                badge("fas-database", "LOCAL SQLITE"),
                badge("fas-wifi", "OFFLINE READY"),
                badge("fas-shield-alt", "SECURE ACCESS"));
        badges.setAlignment(Pos.CENTER);

        progress.setPrefWidth(340);
        progress.getStyleClass().add("splash-progress");
        progress.setOpacity(0);
        status.getStyleClass().add("splash-status");

        error.getStyleClass().add("splash-error");
        error.setWrapText(true);
        error.setMaxWidth(520);
        error.setVisible(false);
        error.setManaged(false);

        Button retry = Ui.primary("Try again", "fas-redo", this::start);
        Button exit = Ui.secondary("Exit", "fas-times", () -> Platform.exit());
        recovery = new HBox(10, retry, exit);
        recovery.setAlignment(Pos.CENTER);
        recovery.setVisible(false);
        recovery.setManaged(false);

        Label version = new Label("Version 1.0.0  •  Single branch");
        version.getStyleClass().add("splash-badge");

        center = new VBox(14, logo, title, sub, badges, progress, status, error, recovery, version);
        center.setAlignment(Pos.CENTER);
        center.setMaxWidth(620);
        center.setPadding(new Insets(30));

        getChildren().addAll(glow, center);

        Animations.fadeIn(title, 700, 160);
        Animations.fadeIn(sub, 700, 300);
        Animations.fadeIn(badges, 700, 460);
        FadeTransition fadeLogo = new FadeTransition(Duration.millis(800), logo);
        fadeLogo.setToValue(1); fadeLogo.setDelay(Duration.millis(80));
        ScaleTransition scaleLogo = new ScaleTransition(Duration.millis(900), logo);
        scaleLogo.setToX(1); scaleLogo.setToY(1);
        scaleLogo.setDelay(Duration.millis(80));
        new ParallelTransition(fadeLogo, scaleLogo).play();
        PauseTransition start = new PauseTransition(Duration.millis(700));
        start.setOnFinished(e -> this.start());
        start.play();
    }

    private HBox badge(String icon, String text) {
        Label l = Ui.label(text, "splash-badge");
        l.setGraphic(Ui.icon(icon, 11));
        return new HBox(l);
    }

    private void start() {
        error.setVisible(false); error.setManaged(false);
        recovery.setVisible(false); recovery.setManaged(false);
        progress.setOpacity(1);
        status.setText("Preparing...");
        Task<AppContext> task = new Task<>() {
            @Override protected AppContext call() {
                Bootstrap.initialize(msg -> updateMessage(msg), p -> updateProgress(p, 1));
                return AppContext.get();
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            progress.progressProperty().unbind();
            status.textProperty().unbind();
            progress.setProgress(1);
            PauseTransition pause = new PauseTransition(Duration.millis(420));
            pause.setOnFinished(x -> exitTo(() -> onReady.accept(task.getValue())));
            pause.play();
        });
        task.setOnFailed(e -> {
            progress.progressProperty().unbind();
            status.textProperty().unbind();
            progress.setProgress(0);
            Throwable ex = task.getException();
            String msg = ex == null ? "Initialization failed." : (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            status.setText("Initialization failed");
            error.setText(msg + "\nData directory: " + AppPaths.root()
                    + "\nCheck that the folder is writable, then retry or restore a verified backup.");
            error.setVisible(true); error.setManaged(true);
            recovery.setVisible(true); recovery.setManaged(true);
        });
        Thread t = new Thread(task, "pos-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private void exitTo(Runnable action) {
        FadeTransition fade = new FadeTransition(Duration.millis(360), this);
        fade.setToValue(0);
        ScaleTransition scale = new ScaleTransition(Duration.millis(360), this);
        scale.setToX(1.035); scale.setToY(1.035);
        ParallelTransition out = new ParallelTransition(fade, scale);
        out.setOnFinished(e -> action.run());
        out.play();
    }
}
