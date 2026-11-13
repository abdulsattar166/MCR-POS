package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.*;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Sales.*;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.printing.ReceiptBuilder;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ReceiptPrintAndShiftLifecycleTest {

    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-shift-print-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
    }

    @AfterAll
    static void teardown() {
        if (ctx != null && ctx.session().active()) {
            ctx.auth().logout();
        }
    }

    @Test
    void testAutomaticShiftStartOnCashierLogin() {
        User cashier = ctx.users().byRole(Role.CASHIER).get(0);
        assertNotNull(cashier);
        assertEquals(Role.CASHIER, cashier.role());

        // Cashier signs in -> auto-starts shift
        ctx.session().start(cashier);
        Shift started = ctx.shifts().ensureActiveShiftForCashier(cashier, ctx.session());

        assertNotNull(started, "Shift must be automatically started for cashier");
        assertEquals(cashier.id(), started.userId());
        assertTrue(started.isOpen(), "Started shift must be OPEN");
        assertEquals(BigDecimal.ZERO, started.openingCash());
        assertNotNull(started.openedAt());

        // Idempotency: repeated login check must reuse the active shift, never creating a duplicate
        Shift secondCheck = ctx.shifts().ensureActiveShiftForCashier(cashier, ctx.session());
        assertEquals(started.id(), secondCheck.id(), "Repeated login must reuse existing shift without duplicates");

        // Verify exactly 1 active shift exists in database for this cashier
        List<Shift> openShifts = ctx.financeRepo().shifts(null, null, cashier.id()).stream()
                .filter(Shift::isOpen).toList();
        assertEquals(1, openShifts.size(), "Cashier must have exactly 1 active shift");

        // Clean up
        ctx.shifts().autoCloseCashierShift(cashier, ctx.session(), "Cleanup");
    }

    @Test
    void testAdminAndOwnerDoNotAutoStartCashierShift() {
        User owner = ctx.users().byRole(Role.OWNER).get(0);
        assertNotNull(owner);
        ctx.session().start(owner);

        Shift shift = ctx.shifts().ensureActiveShiftForCashier(owner, ctx.session());
        assertNull(shift, "Admin/Owner accounts must not automatically create drawer shifts");
    }

    @Test
    void testAutomaticShiftClosureOnLogout() {
        User cashier = ctx.users().byRole(Role.CASHIER).get(0);
        ctx.session().start(cashier);
        Shift shift = ctx.shifts().ensureActiveShiftForCashier(cashier, ctx.session());
        assertNotNull(shift);
        assertTrue(shift.isOpen());

        // Auto close on logout
        ctx.shifts().autoCloseCashierShift(cashier, ctx.session(), "Auto-closed on cashier logout");

        Shift closed = ctx.financeRepo().find(shift.id());
        assertNotNull(closed);
        assertEquals(ShiftStatus.CLOSED, closed.status(), "Shift must be marked CLOSED");
        assertNotNull(closed.closedAt(), "Closed timestamp must be recorded");
        assertNotNull(closed.formattedDuration(), "Duration must be formatted cleanly");
        assertTrue(closed.duration().toMillis() >= 0);

        // Verify that current(cashier.id()) is now null
        assertNull(ctx.shifts().current(cashier.id()), "Cashier must have no active shift after closure");
    }

    @Test
    void testPaymentsAssociateWithActiveShiftAndShiftTotalsReconcile() {
        User cashier = ctx.users().byRole(Role.CASHIER).get(0);
        ctx.session().start(cashier);
        Shift shift = ctx.shifts().ensureActiveShiftForCashier(cashier, ctx.session());
        assertNotNull(shift);

        Product dish = ctx.menu().products(true, null).stream().findFirst().orElseThrow();

        // Place an order
        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.add(dish, (Size) null, List.of(), 1, "Quick");

        Cart.Totals quote = ctx.orders().quote(cart);

        // Checkout with cash payment
        PaymentService.PaymentInput pay = new PaymentService.PaymentInput(PaymentMethod.CASH,
                quote.total, quote.total.add(new BigDecimal("100.00")), "", "Paid cash");
        PaymentService.CheckoutResult result = ctx.payments().checkout(cart, List.of(pay), ctx.session());

        assertNotNull(result);
        assertEquals(quote.total, result.total());

        // Verify payment is linked to cashier's active shift
        List<Payment> orderPayments = ctx.orderRepo().payments(result.orderId());
        assertFalse(orderPayments.isEmpty());
        Payment p = orderPayments.get(0);
        assertEquals(shift.id(), p.shiftId(), "Payment must be associated with the active shift");

        // Verify shift cash sales
        BigDecimal cashSales = ctx.financeRepo().cashSales(shift.id());
        assertEquals(0, quote.total.compareTo(cashSales));

        BigDecimal expected = ctx.financeRepo().expectedCash(shift);
        assertEquals(0, quote.total.compareTo(expected));

        // Auto close shift
        ctx.shifts().autoCloseCashierShift(cashier, ctx.session(), "Reconciled logout");
        Shift closed = ctx.financeRepo().find(shift.id());
        assertEquals(0, quote.total.compareTo(closed.expectedCash()));
        assertEquals(0, quote.total.compareTo(closed.countedCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(closed.difference()));
    }

    @Test
    void testReceiptPrintAndReprintFunctionality() throws Exception {
        User cashier = ctx.users().byRole(Role.CASHIER).get(0);
        ctx.session().start(cashier);
        ctx.shifts().ensureActiveShiftForCashier(cashier, ctx.session());

        Product dish = ctx.menu().products(true, null).stream()
                .filter(p -> p.name().contains("Paratha") || p.name().contains("Lobya") || p.name().contains("Chai"))
                .findFirst().orElseThrow();

        // Create an order
        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.add(dish, (Size) null, List.of(), 1, "Customer special");
        Cart.Totals quote = ctx.orders().quote(cart);

        PaymentService.PaymentInput pay = new PaymentService.PaymentInput(PaymentMethod.CASH,
                quote.total, quote.total.add(new BigDecimal("50.00")), "", "");
        PaymentService.CheckoutResult checkout = ctx.payments().checkout(cart, List.of(pay), ctx.session());

        Order order = ctx.orders().find(checkout.orderId());
        assertNotNull(order);
        List<OrderItem> items = ctx.orders().items(order.id());
        List<Payment> pList = ctx.payments().payments(order.id());

        // 1. Original Receipt Text
        ReceiptBuilder builder = new ReceiptBuilder(ctx.settings());
        String originalReceipt = builder.customerReceipt(order, items, pList, "R-" + order.orderNo() + "-001", false);
        assertNotNull(originalReceipt);
        assertTrue(originalReceipt.contains(order.orderNo()));
        assertTrue(originalReceipt.contains(dish.name()));
        assertFalse(originalReceipt.contains("*** REPRINT / DUPLICATE RECEIPT ***"));

        // 2. Reprint Receipt Text
        String reprintReceipt = builder.customerReceipt(order, items, pList, "REP-" + order.orderNo() + "-001", true);
        assertNotNull(reprintReceipt);
        assertTrue(reprintReceipt.contains(order.orderNo()), "Reprint must preserve original order number");
        assertTrue(reprintReceipt.contains("*** REPRINT / DUPLICATE RECEIPT ***"), "Reprint must contain reprint indicator");

        // 3. 80mm PDF Generation for Original and Reprint
        Path receiptsDir = AppPaths.receipts();
        Path origPdf = receiptsDir.resolve("test_receipt_orig.pdf");
        Path repPdf = receiptsDir.resolve("test_receipt_rep.pdf");

        ReceiptPdfGenerator.generateCustomerPdf(order, items, pList, "R-" + order.orderNo(), ctx.settings(), origPdf, false);
        ReceiptPdfGenerator.generateCustomerPdf(order, items, pList, "REP-" + order.orderNo(), ctx.settings(), repPdf, true);

        assertTrue(Files.exists(origPdf), "Original 80mm PDF must exist");
        assertTrue(Files.exists(repPdf), "Reprint 80mm PDF must exist");
        assertTrue(Files.size(origPdf) > 1000);
        assertTrue(Files.size(repPdf) > 1000);

        // 4. Centralized Print Execution via Printing Service with Callback
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean printSuccess = new AtomicBoolean(false);

        ctx.printer().printPair(order, true, cashier.id(), "__PDF_ONLY__", result -> {
            printSuccess.set(result.success());
            assertNotNull(result.customerPdfPath());
            assertTrue(Files.exists(result.customerPdfPath()));
            latch.countDown();
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Print sequence must complete within timeout");
        assertTrue(printSuccess.get(), "Print pair execution must succeed");

        // Confirm that reprint did not alter order status or create duplicate orders
        Order verified = ctx.orders().find(order.id());
        assertEquals(order.total(), verified.total(), "Order total must remain unaltered");
        assertEquals(PaymentStatus.PAID, verified.paymentStatus(), "Payment status must remain PAID");
        assertEquals(1, ctx.payments().payments(order.id()).size(), "Reprint must not create duplicate payments");

        // Clean up
        ctx.shifts().autoCloseCashierShift(cashier, ctx.session(), "Cleanup");
    }
}
