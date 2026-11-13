package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.OrderStatus;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.printing.ReceiptPrintingService.PrintResult;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.OrderService;
import com.restaurantpos.service.OrderService.DraftChangeLine;
import com.restaurantpos.service.OrderService.DraftItemEdit;
import com.restaurantpos.service.OrderService.DraftSaveResult;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DraftOrderEditingWorkflowTest {
    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-draft-edit-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();

        if (!ctx.session().active()) {
            ctx.auth().login("food_admin@gmail.com", "admin@1122");
        }
        ctx.systemRepo().savePrinterConfig("Cashier", "CASHIER", "");
        ctx.systemRepo().savePrinterConfig("Kitchen", "KITCHEN", "");
    }

    @AfterAll
    static void tearDown() {
        if (ctx != null && ctx.session().active()) {
            ctx.auth().logout();
        }
        AppContext.resetForTesting();
    }

    private Order createDraftWithProducts(Product p1, int qty1, Product p2, int qty2) {
        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.waiterId = ctx.session().userId();
        cart.waiterName = ctx.session().user().fullName();
        cart.customerName = "Draft Customer";
        cart.add(p1, (Size) null, List.of(), qty1, "");
        if (p2 != null) {
            cart.add(p2, (Size) null, List.of(), qty2, "");
        }
        long id = ctx.orders().createOrder(cart, false, ctx.session());
        return ctx.orders().find(id);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void test_addRemoveIncreaseDecreaseRecalculateAndPersistAcrossRestart() throws Exception {
        List<Product> products = ctx.menu().products(true, null);
        assertTrue(products.size() >= 4, "Need at least 4 seeded products");
        Product p1 = products.get(0);
        Product p2 = products.get(1);
        Product p3 = products.get(2);
        Product p4 = products.get(3);

        Order draft = createDraftWithProducts(p1, 2, p2, 1);
        String origOrderNo = draft.orderNo();
        BigDecimal origTotal = draft.total();

        // 1 & 2. Add one item, then add multiple different items
        List<OrderItem> initialItems = ctx.orders().items(draft.id());
        List<DraftItemEdit> edits = new ArrayList<>();
        for (OrderItem i : initialItems) {
            edits.add(DraftItemEdit.fromOrderItem(i));
        }
        edits.add(new DraftItemEdit(null, p3.id(), null, p3.name(), p3.sku(),
                p3.effectivePrice(), Money.nz(p3.cost()), 1, "", "No spice", Money.ZERO));
        edits.add(new DraftItemEdit(null, p4.id(), null, p4.name(), p4.sku(),
                p4.effectivePrice(), Money.nz(p4.cost()), 2, "", "", Money.ZERO));

        DraftSaveResult addedRes = ctx.orders().saveDraftEdits(
                draft.id(), OrderService.draftSnapshotToken(draft, initialItems), edits, ctx.session());
        assertEquals(origOrderNo, addedRes.updatedOrder().orderNo(), "Order number must be preserved");
        assertEquals("Draft Customer", addedRes.updatedOrder().customerName());
        assertEquals(draft.waiterId(), addedRes.updatedOrder().waiterId(), "Waiter ID must be preserved");
        assertEquals(draft.waiterName(), addedRes.updatedOrder().waiterName(), "Waiter name must be preserved");
        assertEquals(4, addedRes.updatedItems().size());
        assertTrue(addedRes.updatedOrder().total().compareTo(origTotal) > 0, "Total must increase after adding items");
        assertEquals(2, addedRes.kitchenChanges().size());
        assertTrue(addedRes.kitchenChanges().stream().allMatch(c -> "ADDED".equals(c.changeType())));

        // 3, 4 & 6. Increase p1 qty (2 -> 3), decrease p4 qty (2 -> 1), remove p2
        BigDecimal totalAfterAdd = addedRes.updatedOrder().total();
        List<DraftItemEdit> edits2 = new ArrayList<>();
        for (OrderItem i : addedRes.updatedItems()) {
            if (i.productId().equals(p2.id())) {
                continue; // remove p2
            } else if (i.productId().equals(p1.id())) {
                DraftItemEdit d = DraftItemEdit.fromOrderItem(i);
                edits2.add(new DraftItemEdit(d.orderItemId(), d.productId(), d.variantId(), d.name(), d.sku(),
                        d.unitPrice(), d.cost(), d.qty() + 1, d.addons(), d.instructions(), d.discount()));
            } else if (i.productId().equals(p4.id())) {
                DraftItemEdit d = DraftItemEdit.fromOrderItem(i);
                edits2.add(new DraftItemEdit(d.orderItemId(), d.productId(), d.variantId(), d.name(), d.sku(),
                        d.unitPrice(), d.cost(), d.qty() - 1, d.addons(), d.instructions(), d.discount()));
            } else {
                edits2.add(DraftItemEdit.fromOrderItem(i));
            }
        }

        DraftSaveResult modRes = ctx.orders().saveDraftEdits(
                draft.id(), OrderService.draftSnapshotToken(addedRes.updatedOrder(), addedRes.updatedItems()), edits2, ctx.session());
        assertEquals(3, modRes.updatedItems().size());
        assertFalse(modRes.updatedItems().stream().anyMatch(i -> i.productId().equals(p2.id())), "Removed item must not exist");

        // 8. Restart application & verify persistence
        BigDecimal savedTotalBeforeRestart = modRes.updatedOrder().total();
        int savedCountBeforeRestart = modRes.updatedItems().size();
        ctx.auth().logout();
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");

        Order reloaded = ctx.orders().find(draft.id());
        List<OrderItem> reloadedItems = ctx.orders().items(draft.id());
        assertNotNull(reloaded);
        assertEquals(0, savedTotalBeforeRestart.compareTo(reloaded.total()), "Updated total must persist across restart");
        assertEquals(savedCountBeforeRestart, reloadedItems.size(), "Updated items must persist across restart");

        // 10. Complete payment and verify Paid Receipt contains only final saved items
        PaymentInput payIn = new PaymentInput(PaymentMethod.CASH, reloaded.total(), reloaded.total(), "", "Settled");
        ctx.payments().recordBalancePayment(reloaded.id(), List.of(payIn), ctx.session());
        Order completed = ctx.orders().find(reloaded.id());
        assertEquals(OrderStatus.COMPLETED, completed.status());
        assertEquals(PaymentStatus.PAID, completed.paymentStatus());

        AtomicReference<PrintResult> cRef = new AtomicReference<>();
        CountDownLatch cLatch = new CountDownLatch(1);
        ctx.printer().printCustomerReceipt(completed, false, ctx.session().userId(), r -> {
            cRef.set(r);
            cLatch.countDown();
        });
        assertTrue(cLatch.await(10, TimeUnit.SECONDS));
        assertTrue(cRef.get().success());
        assertTrue(cRef.get().customerText().contains("PAID CUSTOMER RECEIPT"));
        assertTrue(cRef.get().customerText().contains(p3.name()));
        assertFalse(cRef.get().customerText().contains(p2.name()), "Removed item must not appear in paid receipt");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void test_removeLastItemBlocksCompletion_and_decreaseTotal() {
        List<Product> products = ctx.menu().products(true, null);
        Product p1 = products.get(0);
        Product p2 = products.get(1);

        Order draft = createDraftWithProducts(p1, 1, p2, 1);
        BigDecimal initialTotal = draft.total();

        // Remove p2 -> total must decrease
        List<OrderItem> items = ctx.orders().items(draft.id());
        List<DraftItemEdit> onlyP1 = List.of(DraftItemEdit.fromOrderItem(items.get(0)));
        DraftSaveResult res1 = ctx.orders().saveDraftEdits(
                draft.id(), OrderService.draftSnapshotToken(draft, items), onlyP1, ctx.session());
        assertTrue(res1.updatedOrder().total().compareTo(initialTotal) < 0, "Total must decrease after removing an item");

        // Remove last item -> completion must be blocked
        DraftSaveResult emptyRes = ctx.orders().saveDraftEdits(
                draft.id(), OrderService.draftSnapshotToken(res1.updatedOrder(), res1.updatedItems()), List.of(), ctx.session());
        assertEquals(0, emptyRes.updatedItems().size());
        assertEquals(0, Money.ZERO.compareTo(emptyRes.updatedOrder().total()));

        PaymentInput pay = new PaymentInput(PaymentMethod.CASH, new BigDecimal("10.00"), new BigDecimal("10.00"), "", "");
        assertThrows(AppException.class,
                () -> ctx.payments().recordBalancePayment(draft.id(), List.of(pay), ctx.session()),
                "Payment completion must be blocked for an empty order");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void test_partialPaymentPreserved_and_staleUpdateRejected() {
        List<Product> products = ctx.menu().products(true, null);
        Product p1 = products.get(0);
        Product p2 = products.get(1);

        ctx.settings().save("allow.partial.payment", "true");
        Order draft = createDraftWithProducts(p1, 2, p2, 2);
        BigDecimal partialAmt = p1.effectivePrice();
        ctx.payments().recordBalancePayment(draft.id(),
                List.of(new PaymentInput(PaymentMethod.CASH, partialAmt, partialAmt, "", "Partial")),
                ctx.session());

        Order afterPartial = ctx.orders().find(draft.id());
        assertEquals(PaymentStatus.PARTIAL, afterPartial.paymentStatus());
        int paymentsCountBefore = ctx.payments().payments(draft.id()).size();

        // Stale snapshot must fail without modifying DB
        assertThrows(AppException.class, () ->
                ctx.orders().saveDraftEdits(draft.id(), "stale-snapshot-token", List.of(), ctx.session()));
        assertEquals(2, ctx.orders().items(draft.id()).size(), "Failed stale edit must not modify items");
        assertEquals(0, afterPartial.total().compareTo(ctx.orders().find(draft.id()).total()), "Failed stale edit must not modify total");

        // Valid edit removes p2 while preserving partial payment record
        List<OrderItem> curItems = ctx.orders().items(draft.id());
        DraftSaveResult res = ctx.orders().saveDraftEdits(
                draft.id(), OrderService.draftSnapshotToken(afterPartial, curItems),
                List.of(DraftItemEdit.fromOrderItem(curItems.get(0))), ctx.session());
        assertEquals(paymentsCountBefore, ctx.payments().payments(draft.id()).size(), "Existing payments must not be duplicated or deleted");
        assertEquals(0, partialAmt.compareTo(res.updatedOrder().paidTotal()), "Paid total must remain preserved");
    }
}
