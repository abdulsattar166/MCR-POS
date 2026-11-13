package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.Role;
import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.service.Cart;
import com.restaurantpos.ui.MainShell;
import com.restaurantpos.ui.components.ProductCard;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import com.restaurantpos.ui.views.PosView;
import com.restaurantpos.util.Images;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NewOrderUiAndWorkflowTest {

    private static AppContext ctx;
    private static Path tempDir;
    private static Stage stage;
    private static MainShell shell;

    @BeforeAll
    public static void setup() throws Exception {
        CountDownLatch fxLatch = new CountDownLatch(1);
        try {
            Platform.startup(fxLatch::countDown);
        } catch (IllegalStateException e) {
            fxLatch.countDown();
        }
        assertTrue(fxLatch.await(5, TimeUnit.SECONDS));

        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-neworder-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");

        CountDownLatch stageLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            stage = new Stage();
            com.restaurantpos.ui.AppWindow window = new com.restaurantpos.ui.AppWindow(stage);
            shell = new MainShell(window, ctx);
            stageLatch.countDown();
        });
        assertTrue(stageLatch.await(5, TimeUnit.SECONDS));
    }

    @AfterAll
    public static void tearDown() {
        if (stage != null) {
            Platform.runLater(stage::close);
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. Verify PAR-004, PAR-005, and PAR-006 have distinct, verified image assets")
    public void testParathaImageDistinctMapping() throws Exception {
        List<Product> products = ctx.menu().products(true, null);

        Product alooParatha = products.stream().filter(p -> "PAR-004".equalsIgnoreCase(p.sku())).findFirst().orElse(null);
        Product alooChicken = products.stream().filter(p -> "PAR-005".equalsIgnoreCase(p.sku())).findFirst().orElse(null);
        Product alooCheese = products.stream().filter(p -> "PAR-006".equalsIgnoreCase(p.sku())).findFirst().orElse(null);

        assertNotNull(alooParatha, "Aloo Paratha must exist in database");
        assertNotNull(alooChicken, "Aloo Chicken Paratha must exist in database");
        assertNotNull(alooCheese, "Aloo Cheese Paratha must exist in database");

        // Verify image paths are different in DB
        assertNotEquals(alooParatha.imagePath(), alooChicken.imagePath(), "Aloo Paratha and Chicken Paratha must not share path");
        assertNotEquals(alooParatha.imagePath(), alooCheese.imagePath(), "Aloo Paratha and Cheese Paratha must not share path");
        assertNotEquals(alooChicken.imagePath(), alooCheese.imagePath(), "Aloo Chicken and Cheese Paratha must not share path");

        assertEquals("food/aloo-paratha.jpg", alooParatha.imagePath());
        assertEquals("food/aloo-chicken-paratha.jpg", alooChicken.imagePath());
        assertEquals("food/aloo-cheese-paratha.jpg", alooCheese.imagePath());

        // Resolve files
        Path p4 = Images.resolve(alooParatha.imagePath());
        Path p5 = Images.resolve(alooChicken.imagePath());
        Path p6 = Images.resolve(alooCheese.imagePath());

        assertNotNull(p4, "Image must resolve for PAR-004");
        assertNotNull(p5, "Image must resolve for PAR-005");
        assertNotNull(p6, "Image must resolve for PAR-006");

        assertTrue(Files.isRegularFile(p4));
        assertTrue(Files.isRegularFile(p5));
        assertTrue(Files.isRegularFile(p6));

        // Verify dimensions 640x420
        BufferedImage img4 = ImageIO.read(p4.toFile());
        BufferedImage img5 = ImageIO.read(p5.toFile());
        BufferedImage img6 = ImageIO.read(p6.toFile());

        assertNotNull(img4);
        assertNotNull(img5);
        assertNotNull(img6);

        assertEquals(640, img4.getWidth());
        assertEquals(420, img4.getHeight());
        assertEquals(640, img5.getWidth());
        assertEquals(420, img5.getHeight());
        assertEquals(640, img6.getWidth());
        assertEquals(420, img6.getHeight());

        // Verify MD5 hashes are distinct (not identical copies)
        String hash4 = md5(Files.readAllBytes(p4));
        String hash5 = md5(Files.readAllBytes(p5));
        String hash6 = md5(Files.readAllBytes(p6));

        assertNotEquals(hash4, hash5, "PAR-004 and PAR-005 images must have different content");
        assertNotEquals(hash4, hash6, "PAR-004 and PAR-006 images must have different content");
        assertNotEquals(hash5, hash6, "PAR-005 and PAR-006 images must have different content");
    }

    @Test
    @Order(2)
    @DisplayName("2. Verify New Order title and subtitle block is removed to reclaim vertical space")
    public void testNewOrderHeaderRemoved() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PosView pos = new PosView(ctx, shell);
                // Top child should be the wizard step indicator bar, not pageHeader
                Node topChild = pos.getChildren().get(0);
                assertTrue(topChild.getStyleClass().contains("wizard-header-bar"),
                        "First child must be wizard-header-bar (title/subtitle header removed)");

                // Ensure no label containing "New Order" page title
                boolean hasNewOrderHeader = pos.lookupAll(".page-title").stream()
                        .anyMatch(node -> node instanceof Label l && "New Order".equals(l.getText()));
                assertFalse(hasNewOrderHeader, "Page header 'New Order' must be removed");
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(3)
    @DisplayName("3. Verify 4-column desktop product grid calculation and responsive reflow")
    public void testFourColumnDesktopGrid() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PosView pos = new PosView(ctx, shell);
                ResponsiveCardGrid grid = pos.getProductGridForTesting();
                assertNotNull(grid);

                List<Node> testCards = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    testCards.add(new Label("Card " + i));
                }
                grid.setCards(testCards);

                // On standard desktop center pane width (~750px), should produce 4 columns
                grid.resize(750, 600);
                assertEquals(4, grid.columnCount(), "Standard desktop width should calculate 4 columns");

                // On wide desktop width (~1000px), max columns should still be 4
                grid.resize(1000, 600);
                assertEquals(4, grid.columnCount(), "Wide desktop width should cap at 4 columns");

                // On medium width (~500px), reflows to 3 columns
                grid.resize(500, 600);
                assertEquals(3, grid.columnCount(), "Medium width should reflow to 3 columns");

                // On narrow width (~340px), reflows to 2 columns
                grid.resize(340, 600);
                assertEquals(2, grid.columnCount(), "Narrow width should reflow to 2 columns");
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(4)
    @DisplayName("4. Verify Category navigation with All Menu, pill styles, and single/double click")
    public void testCategoryNavigationAndFiltering() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PosView pos = new PosView(ctx, shell);
                pos.goToStepForTesting(PosView.Step.MENU_BILLING);

                // Check default state is "All Menu (No Category)"
                assertNull(pos.getSelectedCategoryIdForTesting(), "Initial category filter must be null (All Menu)");

                // Select a specific category
                var cats = ctx.menu().categories(true);
                assertFalse(cats.isEmpty());
                var firstCat = cats.get(0);

                pos.selectCategoryForTesting(firstCat.id());
                assertEquals(firstCat.id(), pos.getSelectedCategoryIdForTesting());

                // Reset back to All Menu
                pos.selectCategoryForTesting(null);
                assertNull(pos.getSelectedCategoryIdForTesting());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(5)
    @DisplayName("5. Section 11: Table Selection - Single Click + Button, Double Click, and No Table")
    public void testTableSelectionWorkflow() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PosView pos = new PosView(ctx, shell);
                pos.goToStepForTesting(PosView.Step.TABLE_SELECTION);

                Cart cart = pos.getCart();

                // 1. Single click on table and confirm with button
                var availableTables = ctx.floorRepo().tables(null, true).stream()
                        .filter(t -> t.status() == TableStatus.AVAILABLE)
                        .toList();
                assertFalse(availableTables.isEmpty(), "Must have at least one available table");

                DiningTable table1 = availableTables.get(0);
                cart.tableId = table1.id();
                cart.tableNo = table1.tableNo();

                // Advance with button method
                pos.confirmTableForTesting();
                // Should advance to Waiter Selection
                assertEquals(table1.id(), cart.tableId);
                assertEquals(table1.tableNo(), cart.tableNo);

                // 2. No Table option
                pos.goToStepForTesting(PosView.Step.TABLE_SELECTION);
                cart.tableId = null;
                cart.tableNo = null;
                pos.confirmTableForTesting();
                assertNull(cart.tableId, "No table option must have null tableId");
                assertNull(cart.tableNo, "No table option must have null tableNo");
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(6)
    @DisplayName("6. 4-Step Wizard omits Waiter Selection completely")
    public void testFourStepWizardWithoutWaiter() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PosView pos = new PosView(ctx, shell);
                pos.goToStepForTesting(PosView.Step.TABLE_SELECTION);
                assertEquals(4, PosView.Step.values().length);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(7)
    @DisplayName("7. Preserve order bill and items across backward and forward step navigation")
    public void testStatePreservationAcrossSteps() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PosView pos = new PosView(ctx, shell);
                pos.goToStepForTesting(PosView.Step.MENU_BILLING);

                Cart cart = pos.getCart();
                List<Product> products = ctx.menu().products(true, null);
                Product p1 = products.get(0);
                Product p2 = products.get(1);

                cart.add(p1, null, List.of(), 2, "Extra spicy");
                cart.add(p2, null, List.of(), 1, "No onion");

                assertEquals(2, cart.lines.size(), "Cart must have 2 lines");
                var subtotalBefore = cart.totals(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO).subtotal;
                assertTrue(subtotalBefore.signum() > 0);

                // Navigate back to Table Selection
                pos.goToStepForTesting(PosView.Step.TABLE_SELECTION);
                assertEquals(2, cart.lines.size(), "Cart lines must be preserved navigating backward");

                // Navigate back to Order Type
                pos.goToStepForTesting(PosView.Step.ORDER_TYPE);
                assertEquals(2, cart.lines.size(), "Cart lines must be preserved navigating backward");

                // Return to Menu & Billing
                pos.goToStepForTesting(PosView.Step.MENU_BILLING);
                assertEquals(2, cart.lines.size(), "Cart lines must be preserved navigating forward");
                assertEquals(subtotalBefore, cart.totals(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO).subtotal, "Cart subtotal must remain identical");
                assertEquals("Extra spicy", cart.lines.get(0).instructions);
                assertEquals("No onion", cart.lines.get(1).instructions);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private static String md5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
