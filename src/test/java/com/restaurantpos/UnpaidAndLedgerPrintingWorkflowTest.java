package com.restaurantpos;

import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.OrderStatus;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.SystemModels.PrintJob;
import com.restaurantpos.printing.ReceiptPrintingService.PrintResult;
import com.restaurantpos.printing.ReceiptPrintingService.UnpaidOrderPrintResult;
import com.restaurantpos.service.Cart;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UnpaidAndLedgerPrintingWorkflowTest {
    private static AppContext ctx;
    private static Path tempDir;
    private static long sharedOrderId;

    @BeforeAll
    static void setUp() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-unpaid-ledger-test-");
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

    private Order createUnpaidDraftOrder(String waiterName) {
        List<Product> products = ctx.menu().products(true, null);
        assertFalse(products.isEmpty(), "Seeded menu products should exist");
        Product product = products.get(0);

        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.waiterName = waiterName;
        cart.add(product, (Size) null, List.of(), 2, "Extra crispy");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        return ctx.orders().find(orderId);
    }

    private static boolean hasEmbeddedImage(Path pdfPath) throws Exception {
        try (PdfReader reader = new PdfReader(pdfPath.toString())) {
            PdfDictionary page = reader.getPageN(1);
            PdfDictionary resources = page.getAsDict(PdfName.RESOURCES);
            if (resources == null) return false;
            PdfDictionary xObj = resources.getAsDict(PdfName.XOBJECT);
            return xObj != null && !xObj.getKeys().isEmpty();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void testA_unpaidOrderPrintsSingleCustomerBillOnlyWithoutKitchen() throws Exception {
        Order order = createUnpaidDraftOrder(null);
        sharedOrderId = order.id();
        assertEquals(OrderStatus.DRAFT, order.status());
        assertEquals(PaymentStatus.UNPAID, order.paymentStatus());

        AtomicReference<PrintResult> customerRef = new AtomicReference<>();
        CountDownLatch customerLatch = new CountDownLatch(1);

        ctx.printer().printCustomerReceipt(
                order,
                false,
                ctx.session().userId(),
                custRes -> {
                    customerRef.set(custRes);
                    customerLatch.countDown();
                }
        );

        assertTrue(customerLatch.await(10, TimeUnit.SECONDS), "Customer receipt should complete promptly");

        PrintResult cust = customerRef.get();
        assertNotNull(cust);
        assertTrue(cust.success(), "Unpaid customer receipt should succeed: " + cust.message());

        assertTrue(cust.customerText().contains(order.orderNo()));
        assertTrue(cust.customerText().contains("UNPAID"));
        assertTrue(cust.customerText().contains("Payment has not yet been received."));
        assertTrue(cust.customerText().contains("Powered by ZS Sattar"));
        assertTrue(Files.exists(cust.customerPdfPath()));
        assertTrue(hasEmbeddedImage(cust.customerPdfPath()), "Unpaid customer PDF should embed official logo");

        List<PrintJob> jobs = ctx.systemRepo().printJobs(order.id());
        assertEquals(1, jobs.size(), "Expected exactly 1 CUSTOMER print job and 0 KITCHEN jobs");
        assertEquals("CUSTOMER", jobs.get(0).kind());
        assertEquals("SUCCESS", jobs.get(0).status());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void testB_duplicatePrintProtectionPreventsSecondSequence() throws Exception {
        Order order = ctx.orders().find(sharedOrderId);
        assertNotNull(order);

        AtomicReference<PrintResult> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ctx.printer().printCustomerReceipt(
                order,
                false,
                ctx.session().userId(),
                res -> {
                    ref.set(res);
                    latch.countDown();
                }
        );

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNotNull(ref.get());

        List<PrintJob> jobs = ctx.systemRepo().printJobs(order.id());
        assertEquals(1, jobs.size(), "Duplicate protection must prevent creating extra print jobs");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void testC_unavailableCustomerPrinterPreservesOrder() throws Exception {
        Order order = createUnpaidDraftOrder(null);
        ctx.systemRepo().savePrinterConfig("Cashier", "CASHIER", "Offline-Thermal-80mm");
        try {
            AtomicReference<PrintResult> ref = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ctx.printer().printCustomerReceipt(
                    order,
                    false,
                    ctx.session().userId(),
                    res -> {
                        ref.set(res);
                        latch.countDown();
                    }
            );

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            PrintResult res = ref.get();
            assertNotNull(res);
            assertFalse(res.success(), "Unavailable explicit cashier printer must fail");
            assertTrue(res.message().contains("Offline-Thermal-80mm"));

            Order persisted = ctx.orders().find(order.id());
            assertNotNull(persisted, "Order must remain safely saved in database");
            assertEquals(PaymentStatus.UNPAID, persisted.paymentStatus());

            List<PrintJob> jobs = ctx.systemRepo().printJobs(order.id());
            assertEquals(1, jobs.size(), "Only the failed CUSTOMER job should be recorded");
            assertEquals("CUSTOMER", jobs.get(0).kind());
            assertEquals("FAILED", jobs.get(0).status());
        } finally {
            ctx.systemRepo().savePrinterConfig("Cashier", "CASHIER", "");
        }
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void testD_printPairDelegatesToSingleCustomerReceiptOnly() throws Exception {
        Order order = createUnpaidDraftOrder(null);
        AtomicReference<PrintResult> seqRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ctx.printer().printPair(order, false, ctx.session().userId(), res -> {
            seqRef.set(res);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNotNull(seqRef.get());
        assertTrue(seqRef.get().success());
        assertTrue(ctx.systemRepo().printJobs(order.id()).stream().noneMatch(j -> "KITCHEN".equals(j.kind())));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void testEF_ledgerReceiptCreditAndPaymentAndReprintPreserveBalances() throws Exception {
        long customerId = ctx.customerLedger().createCustomer("Test Ledger Customer", "0300-1122334", "6th Road Rawalpindi", "", "", ctx.session());
        Order order = createUnpaidDraftOrder("Hamza");
        BigDecimal orderTotal = order.total();
        assertTrue(orderTotal.signum() > 0);

        ctx.customerLedger().addOrderToLedger(customerId, order.id(), orderTotal, "Credit sale test", ctx.session());
        BigDecimal paymentAmt = new BigDecimal("100.00");
        if (orderTotal.compareTo(paymentAmt) <= 0) {
            paymentAmt = orderTotal;
        }
        ctx.customerLedger().collectLedgerPayment(customerId, paymentAmt, PaymentMethod.CASH, "", "Partial cash", ctx.session());

        Customer customer = ctx.customerLedger().findCustomer(customerId);
        List<CustomerLedgerEntry> entries = ctx.customerLedger().entries(customerId);
        assertEquals(2, entries.size());
        CustomerLedgerEntry creditEntry = entries.stream().filter(e -> "CREDIT_SALE".equals(e.entryType())).findFirst().orElseThrow();
        CustomerLedgerEntry paymentEntry = entries.stream().filter(e -> "PAYMENT".equals(e.entryType())).findFirst().orElseThrow();

        BigDecimal balanceBefore = ctx.customerLedger().balance(customerId);
        int entriesCountBefore = entries.size();
        long ordersCountBefore = Database.get().scalarLong("SELECT COUNT(*) FROM orders");

        // Print CREDIT_SALE entry
        AtomicReference<PrintResult> creditPrintRef = new AtomicReference<>();
        CountDownLatch creditLatch = new CountDownLatch(1);
        ctx.printer().printLedgerReceipt(customer, creditEntry, ctx.session().userId(), "Save 80mm PDF Only", false, r -> {
            creditPrintRef.set(r);
            creditLatch.countDown();
        });
        assertTrue(creditLatch.await(10, TimeUnit.SECONDS));
        PrintResult creditRes = creditPrintRef.get();
        assertNotNull(creditRes);
        assertTrue(creditRes.success(), creditRes.message());
        assertTrue(creditRes.customerText().contains("CUSTOMER LEDGER RECEIPT"));
        assertTrue(creditRes.customerText().contains("Credit Added (+)"));
        assertTrue(creditRes.customerText().contains("CHARGED TO LEDGER"));
        assertTrue(creditRes.customerText().contains(order.orderNo()));
        assertTrue(creditRes.customerText().contains("Previous Balance"));
        assertTrue(creditRes.customerText().contains("Remaining Balance"));
        assertTrue(Files.exists(creditRes.customerPdfPath()));
        assertTrue(hasEmbeddedImage(creditRes.customerPdfPath()), "Ledger PDF must embed circular logo");

        // Print PAYMENT entry
        AtomicReference<PrintResult> payPrintRef = new AtomicReference<>();
        CountDownLatch payLatch = new CountDownLatch(1);
        ctx.printer().printLedgerReceipt(customer, paymentEntry, ctx.session().userId(), "Save 80mm PDF Only", false, r -> {
            payPrintRef.set(r);
            payLatch.countDown();
        });
        assertTrue(payLatch.await(10, TimeUnit.SECONDS));
        PrintResult payRes = payPrintRef.get();
        assertNotNull(payRes);
        assertTrue(payRes.success(), payRes.message());
        assertTrue(payRes.customerText().contains("Payment Received (-)"));
        assertTrue(payRes.customerText().contains("PAYMENT CONFIRMED"));
        assertTrue(payRes.customerText().contains(Money.plain(orderTotal)));
        assertTrue(payRes.customerText().contains(Money.plain(balanceBefore)));
        assertFalse(payRes.customerText().contains("*** REPRINT / DUPLICATE RECEIPT ***"));

        // Rapid duplicate click within 2s debounce window must be blocked cleanly
        AtomicReference<PrintResult> dupRef = new AtomicReference<>();
        CountDownLatch dupLatch = new CountDownLatch(1);
        ctx.printer().printLedgerReceipt(customer, paymentEntry, ctx.session().userId(), "Save 80mm PDF Only", false, r -> {
            dupRef.set(r);
            dupLatch.countDown();
        });
        assertTrue(dupLatch.await(10, TimeUnit.SECONDS));
        assertNotNull(dupRef.get());
        assertFalse(dupRef.get().success());
        assertTrue(dupRef.get().message().contains("Please wait"));

        Thread.sleep(4100L);

        // Reprint PAYMENT entry
        AtomicReference<PrintResult> reprintRef = new AtomicReference<>();
        CountDownLatch reprintLatch = new CountDownLatch(1);
        ctx.printer().printLedgerReceipt(customer, paymentEntry, ctx.session().userId(), "Save 80mm PDF Only", true, r -> {
            reprintRef.set(r);
            reprintLatch.countDown();
        });
        assertTrue(reprintLatch.await(10, TimeUnit.SECONDS));
        PrintResult reprintRes = reprintRef.get();
        assertNotNull(reprintRes);
        assertTrue(reprintRes.success(), reprintRes.message());
        assertTrue(reprintRes.customerText().contains("*** REPRINT / DUPLICATE RECEIPT ***"));
        assertTrue(reprintRes.customerText().contains("CLR-" + paymentEntry.id()));
        assertTrue(Files.exists(reprintRes.customerPdfPath()));
        assertTrue(hasEmbeddedImage(reprintRes.customerPdfPath()));

        Thread.sleep(4100L);

        // Unavailable explicit printer must fail cleanly for ledger receipt too
        AtomicReference<PrintResult> failRef = new AtomicReference<>();
        CountDownLatch failLatch = new CountDownLatch(1);
        ctx.printer().printLedgerReceipt(customer, paymentEntry, ctx.session().userId(), "Offline-Ledger-80mm", false, r -> {
            failRef.set(r);
            failLatch.countDown();
        });
        assertTrue(failLatch.await(10, TimeUnit.SECONDS));
        assertNotNull(failRef.get());
        assertFalse(failRef.get().success());
        assertTrue(failRef.get().message().contains("Offline-Ledger-80mm"));

        // Verify zero mutation of balances, ledger entries, or orders
        assertEquals(0, balanceBefore.compareTo(ctx.customerLedger().balance(customerId)), "Balance must not change after print/reprint");
        assertEquals(entriesCountBefore, ctx.customerLedger().entries(customerId).size(), "Ledger entry count must not change");
        assertEquals(ordersCountBefore, Database.get().scalarLong("SELECT COUNT(*) FROM orders"), "Orders count must not change");
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void testG_confirmOrderCustomerBillAndCompleteOrderPaidReceiptWorkflow() throws Exception {
        Order draft = createUnpaidDraftOrder(null);
        assertEquals(OrderStatus.DRAFT, draft.status());
        assertEquals(PaymentStatus.UNPAID, draft.paymentStatus());

        // Confirm Order prints ONLY the Unpaid Customer Bill (zero kitchen print jobs)
        AtomicReference<PrintResult> cRef = new AtomicReference<>();
        CountDownLatch cLatch = new CountDownLatch(1);
        ctx.printer().printCustomerReceipt(draft, false, ctx.session().userId(), r -> {
            cRef.set(r);
            cLatch.countDown();
        });
        assertTrue(cLatch.await(10, TimeUnit.SECONDS));
        assertNotNull(cRef.get());
        assertTrue(cRef.get().success());
        assertTrue(cRef.get().customerText().contains("UNPAID"));
        assertTrue(cRef.get().customerText().contains("Powered by ZS Sattar"));
        assertTrue(ctx.printer().hasCustomerPrintJob(draft.id()));
        assertTrue(ctx.systemRepo().printJobs(draft.id()).stream().noneMatch(j -> "KITCHEN".equals(j.kind())));

        // Verify Draft Orders persistence, batch summaries, and summary stats
        assertFalse(ctx.orders().draftOrders(draft.orderNo()).isEmpty());
        var itemMap = ctx.orders().itemSummaries(List.of(draft.id()));
        assertTrue(itemMap.containsKey(draft.id()));
        var statsBeforePay = ctx.orders().summaryStats(null, null);
        assertTrue(statsBeforePay.draftOrders() >= 1);

        // Complete Order & Pay -> status becomes COMPLETED, paymentStatus becomes PAID
        var payInput = new com.restaurantpos.service.PaymentService.PaymentInput(
                PaymentMethod.CASH, draft.total(), draft.total(), "", "Counter settlement");
        ctx.payments().recordBalancePayment(draft.id(), List.of(payInput), ctx.session());

        Order completed = ctx.orders().find(draft.id());
        assertNotNull(completed);
        assertEquals(OrderStatus.COMPLETED, completed.status());
        assertEquals(PaymentStatus.PAID, completed.paymentStatus());
        assertNotNull(completed.completedAt());
        assertTrue(ctx.orders().draftOrders(draft.orderNo()).isEmpty());
        assertFalse(ctx.orders().completeOrders(null, null, draft.orderNo()).isEmpty());
        assertEquals("Cash", ctx.orders().paymentMethods(List.of(completed.id())).get(completed.id()));

        int paymentsBefore = ctx.payments().payments(completed.id()).size();
        long ordersBefore = Database.get().scalarLong("SELECT COUNT(*) FROM orders");

        // Print Paid Customer Receipt (customer receipt only)
        AtomicReference<PrintResult> paidRef = new AtomicReference<>();
        CountDownLatch paidLatch = new CountDownLatch(1);
        ctx.printer().printCustomerReceipt(completed, false, ctx.session().userId(), r -> {
            paidRef.set(r);
            paidLatch.countDown();
        });
        assertTrue(paidLatch.await(10, TimeUnit.SECONDS));
        PrintResult paidRes = paidRef.get();
        assertNotNull(paidRes);
        assertTrue(paidRes.success(), paidRes.message());
        assertTrue(paidRes.customerText().contains("PAID CUSTOMER RECEIPT"));
        assertTrue(paidRes.customerText().contains("*** PAID ***"));
        assertTrue(paidRes.customerText().contains("Completed"));
        assertTrue(Files.exists(paidRes.customerPdfPath()));
        assertTrue(hasEmbeddedImage(paidRes.customerPdfPath()));

        // Reprint Paid Customer Receipt without creating duplicate payments or orders
        AtomicReference<PrintResult> repRef = new AtomicReference<>();
        CountDownLatch repLatch = new CountDownLatch(1);
        ctx.printer().printCustomerReceipt(completed, true, ctx.session().userId(), r -> {
            repRef.set(r);
            repLatch.countDown();
        });
        assertTrue(repLatch.await(10, TimeUnit.SECONDS));
        PrintResult repRes = repRef.get();
        assertNotNull(repRes);
        assertTrue(repRes.success(), repRes.message());
        assertTrue(repRes.customerText().contains("*** REPRINT / DUPLICATE RECEIPT ***"));
        assertTrue(repRes.customerText().contains("PAID CUSTOMER RECEIPT"));

        assertEquals(paymentsBefore, ctx.payments().payments(completed.id()).size(), "Printing/reprinting must not duplicate payments");
        assertEquals(ordersBefore, Database.get().scalarLong("SELECT COUNT(*) FROM orders"), "Printing/reprinting must not duplicate orders");
    }
}
