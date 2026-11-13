package com.restaurantpos.app;

import com.restaurantpos.dao.Database;
import javafx.application.Application;
import javafx.stage.Stage;

public final class Main extends Application {
    public static void main(String[] args) {
        System.setProperty("pos.logs", AppPaths.logs().toString());
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        com.restaurantpos.ui.AppWindow window = new com.restaurantpos.ui.AppWindow(stage);
        window.showSplash();
    }

    @Override
    public void stop() {
        try { Database.get().closeQuietly(); } catch (Exception ignored) {}
    }
}
