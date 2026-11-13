package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.Role;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.Category;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.ShiftSchedule;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.printing.ReceiptPdfGenerator;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportExporter;
import com.restaurantpos.security.Session;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.service.ShiftService;
import com.restaurantpos.util.Images;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NewCategoriesAndWorkflowTest {
    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-new-cat-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Verify all 4 new categories are present, active and correctly named")
    void testFourNewCategories() {
        List<Category> categories = ctx.menu().categories(true);
        assertTrue(categories.size() >= 9, "Must have at least 9 active categories");

        List<String> expectedCategories = List.of(
                "Parachinari Lobya / Karahi",
                "Parachinari Lobya Seekh Karahi",
                "Cultural Tikka Seekh / Karahi",
                "Parachinari Chawal (Kurrmywaly Wrejy)"
        );

        for (String expected : expectedCategories) {
            Category cat = categories.stream()
                    .filter(c -> c.name().equalsIgnoreCase(expected))
                    .findFirst()
                    .orElse(null);
            assertNotNull(cat, "Category must exist: " + expected);
            assertTrue(cat.active(), "Category must be active: " + expected);
            assertNotNull(cat.imagePath(), "Category must have image path: " + expected);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Verify all 7 new products exist in correct categories with valid images")
    void testSevenNewProducts() {
        List<Product> products = ctx.menu().products(true, null);

        // Product 1: Parachinari Lobya Sada
        Product p1 = findProduct(products, "Parachinari Lobya / Karahi", "Parachinari Lobya Sada");
        assertNotNull(p1);
        assertEquals(0, BigDecimal.valueOf(150).compareTo(p1.price()));
        assertImageExists(p1.imagePath());

        // Product 2: Parachinari Lobya Karahi
        Product p2 = findProduct(products, "Parachinari Lobya / Karahi", "Parachinari Lobya Karahi");
        assertNotNull(p2);
        assertImageExists(p2.imagePath());
        List<Size> p2Sizes = ctx.menu().sizes(p2.id());
        assertEquals(4, p2Sizes.size(), "Parachinari Lobya Karahi must have 4 portion options");

        // Product 3: Parachinari Lobya Seekh Karahi
        Product p3 = findProduct(products, "Parachinari Lobya Seekh Karahi", "Parachinari Lobya Seekh Karahi");
        assertNotNull(p3);
        assertImageExists(p3.imagePath());
        List<Size> p3Sizes = ctx.menu().sizes(p3.id());
        assertEquals(4, p3Sizes.size(), "Parachinari Lobya Seekh Karahi must have 4 portion options");

        // Product 4: Cultural Tikka Seekh
        Product p4 = findProduct(products, "Cultural Tikka Seekh / Karahi", "Cultural Tikka Seekh");
        assertNotNull(p4);
        assertImageExists(p4.imagePath());
        List<Size> p4Sizes = ctx.menu().sizes(p4.id());
        assertEquals(2, p4Sizes.size(), "Cultural Tikka Seekh must have 2 portion options");

        // Product 5: Cultural Tikka Seekh Karahi
        Product p5 = findProduct(products, "Cultural Tikka Seekh / Karahi", "Cultural Tikka Seekh Karahi");
        assertNotNull(p5);
        assertImageExists(p5.imagePath());
        List<Size> p5Sizes = ctx.menu().sizes(p5.id());
        assertEquals(2, p5Sizes.size(), "Cultural Tikka Seekh Karahi must have 2 portion options");

        // Product 6: Parachinari Chawal (Kurrmywaly Wrejy)
        Product p6 = findProduct(products, "Parachinari Chawal (Kurrmywaly Wrejy)", "Parachinari Chawal (Kurrmywaly Wrejy)");
        assertNotNull(p6);
        assertImageExists(p6.imagePath());
        List<Size> p6Sizes = ctx.menu().sizes(p6.id());
        assertEquals(4, p6Sizes.size(), "Parachinari Chawal must have 4 portion options");

        // Product 7: Kurrmy Waily Wrejy (Gade Wady)
        Product p7 = findProduct(products, "Parachinari Chawal (Kurrmywaly Wrejy)", "Kurrmy Waily Wrejy (Gade Wady)");
        assertNotNull(p7);
        assertEquals(0, BigDecimal.valueOf(400).compareTo(p7.price()));
        assertImageExists(p7.imagePath());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Verify fixed-price portions for Tikka and Rice have exact specified prices")
    void testFixedPricePortions() {
        // Cultural Tikka Seekh: Half Dozen (Rs. 400), Full Dozen (Rs. 800)
        Product tikka = findProduct(ctx.menu().products(true, null), "Cultural Tikka Seekh / Karahi", "Cultural Tikka Seekh");
        List<Size> tikkaSizes = ctx.menu().sizes(tikka.id());
        Size halfDozenTikka = findSize(tikkaSizes, "Half Dozen");
        Size fullDozenTikka = findSize(tikkaSizes, "Full Dozen");
        assertEquals(0, BigDecimal.valueOf(400).compareTo(halfDozenTikka.price()));
        assertEquals(0, BigDecimal.valueOf(800).compareTo(fullDozenTikka.price()));

        // Cultural Tikka Seekh Karahi: Half Dozen (Rs. 550), Full Dozen (Rs. 1100)
        Product tikkaKarahi = findProduct(ctx.menu().products(true, null), "Cultural Tikka Seekh / Karahi", "Cultural Tikka Seekh Karahi");
        List<Size> tikkaKarahiSizes = ctx.menu().sizes(tikkaKarahi.id());
        Size halfDozenKarahi = findSize(tikkaKarahiSizes, "Half Dozen");
        Size fullDozenKarahi = findSize(tikkaKarahiSizes, "Full Dozen");
        assertEquals(0, BigDecimal.valueOf(550).compareTo(halfDozenKarahi.price()));
        assertEquals(0, BigDecimal.valueOf(1100).compareTo(fullDozenKarahi.price()));

        // Parachinari Chawal (Kurrmywaly Wrejy): Single 550, Double 1100, Three 1600, Four 2000
        Product chawal = findProduct(ctx.menu().products(true, null), "Parachinari Chawal (Kurrmywaly Wrejy)", "Parachinari Chawal (Kurrmywaly Wrejy)");
        List<Size> chawalSizes = ctx.menu().sizes(chawal.id());
        assertEquals(0, BigDecimal.valueOf(550).compareTo(findSize(chawalSizes, "Single Plate").price()));
        assertEquals(0, BigDecimal.valueOf(1100).compareTo(findSize(chawalSizes, "Double Plate").price()));
        assertEquals(0, BigDecimal.valueOf(1600).compareTo(findSize(chawalSizes, "Three Plates").price()));
        assertEquals(0, BigDecimal.valueOf(2000).compareTo(findSize(chawalSizes, "Four Plates").price()));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Verify ranged pricing: min/max stored, unconfigured portion blocked from cart, admin configuration validated")
    void testRangedPricingRules() {
        Product lobyaKarahi = findProduct(ctx.menu().products(true, null), "Parachinari Lobya / Karahi", "Parachinari Lobya Karahi");
        List<Size> sizes = ctx.menu().sizes(lobyaKarahi.id());

        Size quarter = findSize(sizes, "Quarter");
        assertTrue(quarter.isRanged(), "Quarter Lobya Karahi must be marked as ranged");
        assertEquals(0, BigDecimal.valueOf(350).compareTo(quarter.minPrice()));
        assertEquals(0, BigDecimal.valueOf(450).compareTo(quarter.maxPrice()));

        Size twoQuarter = findSize(sizes, "2 Quarter");
        assertEquals(0, BigDecimal.valueOf(500).compareTo(twoQuarter.minPrice()));
        assertEquals(0, BigDecimal.valueOf(600).compareTo(twoQuarter.maxPrice()));

        Size threeQuarter = findSize(sizes, "3 Quarter");
        assertEquals(0, BigDecimal.valueOf(650).compareTo(threeQuarter.minPrice()));
        assertEquals(0, BigDecimal.valueOf(750).compareTo(threeQuarter.maxPrice()));

        Size fourQuarter = findSize(sizes, "4 Quarter");
        assertEquals(0, BigDecimal.valueOf(800).compareTo(fourQuarter.minPrice()));
        assertEquals(0, BigDecimal.valueOf(900).compareTo(fourQuarter.maxPrice()));

        // Test Cart blocking when price is unconfigured
        if (!quarter.isConfigured()) {
            Cart cart = new Cart();
            cart.type = OrderType.TAKEAWAY;
            Exception ex = assertThrows(AppException.class, () -> {
                cart.add(lobyaKarahi, quarter, List.of(), 1, "");
            });
            assertTrue(ex.getMessage().contains("Price not configured") || ex.getMessage().contains("price is not yet configured"));
        }

        // Test Admin range validation: cannot configure price below min or above max
        assertThrows(AppException.class, () -> {
            ctx.menu().configureVariantPrice(quarter.id(), BigDecimal.valueOf(300), ctx.session());
        }, "Should reject price below minimum 350");

        assertThrows(AppException.class, () -> {
            ctx.menu().configureVariantPrice(quarter.id(), BigDecimal.valueOf(500), ctx.session());
        }, "Should reject price above maximum 450");

        // Admin configures valid selling price (e.g. 400 within 350-450)
        ctx.menu().configureVariantPrice(quarter.id(), BigDecimal.valueOf(400), ctx.session());

        // Reload size and verify
        List<Size> updatedSizes = ctx.menu().sizes(lobyaKarahi.id());
        Size configuredQuarter = findSize(updatedSizes, "Quarter");
        assertTrue(configuredQuarter.isConfigured());
        assertEquals(0, BigDecimal.valueOf(400).compareTo(configuredQuarter.price()));
        assertEquals(0, BigDecimal.valueOf(400).compareTo(configuredQuarter.effectivePrice(lobyaKarahi.price())));

        // Cart now accepts configured ranged portion
        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.add(lobyaKarahi, configuredQuarter, List.of(), 2, "");
        assertEquals(1, cart.lines.size());
        assertEquals(0, BigDecimal.valueOf(800).compareTo(cart.lines.get(0).gross()));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Verify sequential MCR order numbering: MCR-0001, MCR-0002...")
    void testMcrOrderNumbering() {
        Cart cart1 = new Cart();
        cart1.type = OrderType.TAKEAWAY;
        Product sada = findProduct(ctx.menu().products(true, null), "Parachinari Lobya Sada");
        cart1.add(sada, null, List.of(), 1, "");

        long id1 = ctx.orders().createOrder(cart1, false, ctx.session());
        Order order1 = ctx.orders().find(id1);
        assertNotNull(order1);
        assertTrue(order1.orderNo().matches("^MCR-\\d{4,}$"), "Order number must follow MCR-XXXX format: " + order1.orderNo());

        Cart cart2 = new Cart();
        cart2.type = OrderType.TAKEAWAY;
        cart2.add(sada, null, List.of(), 1, "");
        long id2 = ctx.orders().createOrder(cart2, false, ctx.session());
        Order order2 = ctx.orders().find(id2);
        assertNotNull(order2);
        assertTrue(order2.orderNo().matches("^MCR-\\d{4,}$"), "Order number must follow MCR-XXXX format: " + order2.orderNo());

        // Sequential check
        int num1 = Integer.parseInt(order1.orderNo().replace("MCR-", ""));
        int num2 = Integer.parseInt(order2.orderNo().replace("MCR-", ""));
        assertEquals(num1 + 1, num2, "Consecutive orders must have sequential MCR numbers");
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Verify optional table and waiter (No Table, No Waiter order creation)")
    void testNoTableNoWaiterOrder() {
        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableId = null;
        cart.tableNo = null;
        cart.waiterId = null;
        cart.waiterName = null;

        Product gadeWady = findProduct(ctx.menu().products(true, null), "Kurrmy Waily Wrejy (Gade Wady)");
        cart.add(gadeWady, null, List.of(), 1, "");

        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);
        assertNotNull(order);
        assertNull(order.tableId(), "Table ID should be null for No Table");
        assertNull(order.waiterId(), "Waiter ID should be null for No Waiter");
        assertEquals(0, BigDecimal.valueOf(400).compareTo(order.total()));
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("Verify shift scheduling and midnight crossing calculation")
    void testShiftScheduling() {
        // Schedule 1: Daytime 08:00 to 16:00
        ShiftSchedule daySchedule = new ShiftSchedule(1L, "Day Shift", "08:00", "16:00", "ALL", 15, true);
        assertTrue(ShiftService.matchesSchedule(LocalTime.of(10, 0), daySchedule));
        assertFalse(ShiftService.matchesSchedule(LocalTime.of(18, 0), daySchedule));

        // Schedule 2: Night shift crossing midnight (20:00 to 04:00)
        ShiftSchedule nightSchedule = new ShiftSchedule(2L, "Night Shift", "20:00", "04:00", "ALL", 15, true);
        assertTrue(ShiftService.matchesSchedule(LocalTime.of(23, 0), nightSchedule), "23:00 is during night shift");
        assertTrue(ShiftService.matchesSchedule(LocalTime.of(2, 0), nightSchedule), "02:00 is during night shift");
        assertFalse(ShiftService.matchesSchedule(LocalTime.of(12, 0), nightSchedule), "12:00 is not during night shift");
        assertFalse(ShiftService.matchesSchedule(LocalTime.of(19, 0), nightSchedule), "19:00 is not during night shift");

        // Save shift schedule to DB and retrieve
        ctx.shifts().saveSchedule(new ShiftSchedule(0L, "Custom Test Shift", "14:00", "22:00", "ALL", 15, true), ctx.session());
        List<ShiftSchedule> schedules = ctx.shifts().schedules();
        assertFalse(schedules.isEmpty());
        assertTrue(schedules.stream().anyMatch(s -> "Custom Test Shift".equals(s.name())));
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("Verify session timeout and security: touch, idle minutes exceeded, logout")
    void testSessionTimeoutAndSecurity() {
        Session session = new Session();
        User testUser = ctx.session().user();
        session.start(testUser);
        assertTrue(session.active());
        assertFalse(session.idleMinutesExceeded(15));

        session.touch();
        assertNotNull(session.lastActivity());

        // Logout
        session.end();
        assertFalse(session.active());
        assertNull(session.user());
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("Verify cashier PDF reports export (A4 format) and 80mm receipt generation")
    void testCashierReportingAndReceipts() throws Exception {
        // Create an order and record payment
        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        Product p = findProduct(ctx.menu().products(true, null), "Parachinari Lobya Sada");
        cart.add(p, null, List.of(), 1, "");
        long orderId = ctx.orders().createOrder(cart, false, ctx.session());

        PaymentInput pIn = new PaymentInput(PaymentMethod.CASH, BigDecimal.valueOf(150), BigDecimal.valueOf(150), "Test Cash", "");
        ctx.payments().recordBalancePayment(orderId, List.of(pIn), ctx.session());
        Order completedOrder = ctx.orders().find(orderId);

        // Verify 80mm PDF receipt generation with circular logo
        Path receiptPdf = tempDir.resolve("test_receipt_" + completedOrder.orderNo() + ".pdf");
        var items = ctx.orders().items(completedOrder.id());
        var payments = ctx.payments().payments(completedOrder.id());
        ReceiptPdfGenerator.generateCustomerPdf(completedOrder, items, payments, completedOrder.orderNo(), ctx.settings(), receiptPdf);
        assertTrue(Files.exists(receiptPdf), "Customer 80mm PDF must be generated");
        assertTrue(Files.size(receiptPdf) > 500, "Receipt PDF must not be empty");

        // Verify PDF Magic Bytes %PDF
        byte[] bytes = Files.readAllBytes(receiptPdf);
        assertEquals('%', bytes[0]);
        assertEquals('P', bytes[1]);
        assertEquals('D', bytes[2]);
        assertEquals('F', bytes[3]);

        // Cashier role session testing report export
        User cashierUser = new User(
                999L, "cashier1", "Cashier Test", Role.CASHIER, true, false, null, LocalDateTime.now(), null
        );
        Session cashierSession = new Session();
        cashierSession.start(cashierUser);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{completedOrder.orderNo(), completedOrder.total()});

        ReportData testReport = new ReportData(
                "test-report",
                "Cashier Daily Summary",
                LocalDate.now().toString(),
                List.of(
                        new ReportData.Column("Order #", ReportData.ColType.TEXT, 20),
                        new ReportData.Column("Total", ReportData.ColType.CURRENCY, 15)
                ),
                rows
        );

        Path exportedPdf = ctx.exporter().export(testReport, ReportExporter.Format.PDF, cashierSession);
        assertTrue(Files.exists(exportedPdf), "Exported A4 report PDF must exist for Cashier");
        assertTrue(Files.size(exportedPdf) > 500, "Report PDF must have valid size");
    }

    private Product findProduct(List<Product> products, String categoryName, String name) {
        return products.stream()
                .filter(p -> p.categoryName() != null && p.categoryName().equalsIgnoreCase(categoryName)
                        && p.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private Product findProduct(List<Product> products, String name) {
        return products.stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private Size findSize(List<Size> sizes, String portionName) {
        return sizes.stream()
                .filter(s -> s.name().equalsIgnoreCase(portionName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Portion option not found: " + portionName));
    }

    private void assertImageExists(String imagePath) {
        assertNotNull(imagePath, "Product must have an image path");
        Path resolved = Images.resolve(imagePath);
        assertNotNull(resolved, "Images.resolve must find file for " + imagePath);
        assertTrue(Files.isRegularFile(resolved), "Image file must exist on disk: " + resolved);
    }
}
