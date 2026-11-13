package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.model.Sales.Shift;
import com.restaurantpos.model.SystemModels.DashboardStats;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;

public final class CashierDashboardView extends AppView {
    private final ResponsiveCardGrid cards = new ResponsiveCardGrid(16, 250, 4);
    private final Label subtitle = Ui.muted("");

    public CashierDashboardView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        HBox header = Ui.pageHeader("Cashier Dashboard", "Your shift, service and payments at a glance.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh),
                Ui.secondary("Record Expense", "fas-file-invoice-dollar",
                        () -> ExpensesView.openRecordDialog(ctx, nav, this::refresh)),
                Ui.primary("New order", "fas-plus", () -> nav.navigate("pos")));
        ImageView dashLogo = new ImageView(BrandLogo.fxRoundedLogo(ctx.settings(), 40, 8));
        dashLogo.setFitWidth(40);
        dashLogo.setFitHeight(40);
        dashLogo.setPreserveRatio(true);
        header.getChildren().add(0, dashLogo);
        header.getChildren().add(2, subtitle);
        getChildren().add(header);

        Label section = Ui.label("TODAY AT A GLANCE", "section-label");
        section.setPadding(new Insets(0, 0, 0, 2));
        VBox body = new VBox(12, section, cards);
        ScrollPane scroll = Ui.scroll(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
        refresh();
    }

    @Override
    public void refresh() {
        DashboardStats stats = ctx.reports().dashboard();
        Shift shift = ctx.shifts().current(ctx.session().userId());
        long availableTables = ctx.floorRepo().tables(null, true).stream()
                .filter(t -> t.status() == TableStatus.AVAILABLE).count();
        int unread = ctx.systemRepo().notifications(99, true).size();
        long draftCount = ctx.orders().draftOrders("").size();
        long completeCount = ctx.orders().completeOrders(java.time.LocalDate.now(), java.time.LocalDate.now(), "").size();
        subtitle.setText(LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy  •  hh:mm a")));

        cards.setCards(List.of(
                card("New Order", "4-Step Wizard", "fas-cash-register", "stat-icon-accent",
                        "Type, table, menu & review", () -> nav.navigate("pos")),
                card("Draft Orders", String.valueOf(draftCount), "fas-clipboard-list", "stat-icon-amber",
                        "Unpaid drafts awaiting counter payment", () -> nav.navigate("draft-orders")),
                card("Complete Orders", String.valueOf(completeCount), "fas-check-double", "stat-icon-green",
                        "Fully settled & paid today", () -> nav.navigate("complete-orders")),
                card("My shift", shift == null ? "Closed" : "Open", "fas-wallet", "stat-icon-blue",
                        shift == null ? "Open a shift to take payments" : "Expected drawer " + Money.fmt(ctx.shifts().expected(shift.id())),
                        () -> nav.navigate("shift")),
                card("Orders this shift", shift == null ? "0" : String.valueOf(ctx.shifts().orderCount(shift.id())),
                        "fas-receipt", "stat-icon-green",
                        shift == null ? "No shift open" : "Collected " + Money.fmt(ctx.shifts().sales(shift.id())),
                        () -> nav.navigate("orders")),
                card("Tables available", String.valueOf(availableTables), "fas-chair", "stat-icon-green",
                        "Ready to seat guests", () -> nav.navigate("floors")),
                card("Expenses today", Money.fmt(ctx.expenses().total(java.time.LocalDate.now(), java.time.LocalDate.now())),
                        "fas-file-invoice-dollar", "stat-icon-amber",
                        "Tap to view or add expenses", () -> nav.navigate("expenses")),
                card("Restaurant sales", Money.fmt(stats.netSales()), "fas-coins",
                        "stat-icon-green", "Net sales today", () -> nav.navigate("orders"))
        ));
        Animations.fadeIn(cards, 260, 0);
    }

    private VBox card(String title, String value, String icon, String iconStyle, String caption, Runnable action) {
        VBox card = new VBox(5);
        card.getStyleClass().add("stat-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-min-width: 0; -fx-pref-width: 0;");
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label iconLabel = new Label();
        iconLabel.setGraphic(Ui.icon(icon, 17));
        iconLabel.getStyleClass().addAll("stat-icon", iconStyle);
        Label label = Ui.label(title.toUpperCase(), "stat-label");
        HBox.setHgrow(label, Priority.ALWAYS);
        top.getChildren().addAll(iconLabel, label);
        Label val = Ui.label(value, "stat-value");
        Label cap = Ui.label(caption, "muted");
        card.getChildren().addAll(top, val, cap);

        card.setCursor(javafx.scene.Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        card.setAccessibleText(title + " card. Activate to open details.");
        Tooltip.install(card, new Tooltip("Open " + title.toLowerCase()));
        card.setOnMouseClicked(e -> action.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                action.run();
                e.consume();
            }
        });
        card.setOnMouseEntered(e -> Animations.hoverLift(card));
        card.setOnMouseExited(e -> Animations.hoverReset(card));
        return card;
    }
}
