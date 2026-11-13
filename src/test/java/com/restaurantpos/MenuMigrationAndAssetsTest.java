package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.MenuMigrator;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.model.Catalog.*;
import com.restaurantpos.service.Cart;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.MenuAssetManager;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MenuMigrationAndAssetsTest {
    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-menu-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
    }

    @Test
    @Order(1)
    @DisplayName("Verify local food images exist and are valid readable images")
    void verifyImageAssets() throws Exception {
        MenuAssetManager.ensureAllAssetsExist();

        Path resFoodDir = Path.of("src", "main", "resources", "images", "food");
        assertTrue(Files.isDirectory(resFoodDir), "Resource food directory must exist");

        // Verify category images
        String[] catImages = {
                "cat_chai_nashta.jpg", "cat_rice_drinks.jpg", "cat_bbq_fastfood.jpg",
                "cat_handi_salan.jpg", "cat_signature_dishes.jpg",
                "cat_parachinari_lobya_karahi.jpg", "cat_parachinari_lobya_seekh_karahi.jpg",
                "cat_cultural_tikka_seekh_karahi.jpg", "cat_parachinari_chawal.jpg"
        };
        for (String cImg : catImages) {
            Path p = resFoodDir.resolve(cImg);
            assertTrue(Files.isRegularFile(p), "Category image must exist: " + cImg);
            BufferedImage img = ImageIO.read(p.toFile());
            assertNotNull(img, "Category image must be valid: " + cImg);
            assertEquals(MenuAssetManager.TARGET_WIDTH, img.getWidth());
            assertEquals(MenuAssetManager.TARGET_HEIGHT, img.getHeight());
        }

        // Verify product images for all 125 products
        List<Product> products = ctx.menu().products(true, null);
        assertEquals(125, products.size(), "Must have exactly 125 active products in database");

        for (Product prod : products) {
            assertNotNull(prod.imagePath(), "Product must have imagePath: " + prod.name());
            Path resolved = Images.resolve(prod.imagePath());
            assertNotNull(resolved, "Images.resolve must not be null for: " + prod.imagePath());
            assertTrue(Files.isRegularFile(resolved), "Resolved image file must exist: " + resolved);

            BufferedImage bImg = ImageIO.read(resolved.toFile());
            assertNotNull(bImg, "Image must be decodable for " + prod.name() + " at " + resolved);
            assertEquals(MenuAssetManager.TARGET_WIDTH, bImg.getWidth());
            assertEquals(MenuAssetManager.TARGET_HEIGHT, bImg.getHeight());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Verify exact PDF categories, counts, and descriptions")
    void verifyCategories() {
        List<Category> cats = ctx.menu().categories(true);
        assertEquals(9, cats.size(), "Must have exactly 9 active categories");

        List<String> expectedNames = List.of(
                "Chai & Nashta", "Rice & Drinks", "BBQ & Fast Food", "Handi & Salan", "Signature Dishes",
                "Parachinari Lobya / Karahi", "Parachinari Lobya Seekh Karahi", "Cultural Tikka Seekh / Karahi", "Parachinari Chawal (Kurrmywaly Wrejy)"
        );
        for (int i = 0; i < expectedNames.size(); i++) {
            assertEquals(expectedNames.get(i), cats.get(i).name());
            assertEquals(i + 1, cats.get(i).sortOrder());
            assertTrue(cats.get(i).active());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Verify authoritative PDF pricing across key menu items")
    void verifyProductPrices() {
        assertPrice("TEA-001", "Chai", 70);
        assertPrice("TEA-002", "Special Chai", 100);
        assertPrice("TEA-003", "Gurh wali Chai", 120);
        assertPrice("TEA-007", "Karak Chai", 100);
        assertPrice("TEA-010", "Kashmiri Chai", 180);

        assertPrice("EGG-001", "Half Fry Egg", 70);
        assertPrice("EGG-002", "Omelette", 80);
        assertPrice("EGG-005", "Malai (Half)", 120);
        assertPrice("EGG-006", "Malai (Full)", 170);

        assertPrice("PAR-001", "Lachadar Paratha", 70);
        assertPrice("PAR-004", "Aloo Paratha", 150);
        assertPrice("PAR-008", "Chicken Cheese Paratha", 350);
        assertPrice("PAR-010", "Honey Malai Paratha", 400);
        assertPrice("PAR-014", "Lahori Paratha", 450);

        assertPrice("RCE-001", "Aloo Biryani (Half)", 200);
        assertPrice("RCE-002", "Aloo Biryani (Full)", 200);
        assertPrice("RCE-003", "Single Aloo Chicken Biryani", 250);
        assertPrice("RCE-004", "Double Chicken Biryani", 350);
        assertPrice("PLU-001", "Sada Pulao", 300);
        assertPrice("PLU-003", "Double Chicken Pulao", 450);

        assertPrice("ROT-001", "Sada Roti", 20);
        assertPrice("ROT-002", "Naan", 30);
        assertPrice("ROT-003", "Roghani Naan", 70);

        assertPrice("DRK-001", "Regular Drink", 90);
        assertPrice("BEV-002", "1 Liter", 180);
        assertPrice("BEV-003", "1.5 Liter", 220);
        assertPrice("BEV-004", "Lassi", 170);
        assertPrice("BEV-005", "Mineral Water", 50);

        assertPrice("BBQ-001", "Chest Piece", 400);
        assertPrice("BBQ-002", "Leg Piece", 350);
        assertPrice("BBQ-003", "Seekh Tikka", 250);

        assertPrice("FST-001", "Fries (Small)", 100);
        assertPrice("FST-004", "Loaded Fries", 350);
        assertPrice("FST-005", "Chicken Shawarma (Small)", 180);
        assertPrice("FST-006", "Chicken Shawarma (Large)", 220);
        assertPrice("FST-007", "Zinger Shawarma (Large)", 300);
        assertPrice("FST-008", "Chicken Roll Paratha", 300);
        assertPrice("FST-009", "Zinger Roll Paratha", 350);

        assertPrice("BUR-001", "Zinger Burger", 350);
        assertPrice("BUR-002", "Chicken Burger", 300);
        assertPrice("BUR-003", "Anda Shami Burger", 200);
        assertPrice("BUR-005", "Shami Burger", 170);

        assertPrice("SUP-001", "Chicken Corn Soup (Sada)", 150);
        assertPrice("SUP-002", "Chicken Corn Soup (Anda)", 200);

        assertPrice("VEG-001", "Mix Sabzi (Half)", 170);
        assertPrice("VEG-002", "Mix Sabzi (Full)", 220);
        assertPrice("VEG-003", "Bhindi (Half)", 200);
        assertPrice("VEG-004", "Bhindi (Full)", 270);
        assertPrice("VEG-007", "Daal Mash (Half)", 170);
        assertPrice("VEG-008", "Daal Mash (Full)", 220);
        assertPrice("VEG-011", "Chany (Half)", 150);
        assertPrice("VEG-013", "Lobya (Half)", 170);

        assertPrice("CHK-001", "Chicken Qorma (Half)", 220);
        assertPrice("CHK-002", "Chicken Qorma (Full)", 320);
        assertPrice("CHK-003", "Chicken Talfarizi (Half)", 280);
        assertPrice("CHK-004", "Chicken Talfarizi (Full)", 400);

        assertPrice("BEF-001", "Aloo Gosht (Half)", 250);
        assertPrice("BEF-002", "Aloo Gosht (Full)", 350);
        assertPrice("BEF-003", "Aloo Qeema (Half)", 220);
        assertPrice("BEF-004", "Aloo Qeema (Full)", 320);
        assertPrice("BEF-005", "Gosht Fry (Half)", 300);
        assertPrice("BEF-006", "Gosht Fry (Full)", 400);

        assertPrice("KRH-001", "Chicken Karahi (Half)", 1000);
        assertPrice("KRH-002", "Chicken Karahi (Full)", 2000);
        assertPrice("KRH-003", "Chicken White Karahi (Half)", 1200);
        assertPrice("KRH-004", "Chicken White Karahi (Full)", 2300);

        assertPrice("SIG-001", "Parachinari Lobya Sada", 150);
        assertPrice("SIG-002", "Parachinari Lobya Karahi (Quarter)", 350);
        assertPrice("SIG-006", "Parachinari Lobya Seekh Karahi (Quarter)", 500);
        assertPrice("SIG-010", "Cultural Tikka Seekh", 400);
        assertPrice("SIG-011", "Cultural Tikka Seekh Karahi", 550);
        assertPrice("SIG-012", "Single Plate (1 Chicken Piece + Shurba + Raita) — 1 Person", 550);
        assertPrice("SIG-013", "Double Plate (2 Chicken Pieces + Shurba + Raita) — 2 Persons", 1100);
        assertPrice("SIG-015", "Four Plates (4 Chicken Pieces + Shurba + Raita) — 4 Persons", 2000);
    }

    @Test
    @Order(4)
    @DisplayName("Verify variants configuration and price calculation")
    void verifyVariants() {
        // Mineral Water: Small (0 extra), Large (+60 extra = 110)
        Product water = ctx.menu().products(true, null).stream()
                .filter(p -> p.sku().equals("BEV-005")).findFirst().orElseThrow();
        List<Size> waterSizes = ctx.menu().sizes(water.id());
        assertEquals(2, waterSizes.size());
        Size waterLarge = waterSizes.stream().filter(s -> s.name().contains("Large")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("60"), waterLarge.extraPrice());
        assertEquals(new BigDecimal("110"), water.effectivePrice().add(waterLarge.extraPrice()));

        // Cultural Tikka Seekh: Half Dozen (0 extra = 400), Full Dozen (+400 extra = 800)
        Product tikka = ctx.menu().products(true, null).stream()
                .filter(p -> p.sku().equals("SIG-010")).findFirst().orElseThrow();
        List<Size> tikkaSizes = ctx.menu().sizes(tikka.id());
        assertEquals(2, tikkaSizes.size());
        Size tikkaFull = tikkaSizes.stream().filter(s -> s.name().contains("Full Dozen")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("400"), tikkaFull.extraPrice());
        assertEquals(new BigDecimal("800"), tikka.effectivePrice().add(tikkaFull.extraPrice()));

        // Parachinari Lobya Karahi: Simple (0 extra = 350), Desi Ghee (+100 extra = 450)
        Product lobya = ctx.menu().products(true, null).stream()
                .filter(p -> p.sku().equals("SIG-002")).findFirst().orElseThrow();
        List<Size> lobyaSizes = ctx.menu().sizes(lobya.id());
        assertEquals(2, lobyaSizes.size());
        Size desiGhee = lobyaSizes.stream().filter(s -> s.name().equalsIgnoreCase("Desi Ghee")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("100"), desiGhee.extraPrice());
        assertEquals(new BigDecimal("450"), lobya.effectivePrice().add(desiGhee.extraPrice()));
    }

    @Test
    @Order(5)
    @DisplayName("Verify ordering and billing with new menu items")
    void testOrderWorkflow() {
        Product chai = ctx.menu().products(true, null).stream()
                .filter(p -> p.sku().equals("TEA-001")).findFirst().orElseThrow();
        Product paratha = ctx.menu().products(true, null).stream()
                .filter(p -> p.sku().equals("PAR-001")).findFirst().orElseThrow();

        Cart cart = new Cart();
        cart.type = OrderType.DINE_IN;
        cart.tableId = 1L;
        cart.tableNo = "Table 1";
        cart.waiterName = "Waiter 1";

        cart.add(chai, null, List.of(), 2, "Less sugar");
        cart.add(paratha, null, List.of(), 2, "Crispy");

        BigDecimal linesTotal = cart.lines.stream().map(Cart.CartLine::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedTotal = BigDecimal.valueOf(70 * 2 + 70 * 2); // 280
        assertEquals(0, expectedTotal.compareTo(linesTotal));
    }

    @Test
    @Order(6)
    @DisplayName("Verify migration idempotency (no duplicates on second run)")
    void testMigrationIdempotency() {
        new MenuMigrator().migrate();

        List<Category> cats = ctx.menu().categories(true);
        assertEquals(9, cats.size(), "Must still have exactly 9 categories");

        List<Product> prods = ctx.menu().products(true, null);
        assertEquals(125, prods.size(), "Must still have exactly 125 products");
    }

    private void assertPrice(String sku, String expectedName, double expectedPrice) {
        Product p = ctx.menu().products(true, null).stream()
                .filter(x -> x.sku().equals(sku)).findFirst()
                .orElseThrow(() -> new AssertionError("Product SKU not found: " + sku));
        assertEquals(expectedName, p.name(), "Product name mismatch for " + sku);
        assertEquals(0, BigDecimal.valueOf(expectedPrice).compareTo(p.price()),
                "Price mismatch for " + sku + " (" + expectedName + ")");
    }
}
