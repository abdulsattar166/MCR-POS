package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Inventory.*;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InventoryAndPurchaseFlowTest {
    private static AppContext ctx;
    private static long supplierId;
    private static long poId;
    private static long testIngredientId;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        Path temp = Files.createTempDirectory("restaurant-pos-purchasing-test-");
        System.setProperty("pos.home", temp.toString());
        AppPaths.overrideRoot(temp);
        Database.use(temp.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
    }

    @Test
    @Order(1)
    void createAndEditPurchaseOrder() {
        Supplier supplier = new Supplier(0, "Apex Foods Supply", "John Supplier", "03001234567",
                "apex@supplier.com", "Industrial Estate", "Net 30", true);
        supplierId = ctx.purchasing().saveSupplier(supplier, ctx.session());
        assertTrue(supplierId > 0);

        List<Ingredient> ingredients = ctx.inventory().ingredients(true);
        assertFalse(ingredients.isEmpty());
        Ingredient ing = ingredients.get(0);

        Map<Long, BigDecimal[]> lines = new LinkedHashMap<>();
        lines.put(ing.id(), new BigDecimal[]{new BigDecimal("10.0"), new BigDecimal("150.00")});

        poId = ctx.purchasing().createPurchaseOrder(supplierId, LocalDate.now().plusDays(3),
                new BigDecimal("50.00"), "First test order", lines, ctx.session());
        assertTrue(poId > 0);

        PurchaseOrder created = ctx.purchasing().orders().stream()
                .filter(p -> p.id() == poId).findFirst().orElseThrow();
        assertEquals("DRAFT", created.status());
        assertEquals(supplierId, created.supplierId());
        assertEquals(0, new BigDecimal("1550.00").compareTo(created.total()));

        // Update purchase order
        Map<Long, BigDecimal[]> updatedLines = new LinkedHashMap<>();
        updatedLines.put(ing.id(), new BigDecimal[]{new BigDecimal("20.0"), new BigDecimal("150.00")});
        ctx.purchasing().updatePurchaseOrder(poId, supplierId, LocalDate.now().plusDays(4),
                new BigDecimal("100.00"), "Updated test order", updatedLines, ctx.session());

        PurchaseOrder updated = ctx.purchasing().orders().stream()
                .filter(p -> p.id() == poId).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3100.00").compareTo(updated.total()));
        assertEquals("Updated test order", updated.notes());
    }

    @Test
    @Order(2)
    void approveReceiveAndSupplierPayment() {
        ctx.purchasing().approve(poId, ctx.session());
        PurchaseOrder approved = ctx.purchasing().orders().stream()
                .filter(p -> p.id() == poId).findFirst().orElseThrow();
        assertEquals("APPROVED", approved.status());

        // Check outstanding balance
        BigDecimal outstanding = ctx.purchasing().outstanding(supplierId);
        assertEquals(0, approved.total().compareTo(outstanding));

        // Receive purchase order
        ctx.purchasing().receive(poId, "Received at dock", ctx.session());
        PurchaseOrder received = ctx.purchasing().orders().stream()
                .filter(p -> p.id() == poId).findFirst().orElseThrow();
        assertEquals("RECEIVED", received.status());

        // Supplier payment (testing the column 'ref')
        ctx.purchasing().addPayment(supplierId, poId, new BigDecimal("1000.00"), "CASH", "TRX-9988", ctx.session());
        BigDecimal outstandingAfterPay = ctx.purchasing().outstanding(supplierId);
        assertEquals(0, approved.total().subtract(new BigDecimal("1000.00")).compareTo(outstandingAfterPay));

        List<Object[]> payments = ctx.purchasing().payments(supplierId);
        assertFalse(payments.isEmpty());
        long paymentId = (Long) payments.get(0)[0];

        // Delete supplier payment
        ctx.purchasing().deletePayment(paymentId, ctx.session());
        BigDecimal outstandingAfterDelete = ctx.purchasing().outstanding(supplierId);
        assertEquals(0, approved.total().compareTo(outstandingAfterDelete));
    }

    @Test
    @Order(3)
    void ingredientHasRecipesAndDeletionProtection() {
        // Find existing ingredient used in recipes
        Ingredient chicken = ctx.inventory().ingredients(true).stream()
                .filter(i -> i.name().equals("Chicken Fillet")).findFirst().orElseThrow();
        assertTrue(ctx.inventory().ingredientHasRecipes(chicken.id()));
        assertThrows(AppException.class, () -> ctx.inventory().deleteIngredient(chicken.id(), ctx.session()));

        // Create independent ingredient not in any recipe
        Ingredient testIng = new Ingredient(0, "ING-TEST-999", "Test Sauce", "kg",
                new BigDecimal("5.0"), new BigDecimal("1.0"), new BigDecimal("20.0"),
                new BigDecimal("80.0"), true);
        testIngredientId = ctx.inventory().saveIngredient(testIng, ctx.session());
        assertTrue(testIngredientId > 0);
        assertFalse(ctx.inventory().ingredientHasRecipes(testIngredientId));

        // Delete test ingredient
        ctx.inventory().deleteIngredient(testIngredientId, ctx.session());
        assertNull(ctx.inventoryRepo().ingredient(testIngredientId));
    }

    @Test
    @Order(4)
    void productDeletionProtection() {
        // Existing product used in recipes/orders
        Product zinger = ctx.menu().sellable().stream()
                .filter(p -> p.name().equals("Zinger Burger")).findFirst().orElseThrow();
        // Zinger was ordered or has orders
        if (ctx.menuRepo().productHasOrders(zinger.id())) {
            assertThrows(AppException.class, () -> ctx.menu().deleteProduct(zinger.id(), ctx.session()));
        }

        // Create a standalone product with no orders
        long categoryId = ctx.menu().categories(true).get(0).id();
        Product dummy = new Product(0, categoryId, "Burgers", "TEST-PRD-1", "Temp Burger",
                "Testing deletion", null, new BigDecimal("100"), new BigDecimal("200"),
                null, 10, "Kitchen", true, Money.ZERO, false, true);
        long dummyId = ctx.menu().saveProduct(dummy, ctx.session());
        assertTrue(dummyId > 0);

        // Delete standalone product
        assertDoesNotThrow(() -> ctx.menu().deleteProduct(dummyId, ctx.session()));
        assertNull(ctx.menu().product(dummyId));
    }
}
