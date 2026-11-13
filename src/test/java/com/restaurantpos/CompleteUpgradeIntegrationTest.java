package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.SchemaManager;
import com.restaurantpos.enums.*;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.*;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Inventory.CustomerLedgerSummary;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.SystemModels.PrintJob;
import com.restaurantpos.model.SystemModels.User;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.restaurantpos.printing.ReceiptLogo;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.printing.ReceiptPrintingService;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CompleteUpgradeIntegrationTest {
    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-upgrade-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Part 2 & 3 — Verify Sadha Paratha (70), Cake (50), Pakura (250g=200, 1kg=800) & Pakura AI image")
    void verifyRequiredMenuItemsAndPakuraImage() throws Exception {
        Product sadha = ctx.menu().products(true, null).stream()
                .filter(p -> "PAR-015".equals(p.sku())).findFirst().orElseThrow();
        assertEquals("Sadha Paratha", sadha.name());
        assertEquals(0, Money.of("70").compareTo(sadha.price()));

        Product cake = ctx.menu().products(true, null).stream()
                .filter(p -> "CAK-001".equals(p.sku())).findFirst().orElseThrow();
        assertEquals("Cake", cake.name());
        assertEquals(0, Money.of("50").compareTo(cake.price()));

        Product pakura = ctx.menu().products(true, null).stream()
                .filter(p -> "PAK-001".equals(p.sku())).findFirst().orElseThrow();
        assertEquals("Pakura", pakura.name());
        assertEquals("food/pakura.jpg", pakura.imagePath());

        Path imgPath = Images.resolve(pakura.imagePath());
        assertNotNull(imgPath);
        assertTrue(Files.isRegularFile(imgPath));
        BufferedImage bImg = ImageIO.read(imgPath.toFile());
        assertNotNull(bImg);
        assertEquals(640, bImg.getWidth());
        assertEquals(420, bImg.getHeight());

        List<Size> portions = ctx.menu().sizes(pakura.id());
        assertEquals(2, portions.size());
        Size small = portions.stream().filter(s -> "250 g".equals(s.name())).findFirst().orElseThrow();
        Size large = portions.stream().filter(s -> "1 kg".equals(s.name())).findFirst().orElseThrow();
        assertEquals(0, Money.of("200").compareTo(small.effectivePrice(pakura.effectivePrice())));
        assertEquals(0, Money.of("800").compareTo(large.effectivePrice(pakura.effectivePrice())));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Part 8 — Verify Restaurant Location is updated everywhere")
    void verifyRestaurantLocation() {
        String expected = "Rawalpindi 6th Road, Satellite Town, D-Block";
        assertEquals(expected, ctx.settings().restaurantAddress());
        assertTrue(ctx.settings().receiptHeader().contains(expected));
        assertFalse(ctx.settings().receiptHeader().contains("Gulberg"));
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Part 9 — Verify exactly 8 physical tables with exact seating capacities and occupation rules")
    void verifyEightTablesLayoutAndRules() {
        List<DiningTable> activeTables = ctx.tables().tables(null, true);
        assertEquals(8, activeTables.size());
        int[] expectedCaps = {4, 2, 4, 2, 4, 2, 4, 2};
        for (int i = 0; i < 8; i++) {
            assertEquals("Table " + (i + 1), activeTables.get(i).tableNo());
            assertEquals(expectedCaps[i], activeTables.get(i).capacity());
            assertEquals(TableStatus.AVAILABLE, activeTables.get(i).status());
        }

        // Occupy Table 1 and verify duplicate active dine-in order is blocked
        DiningTable t1 = activeTables.get(0);
        Product sadha = ctx.menu().products(true, null).stream().filter(p -> "PAR-015".equals(p.sku())).findFirst().orElseThrow();
        Cart c1 = new Cart();
        c1.type = OrderType.DINE_IN;
        c1.tableId = t1.id();
        c1.tableNo = t1.tableNo();
        c1.add(sadha, null, List.of(), 1, "");
        long o1 = ctx.orders().createOrder(c1, false, ctx.session());

        assertEquals(TableStatus.OCCUPIED, ctx.tables().table(t1.id()).status());

        Cart c2 = new Cart();
        c2.type = OrderType.DINE_IN;
        c2.tableId = t1.id();
        c2.tableNo = t1.tableNo();
        c2.add(sadha, null, List.of(), 1, "");
        assertThrows(AppException.class, () -> ctx.orders().createOrder(c2, false, ctx.session()));

        // Pay and complete o1 -> Table 1 becomes AVAILABLE
        Order order1 = ctx.orders().find(o1);
        ctx.payments().recordBalancePayment(o1,
                List.of(new PaymentService.PaymentInput(PaymentMethod.CASH, order1.total(), order1.total(), "", "")),
                ctx.session());
        ctx.orders().complete(o1, ctx.session());
        assertEquals(TableStatus.AVAILABLE, ctx.tables().table(t1.id()).status());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Part 7 — Verify Waiter & Kitchen roles are removed and disabled cleanly")
    void verifyWaiterAndKitchenRolesRemoved() {
        assertTrue(ctx.users().all().stream().noneMatch(u -> "WAITER".equalsIgnoreCase(u.role().name()) || "KITCHEN".equalsIgnoreCase(u.role().name())));
        assertEquals("Rawalpindi 6th Road, Satellite Town, D-Block", ctx.settings().restaurantAddress());
        assertEquals("Powered by ZS Sattar", ctx.settings().receiptFooter());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Part 4 & 5 — Verify Unpaid Customer Receipt print, Paid receipt & Reprint (No Kitchen Ticket)")
    void verifyCustomerBillPrintAndPaidReceiptReprint() throws Exception {
        Product pakura = ctx.menu().products(true, null).stream().filter(p -> "PAK-001".equals(p.sku())).findFirst().orElseThrow();
        Size oneKg = ctx.menu().sizes(pakura.id()).stream().filter(s -> "1 kg".equals(s.name())).findFirst().orElseThrow();

        Cart c = new Cart();
        c.type = OrderType.TAKEAWAY;
        c.add(pakura, oneKg, List.of(), 1, "Extra chutney");
        long oid = ctx.orders().createOrder(c, false, ctx.session());

        Order unpaid = ctx.orders().find(oid);
        assertEquals(PaymentStatus.UNPAID, unpaid.paymentStatus());
        assertEquals(0, Money.of("800").compareTo(unpaid.total()));

        // Print Unpaid Customer Bill (never kitchen ticket)
        CountDownLatch cLatch = new CountDownLatch(1);
        AtomicReference<ReceiptPrintingService.PrintResult> cResult = new AtomicReference<>();
        ctx.printer().printCustomerReceipt(unpaid, false, ctx.session().userId(), res -> {
            cResult.set(res);
            cLatch.countDown();
        });
        assertTrue(cLatch.await(10, TimeUnit.SECONDS));
        assertTrue(cResult.get().success());
        assertTrue(cResult.get().customerText().contains("Pakura"));
        assertTrue(cResult.get().customerText().contains("1 kg"));
        assertTrue(cResult.get().customerText().contains("Rawalpindi 6th Road, Satellite Town, D-Block"));
        assertTrue(cResult.get().customerText().contains("Powered by ZS Sattar"));
        assertTrue(cResult.get().customerText().contains("UNPAID"));
        assertTrue(Files.exists(cResult.get().customerPdfPath()));
        assertTrue(ctx.systemRepo().printJobs(oid).stream().noneMatch(j -> "KITCHEN".equalsIgnoreCase(j.kind())));

        // Settle payment -> generate paid receipt + reprint
        ctx.payments().recordBalancePayment(oid,
                List.of(new PaymentService.PaymentInput(PaymentMethod.CASH, Money.of("800"), Money.of("1000"), "", "")),
                ctx.session());
        Order paid = ctx.orders().find(oid);
        assertEquals(PaymentStatus.PAID, paid.paymentStatus());
        assertEquals(1, ctx.payments().payments(oid).size());

        String reprintText = ctx.printer().previewCustomer(paid, true);
        assertTrue(reprintText.contains("*** REPRINT / DUPLICATE RECEIPT ***"));
        assertTrue(reprintText.contains("Rawalpindi 6th Road, Satellite Town, D-Block"));
        assertTrue(reprintText.contains("Powered by ZS Sattar"));
        assertTrue(reprintText.contains("Pakura"));
        assertTrue(reprintText.contains("1 kg"));
        assertEquals(1, ctx.payments().payments(oid).size(), "Reprint must never create duplicate payments");
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Part 6 — Verify Customer Ledger credit sales, partial/full collection & cash separation")
    void verifyCustomerLedgerIntegrity() throws Exception {
        long custId = ctx.customerLedger().createCustomer("Tariq Traders", "0300-1122334", "Satellite Town", "", "", ctx.session());
        assertThrows(AppException.class, () ->
                ctx.customerLedger().createCustomer("Duplicate Phone", "0300-1122334", "", "", "", ctx.session()));

        Product pakura = ctx.menu().products(true, null).stream().filter(p -> "PAK-001".equals(p.sku())).findFirst().orElseThrow();
        Size small = ctx.menu().sizes(pakura.id()).stream().filter(s -> "250 g".equals(s.name())).findFirst().orElseThrow();
        Cart c = new Cart();
        c.type = OrderType.TAKEAWAY;
        c.add(pakura, small, List.of(), 2, ""); // 2 x 200 = 400
        long oid = ctx.orders().createOrder(c, false, ctx.session());

        // Record credit charge onto Customer Ledger
        ctx.customerLedger().addOrderToLedger(custId, oid, Money.of("400"), "Monthly account", ctx.session());

        Order onAccount = ctx.orders().find(oid);
        assertEquals(PaymentStatus.ON_ACCOUNT, onAccount.paymentStatus());
        assertEquals(0, Money.ZERO.compareTo(onAccount.paidTotal()), "Credit sale must NOT record cash paid_total");
        assertEquals(0, Money.of("400").compareTo(onAccount.creditAmount()));
        assertTrue(ctx.payments().payments(oid).isEmpty(), "Credit sale must NOT create cash payment row");

        CustomerLedgerSummary sumAfterCredit = ctx.customerLedger().summary(custId);
        assertEquals(0, Money.of("400").compareTo(sumAfterCredit.totalCredit()));
        assertEquals(0, Money.ZERO.compareTo(sumAfterCredit.totalReceived()));
        assertEquals(0, Money.of("400").compareTo(sumAfterCredit.currentBalance()));

        // Partial payment 150 -> remaining 250
        ctx.customerLedger().collectLedgerPayment(custId, Money.of("150"), PaymentMethod.CASH, "", "Part 1", ctx.session());
        assertEquals(0, Money.of("250").compareTo(ctx.customerLedger().balance(custId)));

        // Overpayment rejected
        assertThrows(AppException.class, () ->
                ctx.customerLedger().collectLedgerPayment(custId, Money.of("500"), PaymentMethod.CASH, "", "Overpay", ctx.session()));

        // Settle remaining 250 -> balance 0
        ctx.customerLedger().collectLedgerPayment(custId, Money.of("250"), PaymentMethod.CASH, "", "Final", ctx.session());
        CustomerLedgerSummary finalSum = ctx.customerLedger().summary(custId);
        assertEquals(0, Money.of("400").compareTo(finalSum.totalCredit()));
        assertEquals(0, Money.of("400").compareTo(finalSum.totalReceived()));
        assertEquals(0, Money.ZERO.compareTo(finalSum.currentBalance()));
        List<CustomerLedgerEntry> entries = ctx.customerLedger().entries(custId);
        assertEquals(3, entries.size());

        // Generate 80mm Ledger Payment Receipt PDF & verify logo is embedded at top
        Path ledgerPdf = AppPaths.receipts().resolve("Test_Ledger_" + entries.get(0).id() + ".pdf");
        ReceiptPdfGenerator.generateLedgerPaymentPdf("Tariq Traders", "0300-1122334", entries.get(0), ctx.settings(), ledgerPdf);
        assertTrue(Files.exists(ledgerPdf));
        try (PdfReader r = new PdfReader(Files.newInputStream(ledgerPdf))) {
            PdfDictionary res = r.getPageN(1).getAsDict(PdfName.RESOURCES);
            PdfDictionary xobj = res != null ? res.getAsDict(PdfName.XOBJECT) : null;
            assertNotNull(xobj, "Ledger payment receipt PDF must embed logo on top");
            assertFalse(xobj.getKeys().isEmpty());
        }
        assertNotNull(ReceiptLogo.circleSquareLogo(ctx.settings()));
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("Part 1 — Verify Dynamic Menu Management, Global Listeners, Historical Order Preservation & Restart Persistence")
    void verifyDynamicMenuUpdatesAndPersistence() {
        AtomicInteger notifications = new AtomicInteger(0);
        ctx.menu().addMenuChangeListener(notifications::incrementAndGet);

        Category cat = ctx.menu().categories(true).get(0);
        Product added = new Product(0, cat.id(), cat.name(), "TST-999", "Dynamic Special", "Test dish",
                "food/cake.jpg", Money.of("30"), Money.of("100"), null, 5, "Kitchen", true, Money.ZERO, false, true);
        long prodId = ctx.menu().saveProduct(added, ctx.session());
        assertTrue(notifications.get() >= 1);

        // Create historical paid order @ 100
        Cart c = new Cart();
        c.type = OrderType.TAKEAWAY;
        c.add(ctx.menu().product(prodId), null, List.of(), 1, "");
        long oid = ctx.orders().createOrder(c, false, ctx.session());
        ctx.payments().recordBalancePayment(oid,
                List.of(new PaymentService.PaymentInput(PaymentMethod.CASH, Money.of("100"), Money.of("100"), "", "")),
                ctx.session());

        // Edit price to 160 -> live menu reflects 160, historical order stays 100
        Product current = ctx.menu().product(prodId);
        ctx.menu().saveProduct(new Product(current.id(), current.categoryId(), current.categoryName(),
                current.sku(), "Dynamic Special Updated", current.description(), current.imagePath(),
                current.cost(), Money.of("160"), null, current.prepMinutes(), current.station(),
                true, current.taxRate(), current.trackStock(), true), ctx.session());

        assertEquals(0, Money.of("160").compareTo(ctx.menu().product(prodId).price()));
        assertEquals(0, Money.of("100").compareTo(ctx.orders().items(oid).get(0).unitPrice()),
                "Historical order item price MUST remain 100");

        // Restart migration must preserve user edits!
        new SchemaManager().migrate();
        assertEquals("Dynamic Special Updated", ctx.menu().product(prodId).name());
        assertEquals(0, Money.of("160").compareTo(ctx.menu().product(prodId).price()));

        // Soft remove item with orders -> removed from active/sellable, history intact!
        boolean hardDeleted = ctx.menu().removeProduct(prodId, ctx.session());
        assertFalse(hardDeleted, "Product with orders must be soft-deleted");
        assertTrue(ctx.menu().sellable().stream().noneMatch(p -> p.id() == prodId));
        assertEquals("Dynamic Special", ctx.orders().items(oid).get(0).name());

        // Restart migration keeps it deactivated
        new SchemaManager().migrate();
        assertFalse(ctx.menu().product(prodId).active());
    }
}
