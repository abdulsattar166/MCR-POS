package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.ui.Navigator;
import javafx.geometry.Insets;
import javafx.scene.layout.VBox;

public abstract class AppView extends VBox {
    protected final AppContext ctx;
    protected final Navigator nav;

    protected AppView(AppContext ctx, Navigator nav) {
        super(16);
        this.ctx = ctx;
        this.nav = nav;
        getStyleClass().add("view-root");
        setPadding(new Insets(0));
    }

    public void refresh() {}

    protected void toast(String message) { nav.showToast(message, "SUCCESS"); }
    protected void warn(String message) { nav.showToast(message, "WARNING"); }
}
