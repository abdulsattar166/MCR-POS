package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.SystemModels.Notification;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class NotificationsView extends AppView {
    private final TableView<Notification> table = Ui.table();
    private final CheckBox unreadOnly = new CheckBox("Unread only");
    private final Label unread = Ui.muted("");

    public NotificationsView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Notifications", "Operational alerts for stock, cash, printing and backups.",
                Ui.button("Mark all read", "fas-check-double", "secondary-button", () -> {
                    ctx.systemRepo().markAllRead();
                    refresh();
                    nav.refreshNotifications();
                    toast("All notifications marked read.");
                }),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        unreadOnly.selectedProperty().addListener((o, a, b) -> load());
        table.getColumns().addAll(
                Ui.col("Time", n -> Ui.dateTime(n.createdAt()), 140),
                Ui.col("Type", Notification::type, 120),
                Ui.col("Severity", Notification::severity, 90),
                Ui.col("Title", Notification::title, 210),
                Ui.col("Message", Notification::message, 420),
                Ui.col("Read", n -> n.read() ? "Yes" : "No", 60));
        table.setPlaceholder(Ui.empty("No notifications", "Alerts will appear as the restaurant operates."));
        table.setRowFactory(tv -> {
            TableRow<Notification> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.getItem() != null && !row.getItem().read()) {
                    ctx.systemRepo().markRead(row.getItem().id());
                    refresh();
                    nav.refreshNotifications();
                }
            });
            return row;
        });
        HBox bar = new HBox(10, unreadOnly, Ui.spacer(), unread,
                Ui.secondary("Mark selected read", "fas-check", () -> {
                    Notification n = table.getSelectionModel().getSelectedItem();
                    if (n == null) return;
                    ctx.systemRepo().markRead(n.id());
                    refresh();
                    nav.refreshNotifications();
                }));
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(bar, table);
        refresh();
    }

    @Override
    public void refresh() {
        load();
        nav.refreshNotifications();
    }

    private void load() {
        table.getItems().setAll(ctx.systemRepo().notifications(500, unreadOnly.isSelected()));
        int count = ctx.systemRepo().unreadCount();
        unread.setText(count + " unread notification(s)");
        unread.getStyleClass().setAll(count > 0 ? "danger-text" : "muted");
    }
}
