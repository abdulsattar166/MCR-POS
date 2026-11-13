package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.SystemModels.DashboardStats;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.*;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class DashboardView extends AppView {
    private final ResponsiveCardGrid statsGrid = new ResponsiveCardGrid(14, 230, 4);
    private final LineChart<String, Number> salesChart = new LineChart<>(new CategoryAxis(), new NumberAxis());
    private final PieChart paymentsChart = new PieChart();
    private final BarChart<String, Number> categoryChart = new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final VBox activityList = new VBox(10);
    private final VBox alertsList = new VBox(8);
    private final Label subtitle = Ui.muted("");

    public DashboardView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        HBox header = Ui.pageHeader("Owner Dashboard", "A live view of today's restaurant performance.",
                Ui.secondary("Record Expense", "fas-file-invoice-dollar",
                        () -> ExpensesView.openRecordDialog(ctx, nav, this::refresh)),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh));
        ImageView dashLogo = new ImageView(BrandLogo.fxRoundedLogo(ctx.settings(), 40, 8));
        dashLogo.setFitWidth(40);
        dashLogo.setFitHeight(40);
        dashLogo.setPreserveRatio(true);
        header.getChildren().add(0, dashLogo);
        header.getChildren().add(2, subtitle);
        getChildren().add(header);

        ResponsiveCardGrid chartsGrid = new ResponsiveCardGrid(16, 420, 2);
        ResponsiveCardGrid lowerGrid = new ResponsiveCardGrid(16, 300, 3);

        VBox salesCard = Ui.card("Sales trend", "fas-chart-line", salesChart);
        makeCardClickable(salesCard, "Sales trend", () -> nav.navigate("reports"));

        VBox payCard = Ui.card("Payment mix", "fas-credit-card", paymentsChart);
        makeCardClickable(payCard, "Payment mix", () -> nav.navigate("payments"));

        chartsGrid.setCards(List.of(salesCard, payCard));

        VBox catCard = Ui.card("Category performance", "fas-layer-group", categoryChart);
        makeCardClickable(catCard, "Category performance", () -> nav.navigate("reports"));

        VBox alertsCard = Ui.card("Alerts & attention", "fas-bell", alertsList);
        makeCardClickable(alertsCard, "Alerts & attention", () -> nav.navigate("notifications"));

        VBox activityCard = Ui.card("Recent activity", "fas-history", activityList);
        makeCardClickable(activityCard, "Recent activity", () -> nav.navigate("audit"));

        lowerGrid.setCards(List.of(catCard, alertsCard, activityCard));

        salesChart.setPrefHeight(270);
        salesChart.setLegendVisible(false);
        salesChart.setCreateSymbols(true);
        salesChart.setAnimated(true);
        paymentsChart.setPrefHeight(270);
        paymentsChart.setLabelsVisible(false);
        paymentsChart.setLegendSide(javafx.geometry.Side.BOTTOM);
        categoryChart.setPrefHeight(260);
        categoryChart.setLegendVisible(false);
        categoryChart.setAnimated(true);
        NumberAxis axis = (NumberAxis) salesChart.getYAxis(); axis.setForceZeroInRange(true);
        NumberAxis catAxis = (NumberAxis) categoryChart.getYAxis(); catAxis.setForceZeroInRange(true);

        VBox body = new VBox(16, statsGrid, chartsGrid, lowerGrid);
        ScrollPane scroll = Ui.scroll(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
        refresh();
    }

    @Override
    public void refresh() {
        LocalDate today = LocalDate.now();
        DashboardStats s = ctx.reports().dashboard();
        statsGrid.setCards(List.of(
                stat("Gross sales", Money.fmt(s.grossSales()), "fas-coins", "stat-icon-accent", "Completed orders today", () -> nav.navigate("reports")),
                stat("Net sales", Money.fmt(s.netSales()), "fas-chart-line", "stat-icon-green", "After discounts & refunds", () -> nav.navigate("reports")),
                stat("Orders today", String.valueOf(s.orders()), "fas-receipt", "stat-icon-blue", "Average " + Money.fmt(s.averageOrder()), () -> nav.navigate("orders")),
                stat("Gross profit", Money.fmt(s.grossProfit()), "fas-chart-line", "stat-icon-green", "Net sales less food cost", () -> nav.navigate("reports")),
                stat("Operating profit", Money.fmt(s.operatingProfit()), "fas-money-bill-wave", "stat-icon-accent", "After operating expenses", () -> nav.navigate("expenses")),
                stat("Low stock", String.valueOf(s.lowStock() + s.outOfStock()), "fas-boxes", "stat-icon-red",
                        s.outOfStock() + " out of stock", () -> nav.navigate("inventory")),
                stat("Open shifts", String.valueOf(s.activeShifts()), "fas-wallet", "stat-icon-blue",
                        "Cash discrepancy " + Money.fmt(s.cashDiscrepancy()), () -> nav.navigate("shift"))
        ));

        subtitle.setText(LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy  •  hh:mm a")));

        salesChart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Net sales");
        LocalDate from = today.minusDays(13);
        Map<String, BigDecimal> daily = ctx.reports().dailySalesMap(from, today);
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd MMM");
        for (int i = 0; i < 14; i++) {
            LocalDate d = from.plusDays(i);
            BigDecimal v = daily.getOrDefault(d.toString(), BigDecimal.ZERO);
            series.getData().add(new XYChart.Data<>(d.format(f), v.doubleValue()));
        }
        salesChart.getData().add(series);

        paymentsChart.getData().clear();
        Map<String, BigDecimal> pays = ctx.reports().paymentDistribution(today.withDayOfMonth(1), today);
        for (Map.Entry<String, BigDecimal> e : pays.entrySet()) {
            paymentsChart.getData().add(new PieChart.Data(e.getKey().replace('_', ' '), e.getValue().doubleValue()));
        }


        categoryChart.getData().clear();
        XYChart.Series<String, Number> cats = new XYChart.Series<>();
        List<Object[]> catRows = ctx.reports().categoryPerformance(today.withDayOfMonth(1), today);
        for (Object[] r : catRows) {
            cats.getData().add(new XYChart.Data<>(shorten((String) r[0]), ((BigDecimal) r[2]).doubleValue()));
        }
        categoryChart.getData().add(cats);

        alertsList.getChildren().clear();
        var notifications = ctx.systemRepo().notifications(6, true);
        if (notifications.isEmpty()) {
            alertsList.getChildren().add(Ui.empty("All clear", "No unread alerts right now."));
        } else {
            for (var n : notifications) {
                HBox row = new HBox(9);
                row.setAlignment(Pos.TOP_LEFT);
                Label icon = new Label();
                icon.setGraphic(Ui.icon("CRITICAL".equals(n.severity()) ? "fas-exclamation-circle"
                        : "WARNING".equals(n.severity()) ? "fas-exclamation-triangle" : "fas-info-circle", 14));
                icon.getStyleClass().add("danger-text");
                VBox text = new VBox(2, Ui.label(n.title(), "h3"), Ui.muted(n.message()));
                text.setMaxWidth(230);
                ((Label) text.getChildren().get(1)).setWrapText(true);
                row.getChildren().addAll(icon, text);
                alertsList.getChildren().add(row);
            }
            alertsList.getChildren().add(Ui.ghost("Open notification centre", "fas-arrow-right", () -> nav.navigate("notifications")));
        }

        activityList.getChildren().clear();
        var activity = ctx.reports().recentActivity(8);
        if (activity.isEmpty()) {
            activityList.getChildren().add(Ui.empty("No activity yet", "Audit events will appear here."));
        } else {
            for (Object[] a : activity) {
                HBox row = new HBox(9);
                row.setAlignment(Pos.TOP_LEFT);
                CircleDot dot = new CircleDot();
                VBox text = new VBox(1, Ui.label(String.valueOf(a[1]), "h3"),
                        Ui.muted(a[0] + "  •  " + a[4]));
                text.setMaxWidth(280);
                row.getChildren().addAll(dot, text);
                activityList.getChildren().add(row);
            }
        }
        Animations.fadeIn(activityList, 300, 0);
    }

    private void makeCardClickable(VBox card, String title, Runnable action) {
        if (action == null) return;
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        card.setAccessibleText(title + " dashboard card. Activate to open details.");
        Tooltip.install(card, new Tooltip("Open " + title.toLowerCase() + " details"));
        card.setOnMouseClicked(e -> action.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                action.run();
                e.consume();
            }
        });
        card.setOnMouseEntered(e -> Animations.hoverLift(card));
        card.setOnMouseExited(e -> Animations.hoverReset(card));
    }

    private VBox stat(String label, String value, String icon, String iconStyle, String caption, Runnable action) {
        VBox card = new VBox(5);
        card.getStyleClass().add("stat-card");
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label iconLabel = new Label();
        iconLabel.setGraphic(Ui.icon(icon, 17));
        iconLabel.getStyleClass().addAll("stat-icon", iconStyle);
        Label title = Ui.label(label.toUpperCase(), "stat-label");
        HBox.setHgrow(title, Priority.ALWAYS);
        top.getChildren().addAll(iconLabel, title);
        Label val = Ui.label(value, "stat-value");
        Label cap = Ui.label(caption == null ? "" : caption, "muted");
        card.getChildren().addAll(top, val, cap);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-min-width: 0; -fx-pref-width: 0;");
        makeCardClickable(card, label, action);
        return card;
    }

    private static String shorten(String s) { return s.length() > 12 ? s.substring(0, 11) + "…" : s; }

    private static final class CircleDot extends StackPane {
        CircleDot() {
            javafx.scene.shape.Circle c = new javafx.scene.shape.Circle(4);
            c.setFill(Color.web("#e9822d"));
            setPadding(new Insets(6, 0, 0, 0));
            getChildren().add(c);
        }
    }
}
