package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.*;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.model.Sales.Shift;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CoreFlowTest {
    private static AppContext ctx;
    private static long orderId;
    private static long shiftId;

    @BeforeAll
    static void setup() throws Exception {
        Path temp = Files.createTempDirectory("restaurant-pos-test-");
        System.setProperty("pos.home", temp.toString());
        AppPaths.overrideRoot(temp);
        Database.use(temp.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void seedsInitialAccountsAndMenu() {
        assertNotNull(ctx.users().findAuth("food_cashier@gmail.com"));
        assertNotNull(ctx.users().findAuth("food_admin@gmail.com"));
        assertTrue(ctx.menu().categories(true).size() >= 5);
        assertTrue(ctx.menu().sellable().size() >= 20);
        assertEquals(8, ctx.tables().totalTables(null));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void cashierCanSignInAndPasswordIsHashed() {
        var user = ctx.auth().login("food_cashier@gmail.com", "cashier@1122");
        assertEquals(Role.CASHIER, user.role());
        var auth = ctx.users().findAuth("food_cashier@gmail.com");
        assertNotEquals("cashier@1122", auth.hash());
        assertTrue(auth.hash().length() > 40);
        assertThrows(AppException.class, () -> ctx.auth().login("food_cashier@gmail.com", "wrong-password"));
        ctx.auth().login("food_cashier@gmail.com", "cashier@1122");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void shiftAndCashCheckoutAreConsistent() {
        var user = ctx.session().user();
        Shift shift = ctx.shifts().open(Money.of("5000"), ctx.session());
        shiftId = shift.id();
        BigDecimal expected = ctx.shifts().expected(shift.id());
        assertEquals(Money.of("5000"), expected);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void checkoutRecordsPaymentAndDeductsRecipeStock() {
        Product product = ctx.menu().sellable().stream().filter(p -> p.name().equals("Zinger Burger")).findFirst().orElseThrow();
        DiningTable table = ctx.tables().tables(null, true).stream()
                .filter(t -> t.status() == TableStatus.AVAILABLE).findFirst().orElseThrow();
        BigDecimal stockBefore = ctx.inventoryRepo().ingredients(true).stream()
                .filter(i -> i.name().equals("Chicken Fillet")).findFirst().orElseThrow().stockQty();
        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableId = table.id();
        cart.tableNo = table.tableNo();
        cart.guestCount = 2;
        cart.shiftId = ctx.shifts().current(ctx.session().userId()).id();
        cart.add(product, null, Money.ZERO, List.of(), 2, "No mayo");
        BigDecimal total = ctx.orders().quote(cart).total;
        assertTrue(total.signum() > 0);

        var result = ctx.payments().checkout(cart,
                List.of(new PaymentService.PaymentInput(PaymentMethod.CASH, total, total.add(Money.of("500")), "", "")),
                ctx.session());
        orderId = result.orderId();
        Order order = ctx.orders().find(orderId);
        assertEquals(OrderStatus.CONFIRMED, order.status());
        assertEquals(PaymentStatus.PAID, order.paymentStatus());
        assertEquals(Money.of("500"), result.change());
        BigDecimal stockAfter = ctx.inventoryRepo().ingredients(true).stream()
                .filter(i -> i.name().equals("Chicken Fillet")).findFirst().orElseThrow().stockQty();
        assertTrue(stockAfter.compareTo(stockBefore) < 0);
        assertThrows(AppException.class, () -> ctx.orders().cancel(orderId, "", ctx.session()));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void refundCannotExceedRefundableAmount() {
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
        Payment payment = ctx.payments().payments(orderId).get(0);
        assertThrows(AppException.class, () -> ctx.payments().refund(payment.id(),
                payment.amount().add(Money.of("1")), "over-refund", ctx.session()));
        ctx.payments().refund(payment.id(), Money.of("100"), "Customer complaint", ctx.session());
        Payment refreshed = ctx.orderRepo().payment(payment.id());
        assertEquals(0, Money.of("100").compareTo(refreshed.refundedAmount()));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, ctx.orders().find(orderId).paymentStatus());
        ctx.orders().cancel(orderId, "Customer changed mind", ctx.session());
        assertEquals(OrderStatus.CANCELLED, ctx.orders().find(orderId).status());
        var ingredient = ctx.inventoryRepo().ingredients(true).stream().filter(i -> i.name().equals("Chicken Fillet")).findFirst().orElseThrow();
        assertTrue(ingredient.stockQty().signum() >= 0);
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void backupIsVerifiedAndCanBeValidated() {
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
        var backup = ctx.backups().createBackup(ctx.session(), false);
        assertTrue(backup.success());
        assertNotNull(backup.folder());
        assertTrue(Files.exists(backup.folder().resolve("restaurant.db")));
        assertTrue(Files.exists(backup.folder().resolve("manifest.json")));
        assertTrue(ctx.backups().validateDatabase(backup.folder().resolve("restaurant.db")));
        assertTrue(backup.archive() == null || Files.exists(backup.archive()));
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void allScreensConstructWithoutExceptions() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        try { javafx.application.Platform.startup(() -> {}); } catch (IllegalStateException ignored) {}
        javafx.application.Platform.runLater(() -> {
            try {
                if (!ctx.session().active()) ctx.auth().login("food_admin@gmail.com", "admin@1122");
                com.restaurantpos.ui.AppWindow window = new com.restaurantpos.ui.AppWindow(new javafx.stage.Stage());
                com.restaurantpos.ui.MainShell shell = new com.restaurantpos.ui.MainShell(window, ctx);
                javafx.scene.Scene scene = new javafx.scene.Scene(shell, 1400, 880);
                com.restaurantpos.ui.components.Ui.applyStyles(scene);
                String[] keys = {"dashboard", "pos", "draft-orders", "complete-orders", "floors", "orders", "shift", "menu",
                        "inventory", "purchasing", "payments", "expenses", "staff", "reports",
                        "receipts", "notifications", "audit", "backup", "settings"};
                for (String key : keys) {
                    assertNotNull(com.restaurantpos.ui.ViewFactory.create(key, ctx, shell), "View " + key);
                }
                ctx.auth().logout();
                ctx.auth().login("food_cashier@gmail.com", "cashier@1122");
                assertNotNull(com.restaurantpos.ui.ViewFactory.create("dashboard", ctx, shell), "Cashier dashboard");
                javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/view/login.fxml"));
                loader.setController(new com.restaurantpos.controller.LoginController(ctx, window));
                loader.load();
            } catch (Throwable t) {
                t.printStackTrace();
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(60, java.util.concurrent.TimeUnit.SECONDS), "JavaFX smoke test timed out");
        assertNull(error.get(), () -> "UI construction failed: " + error.get());
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    void driveFolderLinksAndIdsAreParsed() {
        assertEquals("1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH",
                com.restaurantpos.drive.DriveService.folderIdFrom("https://drive.google.com/drive/folders/1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH"));
        assertEquals("1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH",
                com.restaurantpos.drive.DriveService.folderIdFrom("1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH"));
        assertEquals("1mUE-DOBppqCQDxvE21gzmqBqi_KvIKTQ",
                com.restaurantpos.drive.DriveService.folderIdFrom("https://drive.google.com/drive/folders/1mUE-DOBppqCQDxvE21gzmqBqi_KvIKTQ"));
    }}
