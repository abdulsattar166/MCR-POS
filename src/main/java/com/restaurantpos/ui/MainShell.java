package com.restaurantpos.ui;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.enums.Role;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.ui.views.AppView;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MainShell extends StackPane implements Navigator {
    private record NavItem(String key, String label, String icon) {}
    private record Route(String label, String icon) {}

    private final AppWindow window;
    private final AppContext ctx;
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final Map<String, Node> views = new HashMap<>();
    private final StackPane content = new StackPane();
    private final Label titleLabel = new Label();
    private final VBox navBox = new VBox(2);
    private final Map<String, Button> navButtons = new HashMap<>();
    private final HBox sidebar;
    private final StackPane notifGraphic = new StackPane();
    private final Circle notifDot = new Circle(4);
    private final Label notifCount = new Label();
    private final Label clock = new Label();
    private final Label shiftStatus = new Label();
    private String currentKey;
    private long lastTouch = 0;

    public MainShell(AppWindow window, AppContext ctx) {
        this.window = window;
        this.ctx = ctx;
        getStyleClass().add("main-shell");
        setStyle("-fx-background-color: #f7f6f2;");

        sidebar = buildSidebar();
        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setTop(buildTopbar());
        content.setPadding(new Insets(16, 20, 16, 20));
        content.getStyleClass().add("content-area");
        StackPane.setAlignment(content, Pos.TOP_LEFT);
        root.setCenter(content);

        getChildren().add(root);
        setOnMouseMoved(e -> touch());
        setOnKeyPressed(e -> touch());
    }

    private HBox buildSidebar() {
        VBox logo = new VBox(3);
        logo.getStyleClass().add("sidebar-logo-box");
        HBox brandRow = new HBox(10);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.image.ImageView logoView = new javafx.scene.image.ImageView(BrandLogo.fxRoundedLogo(ctx.settings(), 44, 10));
        logoView.setFitWidth(44); logoView.setFitHeight(44); logoView.setPreserveRatio(true); logoView.setSmooth(true);
        VBox names = new VBox(0);
        Label name = new Label("MUBARAK");
        name.getStyleClass().add("sidebar-brand");
        Label sub = new Label("CAFE & RESTAURANT");
        sub.getStyleClass().add("sidebar-brand-sub");
        names.getChildren().addAll(name, sub);
        brandRow.getChildren().addAll(logoView, names);
        logo.getChildren().add(brandRow);

        buildRoutes();
        navBox.setPadding(new Insets(0));
        String lastSection = null;
        boolean owner = ctx.isOwner() || ctx.session().role() == Role.MANAGER;
        for (NavItem item : navItems(owner)) {
            String section = sectionFor(item.key());
            if (!Objects.equals(section, lastSection) && section != null) {
                Label s = new Label(section.toUpperCase());
                s.getStyleClass().add("sidebar-section");
                navBox.getChildren().add(s);
                lastSection = section;
            }
            Button b = new Button(item.label());
            b.setGraphic(Ui.icon(item.icon(), 15));
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> navigate(item.key()));
            navButtons.put(item.key(), b);
            navBox.getChildren().add(b);
        }

        ScrollPane navScroll = new ScrollPane(navBox);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(navScroll, Priority.ALWAYS);

        Region divider = new Region();
        divider.getStyleClass().add("sidebar-divider");
        Label offline = new Label("● Offline ready  •  Local DB");
        offline.getStyleClass().add("sidebar-foot");
        Label version = new Label("Mubarak POS v" + ctx.settings().appVersion());
        version.getStyleClass().add("sidebar-foot");

        VBox side = new VBox(logo, navScroll, divider, offline, version);
        side.getStyleClass().add("sidebar");
        HBox wrapper = new HBox(side);
        HBox.setHgrow(side, Priority.ALWAYS);
        return wrapper;
    }

    private void buildRoutes() {
        routes.put("dashboard", new Route(ctx.canManage() ? "Owner Dashboard" : "Cashier Dashboard", "fas-chart-pie"));
        routes.put("pos", new Route("New Order", "fas-cash-register"));
        routes.put("draft-orders", new Route("Draft Orders", "fas-clipboard-list"));
        routes.put("complete-orders", new Route("Complete Orders", "fas-check-double"));
        routes.put("floors", new Route("Floors & Tables", "fas-layer-group"));
        routes.put("orders", new Route("All Orders", "fas-history"));
        routes.put("customer-ledger", new Route("Customer Ledger", "fas-book"));
        routes.put("shift", new Route("My Shift & Cash", "fas-wallet"));
        routes.put("menu", new Route("Menu Management", "fas-hamburger"));
        routes.put("inventory", new Route("Inventory", "fas-boxes"));
        routes.put("purchasing", new Route("Purchasing & Suppliers", "fas-truck-loading"));
        routes.put("payments", new Route("Payments & Refunds", "fas-credit-card"));
        routes.put("expenses", new Route("Expenses", "fas-file-invoice-dollar"));
        routes.put("staff", new Route("Staff & Cashiers", "fas-users"));
        routes.put("reports", new Route("Reports & Analytics", "fas-chart-line"));
        routes.put("receipts", new Route("Receipts & Printers", "fas-receipt"));
        routes.put("notifications", new Route("Notifications", "fas-bell"));
        routes.put("audit", new Route("Audit & Security", "fas-shield-alt"));
        routes.put("backup", new Route("Backup & Recovery", "fas-cloud-upload-alt"));
        routes.put("settings", new Route("System Settings", "fas-cog"));
    }

    private List<NavItem> navItems(boolean owner) {
        List<NavItem> items = new ArrayList<>();
        items.add(new NavItem("dashboard", "Dashboard", "fas-chart-pie"));
        items.add(new NavItem("pos", "New Order", "fas-cash-register"));
        items.add(new NavItem("draft-orders", "Draft Orders", "fas-clipboard-list"));
        items.add(new NavItem("complete-orders", "Complete Orders", "fas-check-double"));
        items.add(new NavItem("floors", "Floors & Tables", "fas-layer-group"));
        items.add(new NavItem("orders", "All Orders", "fas-history"));
        items.add(new NavItem("customer-ledger", "Customer Ledger", "fas-book"));
        items.add(new NavItem("shift", "My Shift & Cash", "fas-wallet"));
        items.add(new NavItem("reports", "Reports & Analytics", "fas-chart-line"));
        if (owner) {
            items.add(new NavItem("menu", "Menu Management", "fas-hamburger"));
            items.add(new NavItem("inventory", "Inventory", "fas-boxes"));
            items.add(new NavItem("purchasing", "Purchasing", "fas-truck-loading"));
            items.add(new NavItem("payments", "Payments & Refunds", "fas-credit-card"));
            items.add(new NavItem("expenses", "Expenses", "fas-file-invoice-dollar"));
            items.add(new NavItem("staff", "Staff & Cashiers", "fas-users"));
            items.add(new NavItem("receipts", "Receipts & Printers", "fas-receipt"));
            items.add(new NavItem("notifications", "Notifications", "fas-bell"));
            items.add(new NavItem("audit", "Audit & Security", "fas-shield-alt"));
            items.add(new NavItem("backup", "Backup & Recovery", "fas-cloud-upload-alt"));
            items.add(new NavItem("settings", "System Settings", "fas-cog"));
        } else {
            items.add(new NavItem("expenses", "Expenses", "fas-file-invoice-dollar"));
            items.add(new NavItem("notifications", "Notifications", "fas-bell"));
        }
        return items;
    }

    private String sectionFor(String key) {
        return switch (key) {
            case "dashboard" -> "Overview";
            case "pos", "draft-orders", "complete-orders", "floors", "orders", "customer-ledger", "shift" -> "Service";
            case "menu", "inventory", "purchasing", "payments", "expenses", "staff" -> "Management";
            case "reports", "receipts" -> "Insights";
            case "notifications", "audit", "backup", "settings" -> "System";
            default -> null;
        };
    }

    private HBox buildTopbar() {
        Button menuToggle = Ui.iconButton("fas-bars", "Toggle navigation", this::toggleSidebar);
        titleLabel.getStyleClass().add("topbar-title");
        VBox titleBox = new VBox(1, titleLabel);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        clock.getStyleClass().add("clock-label");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy  •  hh:mm:ss a");
        clock.setText(LocalDateTime.now().format(fmt));
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            clock.setText(LocalDateTime.now().format(fmt));
            updateShiftStatus();
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        shiftStatus.getStyleClass().add("offline-pill");
        updateShiftStatus();

        Label offline = Ui.label("● Offline ready", "offline-pill");

        notifDot.setFill(javafx.scene.paint.Color.web("#d84a3a"));
        notifDot.setStroke(javafx.scene.paint.Color.WHITE);
        notifDot.setStrokeWidth(1.5);
        notifDot.setTranslateX(7); notifDot.setTranslateY(-7);
        notifDot.setVisible(false);
        notifCount.getStyleClass().add("badge");
        notifCount.setTranslateX(7); notifCount.setTranslateY(-7);
        notifCount.setVisible(false);
        FontIcon bell = Ui.icon("fas-bell", 17);
        notifGraphic.getChildren().addAll(bell, notifDot, notifCount);
        Button notif = new Button();
        notif.setGraphic(notifGraphic);
        notif.getStyleClass().add("notif-button");
        notif.setTooltip(new Tooltip("Notifications"));
        notif.setOnAction(e -> navigate("notifications"));

        User user = ctx.session().user();
        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("avatar");
        Label initials = new Label(initials(user.fullName()));
        initials.getStyleClass().add("avatar-text");
        avatar.getChildren().add(initials);
        VBox who = new VBox(-1, Ui.label(user.fullName(), "h3"), Ui.muted(user.role().label()));
        HBox userChip = new HBox(avatar, who);
        userChip.getStyleClass().add("user-chip");
        userChip.setAlignment(Pos.CENTER_LEFT);

        Button logout = Ui.iconButton("fas-sign-out-alt", "Sign out", window::logout);
        Button changePwd = Ui.iconButton("fas-key", "Change password", this::changePassword);

        HBox bar = new HBox(10, menuToggle, titleBox, shiftStatus, offline, clock, notif, changePwd, logout, userChip);
        bar.getStyleClass().add("topbar");
        clock.managedProperty().bind(bar.widthProperty().greaterThan(920));
        clock.visibleProperty().bind(bar.widthProperty().greaterThan(920));
        offline.managedProperty().bind(bar.widthProperty().greaterThan(780));
        offline.visibleProperty().bind(bar.widthProperty().greaterThan(780));
        return bar;
    }

    private void updateShiftStatus() {
        if (ctx.session() == null || !ctx.session().active() || ctx.session().userId() == null) {
            shiftStatus.setText("No open shift");
            shiftStatus.setStyle("-fx-background-color: rgba(224, 142, 38, 0.16); -fx-text-fill: #8a6113;");
            return;
        }
        var shift = ctx.shifts().current(ctx.session().userId());
        if (shift == null) {
            shiftStatus.setText("No open shift");
            shiftStatus.setStyle("-fx-background-color: rgba(224, 142, 38, 0.16); -fx-text-fill: #8a6113;");
        } else {
            shiftStatus.setText("Shift open");
            shiftStatus.setStyle("-fx-background-color: rgba(38, 112, 71, 0.15); -fx-text-fill: #267047;");
        }
    }

    private String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        return (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase();
    }

    private void changePassword() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(window.stage());
        d.setTitle("Change password");
        d.setHeaderText("Update your sign-in password");
        PasswordField current = Ui.passwordField("Current password");
        PasswordField next = Ui.passwordField("New password");
        PasswordField confirm = Ui.passwordField("Confirm new password");
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, Ui.field("Current password", current), Ui.field("New password", next),
                Ui.field("Confirm new password", confirm), error);
        box.setPadding(new Insets(12, 0, 0, 0));
        box.setPrefWidth(400);
        d.getDialogPane().setContent(box);
        ButtonType ok = new ButtonType("Change password", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ok);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button okButton = (Button) d.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try { ctx.auth().changeOwnPassword(current.getText(), next.getText(), confirm.getText()); }
            catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        d.showAndWait().filter(b -> b == ok).ifPresent(b -> showToast("Password changed successfully.", "SUCCESS"));
    }

    private void toggleSidebar() {
        boolean showing = sidebar.isVisible();
        TranslateTransition tt = new TranslateTransition(Duration.millis(220), sidebar);
        if (showing) {
            tt.setToX(-sidebar.getWidth());
            tt.setOnFinished(e -> { sidebar.setVisible(false); sidebar.setManaged(false); });
        } else {
            sidebar.setVisible(true); sidebar.setManaged(true);
            sidebar.setTranslateX(-sidebar.getWidth());
            tt.setToX(0);
        }
        tt.play();
    }

    public void start() {
        String defaultKey = ctx.isOwner() || ctx.session().role() == Role.MANAGER ? "dashboard" : "pos";
        if (!routes.containsKey(defaultKey)) defaultKey = "pos";
        navigate(defaultKey);
        refreshNotifications();
        sessionWatch();
        Platform.runLater(this::installAccelerators);
    }

    private void installAccelerators() {
        Scene scene = getScene();
        if (scene == null) return;
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F1), () -> navigate("pos"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F2), () -> navigate("draft-orders"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F3), () -> navigate("complete-orders"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F4), () -> navigate("shift"));
        if (ctx.isOwner()) scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), () -> navigate("dashboard"));
    }

    private void sessionWatch() {
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(20), e -> {
            int timeout = ctx.settings().sessionTimeoutMinutes();
            if (ctx.session().idleMinutesExceeded(timeout)) {
                if (ctx.session().active() && ctx.session().role() == com.restaurantpos.enums.Role.CASHIER) {
                    try {
                        ctx.shifts().autoCloseCashierShift(ctx.session().user(), ctx.session(), "Auto-closed on session timeout");
                    } catch (Exception ex) {
                        // preserve data and continue session exit
                    }
                }
                ctx.auth().logout();
                window.showLogin();
                Ui.error(window.stage(), "Session timed out",
                        "You were signed out after " + timeout + " minutes of inactivity. Sign in again to continue.");
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void touch() {
        long now = System.currentTimeMillis();
        if (now - lastTouch > 15000) {
            lastTouch = now;
            ctx.session().touch();
        }
    }

    @Override
    public void navigate(String key) {
        Route route = routes.get(key);
        if (route == null) return;
        Node node = views.get(key);
        if (node == null) {
            try {
                node = com.restaurantpos.ui.ViewFactory.create(key, ctx, this);
                views.put(key, node);
            } catch (Exception e) {
                e.printStackTrace();
                Ui.error(window.stage(), "Screen failed to open", e.getMessage());
                return;
            }
        } else if (node instanceof AppView av) {
            try { av.refresh(); } catch (Exception e) {
                Ui.error(window.stage(), "Refresh failed", e.getMessage());
            }
        }
        currentKey = key;
        titleLabel.setText(route.label());
        navButtons.forEach((k, b) -> b.getStyleClass().remove("active"));
        Button active = navButtons.get(key);
        if (active != null) active.getStyleClass().add("active");
        node.setOpacity(0);
        node.setTranslateY(8);
        content.getChildren().setAll(node);
        FadeTransition ft = new FadeTransition(Duration.millis(230), node); ft.setToValue(1);
        TranslateTransition tt = new TranslateTransition(Duration.millis(260), node); tt.setToY(0);
        tt.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(ft, tt).play();
        refreshNotifications();
        updateShiftStatus();
        ctx.session().touch();
    }

    @Override
    public void showToast(String message, String severity) {
        Ui.toast(this, message, severity);
        refreshNotifications();
    }

    @Override
    public void startOrder(long tableId) {
        UiState.pendingTableId = tableId;
        navigate("pos");
    }

    @Override
    public void previewReceipt(long orderId) {
        var order = ctx.orders().find(orderId);
        if (order == null) {
            showToast("Order not found for receipt preview.", "WARNING");
            return;
        }
        window.openReceiptPreview(order);
    }

    @Override
    public void refreshNotifications() {
        int count = ctx.systemRepo().unreadCount();
        notifCount.setText(count > 99 ? "99+" : String.valueOf(count));
        notifCount.setVisible(count > 0);
        notifDot.setVisible(false);
    }

    @Override
    public Window window() { return window.stage(); }

    public String currentKey() { return currentKey; }
}
