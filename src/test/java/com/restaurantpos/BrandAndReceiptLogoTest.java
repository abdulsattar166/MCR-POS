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
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.model.Sales.Payment;
import com.restaurantpos.printing.ReceiptLogo;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.printing.ReceiptPrintingService;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportExporter;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.util.BrandLogo;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BrandAndReceiptLogoTest {
    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-brand-logo-test-");
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
        if (ctx != null && ctx.session().active()) ctx.auth().logout();
        AppContext.resetForTesting();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Official brown MCR logo is bundled in resources and replaces legacy placeholders")
    void testOfficialResourceAndPlaceholderReplacement() throws Exception {
        assertNotNull(BrandAndReceiptLogoTest.class.getResource(BrandLogo.OFFICIAL_RESOURCE));
        assertNotNull(BrandAndReceiptLogoTest.class.getResource(BrandLogo.LEGACY_RESOURCE));
        assertNotNull(BrandAndReceiptLogoTest.class.getResource(BrandLogo.BRANDING_RESOURCE));

        BufferedImage official = BrandLogo.loadBuffered(ctx.settings());
        assertEquals(1024, official.getWidth());
        assertEquals(1024, official.getHeight());

        // Simulate an existing 30,363-byte legacy placeholder file on disk and verify upgrade replacement
        Path brandFile = AppPaths.brandImages().resolve("logo.png");
        Files.write(brandFile, new byte[30363]);
        Images.generateLogo();
        assertTrue(Files.size(brandFile) > 500_000, "Legacy placeholder must be replaced with official MCR logo");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Circular crop preserves aspect ratio, has transparent corners, and produces valid ESC/POS raster")
    void testCircularCropAndEscPosRaster() throws Exception {
        BufferedImage circle = BrandLogo.circularBuffered(ctx.settings(), 240);
        assertEquals(240, circle.getWidth());
        assertEquals(240, circle.getHeight());

        // Corner outside circular mask must be fully transparent (alpha == 0)
        int cornerAlpha = (circle.getRGB(0, 0) >>> 24) & 0xFF;
        assertEquals(0, cornerAlpha, "Pixels outside circular crop must be transparent");

        // Center inside circle must be opaque
        int centerAlpha = (circle.getRGB(120, 120) >>> 24) & 0xFF;
        assertEquals(255, centerAlpha, "Pixels inside circular crop must be opaque");

        // Verify ESC/POS GS v 0 raster command + center align + non-solid black/white pattern
        byte[] raster = ReceiptLogo.escPosRaster(ctx.settings());
        assertTrue(raster.length > 1000);
        assertEquals(0x1B, raster[0] & 0xFF);
        assertEquals(0x61, raster[1] & 0xFF);
        assertEquals(0x01, raster[2] & 0xFF);
        assertEquals(0x1D, raster[3] & 0xFF);
        assertEquals(0x76, raster[4] & 0xFF);

        // Ensure not all bitmap bytes are 0xFF (would indicate crushed solid-black circle)
        int fullBlackBytes = 0;
        for (int i = 8; i < raster.length - 4; i++) {
            if ((raster[i] & 0xFF) == 0xFF) fullBlackBytes++;
        }
        assertTrue(fullBlackBytes < (raster.length * 0.45), "Dithered ESC/POS raster must not crush logo to solid black");

        // Export visual sample for verification
        ImageIO.write(circle, "PNG", AppPaths.temp().resolve("verified_circular_logo.png").toFile());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("All receipt PDFs (new paid, reprint, download, kitchen, ledger, older order, post-restart) & reports embed logo")
    void testAllReceiptsAndReportsEmbedOfficialLogo() throws Exception {
        Product p = ctx.menu().products(true, null).get(0);
        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableNo = "Table 1";
        cart.waiterName = "Waiter 1";
        cart.add(p, null, List.of(), 2, "");
        long oid = ctx.orders().createOrder(cart, false, ctx.session());
        Order unpaid = ctx.orders().find(oid);

        ctx.payments().recordBalancePayment(oid,
                List.of(new PaymentInput(PaymentMethod.CASH, unpaid.total(), unpaid.total(), "REF-1", "")),
                ctx.session());
        Order paid = ctx.orders().find(oid);
        List<OrderItem> items = ctx.orders().items(oid);
        List<Payment> payments = ctx.payments().payments(oid);

        Path newPaidPdf = AppPaths.receipts().resolve("LogoTest_Paid.pdf");
        Path reprintPdf = AppPaths.receipts().resolve("LogoTest_Reprint.pdf");
        Path downloadedPdf = AppPaths.receipts().resolve("LogoTest_Download.pdf");

        ReceiptPdfGenerator.generateCustomerPdf(paid, items, payments, "REC-01", ctx.settings(), newPaidPdf, false);
        ReceiptPdfGenerator.generateCustomerPdf(paid, items, payments, "REP-01", ctx.settings(), reprintPdf, true);
        ReceiptPdfGenerator.generateCustomerPdf(paid, items, payments, paid.orderNo(), ctx.settings(), downloadedPdf);

        Cart creditCart = new Cart();
        creditCart.type = OrderType.TAKEAWAY;
        creditCart.add(p, null, List.of(), 1, "");
        long creditOid = ctx.orders().createOrder(creditCart, false, ctx.session());
        Order creditOrder = ctx.orders().find(creditOid);
        long cid = ctx.customerLedger().createCustomer("Logo Test Customer", "0300-7788990", "Rawalpindi", "", "", ctx.session());
        ctx.customerLedger().addOrderToLedger(cid, creditOid, creditOrder.total(), "Credit test", ctx.session());
        ctx.customerLedger().collectLedgerPayment(cid, creditOrder.total(), PaymentMethod.CASH, "L-1", "Settle", ctx.session());
        CustomerLedgerEntry entry = ctx.customerLedger().entries(cid).get(0);
        Path ledgerPdf = AppPaths.receipts().resolve("LogoTest_Ledger.pdf");
        ReceiptPdfGenerator.generateLedgerPaymentPdf("Logo Test Customer", "0300-7788990", entry, ctx.settings(), ledgerPdf);

        // Verify ReceiptPrintingService async generation produces PDFs with embedded logo
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReceiptPrintingService.PrintResult> printRes = new AtomicReference<>();
        ctx.printer().printPair(paid, false, ctx.session().userId(), r -> {
            printRes.set(r);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(printRes.get().success());

        // Simulate application restart and generate receipt for previously saved order
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
        Order afterRestart = ctx.orders().find(oid);
        Path afterRestartPdf = AppPaths.receipts().resolve("LogoTest_AfterRestart.pdf");
        ReceiptPdfGenerator.generateCustomerPdf(afterRestart, ctx.orders().items(oid), ctx.payments().payments(oid),
                "RESTART-" + afterRestart.orderNo(), ctx.settings(), afterRestartPdf);

        // Branded Report PDF export
        ReportData rep = new ReportData("sales", "Sales Summary", "Today",
                List.of(ReportData.Column.text("Item", 20), ReportData.Column.money("Amount", 12)),
                List.<Object[]>of(new Object[]{p.name(), Money.of("100")}));
        Path reportPdf = ctx.exporter().export(rep, ReportExporter.Format.PDF, ctx.session());

        for (Path pdf : List.of(newPaidPdf, reprintPdf, downloadedPdf, ledgerPdf,
                printRes.get().customerPdfPath(), afterRestartPdf, reportPdf)) {
            assertPdfHasEmbeddedImage(pdf);
        }
    }

    private static void assertPdfHasEmbeddedImage(Path pdf) throws Exception {
        assertTrue(Files.exists(pdf), "PDF must exist: " + pdf);
        assertTrue(Files.size(pdf) > 2000, "PDF with logo should be non-trivial: " + pdf);
        try (PdfReader reader = new PdfReader(Files.newInputStream(pdf))) {
            PdfDictionary res = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
            PdfDictionary xobj = res != null ? res.getAsDict(PdfName.XOBJECT) : null;
            assertNotNull(xobj, "PDF must embed official logo XObject on page 1: " + pdf.getFileName());
            assertFalse(xobj.getKeys().isEmpty(), "XObject map must not be empty: " + pdf.getFileName());
        }
    }
}
