package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.Role;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Inventory.Expense;
import com.restaurantpos.model.Inventory.ExpenseCategory;
import com.restaurantpos.util.Money;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpensePanelAccessTest {
    private static Path tempDir;
    private static AppContext ctx;

    @BeforeAll
    static void setUp() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-expense-access-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
    }

    @AfterAll
    static void tearDown() {
        if (ctx != null && ctx.session().active()) {
            ctx.auth().logout();
        }
        AppContext.resetForTesting();
    }

    @Test
    void test_adminAndCashierCanAddExpenses_and_roleGuardsPreserved() {
        LocalDate today = LocalDate.now();
        List<ExpenseCategory> categories = ctx.expenses().categories();
        assertFalse(categories.isEmpty(), "Seeded expense categories must exist");
        long categoryId = categories.get(0).id();

        // 1. Admin / Owner adds an expense
        ctx.auth().login("food_admin@gmail.com", "admin@1122");
        assertEquals(Role.OWNER, ctx.session().role());
        long adminExpenseId = ctx.expenses().add(
                categoryId,
                new BigDecimal("1500.00"),
                today,
                "Cash",
                "Admin generator diesel",
                "Restaurant Owner",
                "",
                ctx.session()
        );
        assertTrue(adminExpenseId > 0);
        ctx.auth().logout();

        // 2. Cashier adds an expense
        ctx.auth().login("food_cashier@gmail.com", "cashier@1122");
        assertEquals(Role.CASHIER, ctx.session().role());
        long cashierExpenseId = ctx.expenses().add(
                categoryId,
                new BigDecimal("450.00"),
                today,
                "Cash",
                "Counter ice and drinking water",
                "",
                "",
                ctx.session()
        );
        assertTrue(cashierExpenseId > 0);

        // Cashier still cannot edit, delete, or add expense categories
        assertThrows(AppException.class, () ->
                ctx.expenses().update(cashierExpenseId, categoryId, new BigDecimal("500.00"), today,
                        "Cash", "Tamper", "Front Cashier", "APPROVED", ctx.session()));
        assertThrows(AppException.class, () -> ctx.expenses().delete(cashierExpenseId, ctx.session()));
        assertThrows(AppException.class, () -> ctx.expenses().saveCategory("Unauthorized Cat", ctx.session()));
        ctx.auth().logout();

        // 3. Restart app & verify both expenses persisted and sum to 1950.00
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();

        BigDecimal totalToday = ctx.expenses().total(today, today);
        assertEquals(0, new BigDecimal("1950.00").compareTo(totalToday),
                "Both Admin and Cashier expenses must be included in today's total");

        List<Expense> list = ctx.expenses().list(today, today, null);
        assertEquals(2, list.size());
        Expense cashierSaved = list.stream().filter(e -> e.id() == cashierExpenseId).findFirst().orElseThrow();
        assertEquals("Front Cashier", cashierSaved.responsible(), "Blank responsible should default to cashier's full name");
        assertEquals("APPROVED", cashierSaved.status());
        assertEquals("Counter ice and drinking water", cashierSaved.description());
        assertEquals(0, new BigDecimal("450.00").compareTo(cashierSaved.amount()));
    }
}
