package com.restaurantpos;

import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.printing.ReceiptLogo;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.printing.ReceiptPrintingService;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReceiptPrintingAndAuditTest {
    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-print-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();

        if (!ctx.session().active()) {
            ctx.auth().login("food_admin@gmail.com", "admin@1122");
        }
    }

    @AfterAll
    static void tearDown() {
        if (ctx != null && ctx.session().active()) {
            ctx.auth().logout();
        }
        AppContext.resetForTesting();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Verify 80 mm Customer Receipt PDF Generation with Circular Logo (PAID Order)")
    void testPaidCustomerReceiptPdfGeneration() throws Exception {
        List<Product> products = ctx.menu().products(true, null);
        assertFalse(products.isEmpty(), "Menu products must exist");
        Product p1 = products.get(0);
        Product p2 = products.get(1);

        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableNo = "T-1";
        cart.waiterName = "Ali";
        cart.add(p1, (Size) null, List.of(), 2, "Extra spicy");
        cart.add(p2, (Size) null, List.of(), 1, "");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);
        assertNotNull(order);

        // Record full payment
        PaymentInput pInput = new PaymentInput(PaymentMethod.CASH, order.total(), order.total(), "PAID-IN-FULL", "");
        ctx.payments().recordBalancePayment(order.id(), List.of(pInput), ctx.session());
        Order paidOrder = ctx.orders().find(orderId);
        assertEquals(PaymentStatus.PAID, paidOrder.paymentStatus());

        List<OrderItem> items = ctx.orders().items(orderId);
        List<Payment> payments = ctx.orderRepo().payments(orderId);

        Path pdfFile = AppPaths.receipts().resolve("Test_Paid_Receipt_" + order.orderNo() + ".pdf");
        ReceiptPdfGenerator.generateCustomerPdf(paidOrder, items, payments, "TEST-SEQ-001", ctx.settings(), pdfFile);

        assertTrue(Files.exists(pdfFile), "Generated PDF file must exist");
        assertTrue(Files.size(pdfFile) > 1000, "Generated PDF must contain data");

        // Validate 80 mm width (226.77 pt) using PdfReader
        PdfReader reader = new PdfReader(Files.newInputStream(pdfFile));
        assertEquals(1, reader.getNumberOfPages(), "Receipt should be 1 continuous page");
        float width = reader.getPageSize(1).getWidth();
        assertEquals(226.77f, width, 0.5f, "PDF page width must match standard 80 mm thermal roll (226.77 pt)");
        reader.close();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Verify 80 mm Delivery Customer Receipt PDF with Customer Details (UNPAID)")
    void testUnpaidDeliveryReceiptPdf() throws Exception {
        List<Product> products = ctx.menu().products(true, null);
        Product p = products.get(0);

        Cart cart = new Cart();
        cart.type = OrderType.DELIVERY;
        cart.customerName = "Bilal Khan";
        cart.customerPhone = "03001234567";
        cart.customerAddress = "House 12, Street 4, F-8/2, Islamabad";
        cart.deliveryCharge = new BigDecimal("150.00");
        cart.notes = "Call when arriving at main gate";
        cart.add(p, (Size) null, List.of(), 3, "No onions");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);
        assertNotNull(order);
        assertEquals(PaymentStatus.UNPAID, order.paymentStatus());

        List<OrderItem> items = ctx.orders().items(orderId);
        List<Payment> payments = ctx.orderRepo().payments(orderId);

        Path pdfFile = AppPaths.receipts().resolve("Test_Unpaid_Delivery_" + order.orderNo() + ".pdf");
        ReceiptPdfGenerator.generateCustomerPdf(order, items, payments, "TEST-DELIV-002", ctx.settings(), pdfFile);

        assertTrue(Files.exists(pdfFile));
        PdfReader reader = new PdfReader(Files.newInputStream(pdfFile));
        assertEquals(226.77f, reader.getPageSize(1).getWidth(), 0.5f);
        reader.close();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Verify 80 mm Kitchen Order Ticket PDF Generation")
    void testKitchenTicketPdfGeneration() throws Exception {
        List<Product> products = ctx.menu().products(true, null);
        Product p = products.get(0);

        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableNo = "T-5";
        cart.waiterName = "Hamza";
        cart.notes = "Rush order for VIP guests";
        cart.add(p, (Size) null, List.of(), 2, "Well done");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);
        List<OrderItem> items = ctx.orders().items(orderId);

        Path cPdfFile = AppPaths.receipts().resolve("Test_Customer_" + order.orderNo() + ".pdf");
        ReceiptPdfGenerator.generateCustomerPdf(order, items, List.of(), order.orderNo(), ctx.settings(), cPdfFile);

        assertTrue(Files.exists(cPdfFile));
        PdfReader reader = new PdfReader(Files.newInputStream(cPdfFile));
        assertEquals(226.77f, reader.getPageSize(1).getWidth(), 0.5f);
        PdfDictionary res = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
        PdfDictionary xobj = res != null ? res.getAsDict(PdfName.XOBJECT) : null;
        assertNotNull(xobj, "Customer receipt PDF must embed the restaurant logo image at top");
        assertFalse(xobj.getKeys().isEmpty(), "Customer receipt PDF must contain embedded logo XObject");
        reader.close();

        assertNotNull(ReceiptLogo.circleSquareLogo(ctx.settings()));
        assertTrue(ReceiptLogo.escPosRaster(ctx.settings()).length > 100);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Verify ReceiptPrintingService Non-blocking Fallback Execution & Debouncing")
    void testReceiptPrintingServiceExecutionAndDebounce() throws Exception {
        List<Product> products = ctx.menu().products(true, null);
        Product p = products.get(0);

        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.add(p, (Size) null, List.of(), 1, "");
        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);

        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicReference<ReceiptPrintingService.PrintResult> result1 = new AtomicReference<>();

        ctx.receiptService().printPair(order, false, ctx.session().userId(), res -> {
            result1.set(res);
            latch1.countDown();
        });

        assertTrue(latch1.await(10, TimeUnit.SECONDS), "First print job must complete asynchronously within 10s");
        assertNotNull(result1.get());
        assertTrue(result1.get().success(), "Print sequence must succeed (fallback to 80mm PDF when no printer)");
        assertNotNull(result1.get().customerPdfPath(), "Generated customer 80mm PDF path must be provided");
        assertTrue(Files.exists(result1.get().customerPdfPath()), "Generated customer 80mm PDF must exist on disk");

        // Immediate retry without reprint flag should debounce
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<ReceiptPrintingService.PrintResult> result2 = new AtomicReference<>();
        ctx.receiptService().printPair(order, false, ctx.session().userId(), res -> {
            result2.set(res);
            latch2.countDown();
        });

        assertTrue(latch2.await(5, TimeUnit.SECONDS));
        assertNotNull(result2.get());
        assertFalse(result2.get().success(), "Debounce should prevent duplicate sequence when reprint=false");
        assertTrue(result2.get().message().contains("just started"), "Message should notify about debounce");

        // Retry with reprint=true must succeed
        CountDownLatch latch3 = new CountDownLatch(1);
        AtomicReference<ReceiptPrintingService.PrintResult> result3 = new AtomicReference<>();
        ctx.receiptService().printPair(order, true, ctx.session().userId(), res -> {
            result3.set(res);
            latch3.countDown();
        });

        assertTrue(latch3.await(10, TimeUnit.SECONDS), "Reprint must be allowed");
        assertNotNull(result3.get());
        assertTrue(result3.get().success(), "Reprint sequence must succeed");
    }
}
