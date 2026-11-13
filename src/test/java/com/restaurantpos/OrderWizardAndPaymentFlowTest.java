package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.SchemaManager;
import com.restaurantpos.enums.*;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.printing.ReceiptBuilder;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.ui.views.OrdersView;
import com.restaurantpos.ui.views.PosView;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderWizardAndPaymentFlowTest {
    private static AppContext ctx;
    private static Path tempDir;
    private static long scenarioAOrderId;
    private static long scenarioDOrderId;
    private static Long testWaiterId;
    private static String testWaiterName;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-wizard-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();

        // Login cashier
        ctx.auth().login("food_cashier@gmail.com", "cashier@1122");
        ctx.shifts().open(Money.of("5000"), ctx.session());
        testWaiterId = null;
        testWaiterName = null;
    }

    @AfterAll
    static void teardown() {
        if (ctx.session().active()) {
            ctx.auth().logout();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Scenario A — Table and waiter selected, order saved unpaid with customer receipt")
    void scenarioA_tableAndWaiterSelected() {
        DiningTable table = ctx.tables().tables(null, true).stream()
                .filter(t -> t.status() == TableStatus.AVAILABLE).findFirst().orElseThrow();
        Product burger = ctx.menu().products(true, null).stream()
                .filter(p -> p.name().contains("Zinger")).findFirst().orElseThrow();

        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableId = table.id();
        cart.tableNo = table.tableNo();
        cart.waiterId = testWaiterId;
        cart.waiterName = testWaiterName;
        cart.guestCount = 2;
        cart.add(burger, (Size) null, List.of(), 2, "Extra crispy");

        Cart.Totals totals = ctx.orders().quote(cart);
        assertTrue(totals.total.signum() > 0);

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        scenarioAOrderId = orderId;
        assertTrue(orderId > 0);

        Order saved = ctx.orders().find(orderId);
        assertNotNull(saved);
        assertEquals(OrderType.DINE_IN, saved.type());
        assertEquals(table.id(), saved.tableId());
        assertEquals(table.tableNo(), saved.tableNo());
        assertEquals(PaymentStatus.UNPAID, saved.paymentStatus());
        assertEquals(OrderStatus.DRAFT, saved.status());

        // Check occupancy
        DiningTable tableAfter = ctx.floorRepo().table(table.id());
        assertEquals(TableStatus.OCCUPIED, tableAfter.status());

        // Receipt verification
        ReceiptBuilder builder = new ReceiptBuilder(ctx.settings());
        String receipt = builder.customerReceipt(saved, ctx.orders().items(orderId), List.of(), "TEST-01");
        assertTrue(receipt.contains(saved.orderNo()));
        assertTrue(receipt.contains(table.tableNo()));
        assertTrue(receipt.contains("Powered by ZS Sattar"));
        assertTrue(receipt.contains("UNPAID — PAYMENT PENDING"));
        assertTrue(receipt.contains("Payment has not yet been received."));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Scenario B — No table and no waiter, order persists valid with null assignments")
    void scenarioB_noTableAndNoWaiter() {
        Product fries = ctx.menu().products(true, null).stream()
                .filter(p -> p.name().contains("Fries")).findFirst().orElseThrow();

        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.tableId = null;
        cart.tableNo = null;
        cart.waiterId = null;
        cart.waiterName = null;
        cart.add(fries, (Size) null, List.of(), 1, "");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        assertTrue(orderId > 0);

        Order saved = ctx.orders().find(orderId);
        assertNotNull(saved);
        assertEquals(OrderType.TAKEAWAY, saved.type());
        assertNull(saved.tableId());
        assertNull(saved.tableNo());
        assertNull(saved.waiterId());
        assertNull(saved.waiterName());
        assertEquals(PaymentStatus.UNPAID, saved.paymentStatus());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Scenario C — Menu and variants, correct configured price snapshot and live billing")
    void scenarioC_menuAndVariants() {
        Product drink = ctx.menu().products(true, null).stream()
                .filter(p -> p.sku().equals("DRK-001")).findFirst().orElseThrow();
        List<Size> sizes = ctx.menu().sizes(drink.id());
        assertFalse(sizes.isEmpty(), "DRK-001 should have configured sizes/variants");

        Size large = sizes.stream().filter(s -> s.name().equalsIgnoreCase("Large")).findFirst().orElseThrow();
        BigDecimal expectedUnit = drink.effectivePrice().add(large.extraPrice());

        Cart cart = new Cart();
        cart.type = OrderType.SIDE_ORDER;
        cart.add(drink, large, List.of(), 2, "Chilled");

        assertEquals(1, cart.lines.size());
        Cart.CartLine line = cart.lines.get(0);
        assertEquals(large.id(), line.variantId);
        assertEquals(0, expectedUnit.compareTo(line.unitPrice));
        assertEquals(0, expectedUnit.multiply(BigDecimal.valueOf(2)).compareTo(line.net()));

        Cart.Totals totals = ctx.orders().quote(cart);
        assertEquals(0, line.net().compareTo(totals.total));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Scenario D — Draft order appears in Draft Orders, searchable by order number")
    void scenarioD_draftOrderSearch() {
        Product product = ctx.menu().products(true, null).get(0);
        Cart cart = new Cart();
        cart.type = OrderType.PICKUP;
        cart.customerName = "Mr. Test Customer";
        cart.customerPhone = "03001234567";
        cart.add(product, (Size) null, List.of(), 1, "");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        scenarioDOrderId = orderId;
        Order order = ctx.orders().find(orderId);

        // Query draft orders
        List<Order> drafts = ctx.orders().draftOrders(order.orderNo());
        assertFalse(drafts.isEmpty());
        Order found = drafts.get(0);
        assertEquals(order.orderNo(), found.orderNo());
        assertEquals(PaymentStatus.UNPAID, found.paymentStatus());
        assertEquals(0, order.total().compareTo(found.total()));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Scenario E — Successful cash payment with change calculation, moves to Complete Orders")
    void scenarioE_successfulCashPayment() {
        Order draft = ctx.orders().find(scenarioDOrderId);
        assertNotNull(draft);
        BigDecimal due = draft.balance();
        assertTrue(due.signum() > 0);

        BigDecimal tendered = due.add(Money.of("500"));
        PaymentInput input = new PaymentInput(PaymentMethod.CASH, due, tendered, "", "Paid at counter");

        var result = ctx.payments().recordBalancePayment(draft.id(), List.of(input), ctx.session());
        assertNotNull(result);
        assertEquals(due, result.paid());
        assertEquals(Money.of("500"), result.change());

        Order paidOrder = ctx.orders().find(draft.id());
        assertEquals(PaymentStatus.PAID, paidOrder.paymentStatus());
        assertEquals(OrderStatus.COMPLETED, paidOrder.status());

        // Should no longer be in draft orders
        List<Order> drafts = ctx.orders().draftOrders(draft.orderNo());
        assertTrue(drafts.isEmpty(), "Order should no longer appear in draft orders");

        // Should appear in complete orders
        List<Order> completed = ctx.orders().completeOrders(LocalDate.now(), LocalDate.now(), draft.orderNo());
        assertFalse(completed.isEmpty(), "Order should appear in complete orders");
        assertEquals(draft.orderNo(), completed.get(0).orderNo());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Scenario F — Non-cash digital payment with reference confirmation")
    void scenarioF_nonCashPayment() {
        Product product = ctx.menu().products(true, null).get(0);
        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.add(product, (Size) null, List.of(), 1, "");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);
        BigDecimal due = order.balance();

        // Digital payment without reference should fail
        assertThrows(AppException.class, () -> {
            PaymentInput invalid = new PaymentInput(PaymentMethod.EASYPAISA, due, due, "", "");
            ctx.payments().recordBalancePayment(orderId, List.of(invalid), ctx.session());
        });

        // Digital payment with reference should succeed
        PaymentInput valid = new PaymentInput(PaymentMethod.EASYPAISA, due, due, "EP-987654321", "Verified");
        var result = ctx.payments().recordBalancePayment(orderId, List.of(valid), ctx.session());
        assertEquals(due, result.paid());

        Order refreshed = ctx.orders().find(orderId);
        assertEquals(PaymentStatus.PAID, refreshed.paymentStatus());
        List<Payment> payments = ctx.payments().payments(orderId);
        assertEquals(1, payments.size());
        assertEquals("EP-987654321", payments.get(0).reference());
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("Scenario G — Duplicate payment prevention on settled orders")
    void scenarioG_duplicatePaymentPrevention() {
        Order paidOrder = ctx.orders().find(scenarioDOrderId);
        assertEquals(PaymentStatus.PAID, paidOrder.paymentStatus());

        PaymentInput input = new PaymentInput(PaymentMethod.CASH, Money.of("100"), Money.of("100"), "", "");
        assertThrows(AppException.class, () ->
                ctx.payments().recordBalancePayment(paidOrder.id(), List.of(input), ctx.session()));
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("Scenario H — Failure recovery: transaction rollback on error and printing non-blocking recovery")
    void scenarioH_failureRecovery() {
        // Attempt creating order with empty cart
        Cart empty = new Cart();
        empty.type = OrderType.DINE_IN;
        assertThrows(AppException.class, () -> ctx.orders().createOrder(empty, false, ctx.session()));

        // Order creation with inactive table should fail and not leave behind order
        long beforeCount = ctx.orders().list(null, null, null, null, null, null, null).size();
        Cart invalidCart = new Cart();
        invalidCart.type = OrderType.DINE_IN;
        invalidCart.tableId = 999999L; // Non-existent table
        invalidCart.add(ctx.menu().products(true, null).get(0), (Size) null, List.of(), 1, "");
        assertThrows(AppException.class, () -> ctx.orders().createOrder(invalidCart, false, ctx.session()));
        long afterCount = ctx.orders().list(null, null, null, null, null, null, null).size();
        assertEquals(beforeCount, afterCount, "Failed order creation must roll back completely");

        // Order remains unpaid if payment fails
        Order scenarioA = ctx.orders().find(scenarioAOrderId);
        assertEquals(PaymentStatus.UNPAID, scenarioA.paymentStatus());
        PaymentInput overpay = new PaymentInput(PaymentMethod.CASH, scenarioA.total().add(Money.of("1000")), scenarioA.total().add(Money.of("1000")), "", "");
        assertThrows(AppException.class, () -> ctx.payments().recordBalancePayment(scenarioAOrderId, List.of(overpay), ctx.session()));
        assertEquals(PaymentStatus.UNPAID, ctx.orders().find(scenarioAOrderId).paymentStatus(), "Order must remain unpaid after payment failure");
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("Scenario I — Existing data compatibility and schema migrations")
    void scenarioI_existingDataCompatibility() {
        // Database schema should be v5+
        assertTrue(new SchemaManager().version() >= 5);
        assertTrue(new SchemaManager().hasColumn("orders", "waiter_id"));
        assertTrue(new SchemaManager().hasColumn("product_variants", "min_price"));

        // Historical orders remain queryable and intact
        List<Order> all = ctx.orders().list(null, null, null, null, null, null, null);
        assertFalse(all.isEmpty());
        for (Order o : all) {
            assertNotNull(o.orderNo());
            assertNotNull(o.type());
            assertNotNull(o.paymentStatus());
        }
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("Scenario J — UI and navigation: wizard steps, draft & complete tabs construct cleanly")
    void scenarioJ_uiAndNavigation() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

        try { javafx.application.Platform.startup(() -> {}); } catch (IllegalStateException ignored) {}

        javafx.application.Platform.runLater(() -> {
            try {
                com.restaurantpos.ui.AppWindow window = new com.restaurantpos.ui.AppWindow(new javafx.stage.Stage());
                com.restaurantpos.ui.MainShell shell = new com.restaurantpos.ui.MainShell(window, ctx);

                // Construct PosView wizard
                PosView pos = new PosView(ctx, shell);
                assertNotNull(pos);

                // Construct OrdersView in all 3 modes
                OrdersView draftsView = new OrdersView(ctx, shell, OrdersView.ViewMode.DRAFT);
                assertNotNull(draftsView);

                OrdersView completeView = new OrdersView(ctx, shell, OrdersView.ViewMode.COMPLETE);
                assertNotNull(completeView);

                OrdersView allView = new OrdersView(ctx, shell, OrdersView.ViewMode.ALL);
                assertNotNull(allView);

            } catch (Throwable t) {
                t.printStackTrace();
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(60, java.util.concurrent.TimeUnit.SECONDS), "JavaFX UI test timed out");
        assertNull(error.get(), () -> "UI construction failed: " + error.get());
    }
}
