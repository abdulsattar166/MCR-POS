package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.enums.*;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.service.Cart;
import com.restaurantpos.service.PaymentService.PaymentInput;
import com.restaurantpos.ui.AppWindow;
import com.restaurantpos.ui.MainShell;
import com.restaurantpos.ui.components.ConfirmPaymentDialog;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.ui.views.OrdersView;
import com.restaurantpos.ui.views.PaymentsView;
import com.restaurantpos.ui.views.PosView;
import com.restaurantpos.util.Money;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
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
public class ScrollingAndResponsiveUITest {

    private static AppContext ctx;
    private static Path tempDir;
    private static Stage dummyStage;

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
        tempDir = Files.createTempDirectory("restaurant-pos-responsive-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();

        // Login cashier
        ctx.auth().login("food_cashier@gmail.com", "cashier@1122");
        ctx.shifts().open(Money.of("5000"), ctx.session());

        CountDownLatch stageLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            dummyStage = new Stage();
            Scene sc = new Scene(new StackPane(), 1024, 700);
            Ui.applyStyles(sc);
            dummyStage.setScene(sc);
            stageLatch.countDown();
        });
        assertTrue(stageLatch.await(5, TimeUnit.SECONDS));
    }

    @AfterAll
    public static void teardown() throws Exception {
        if (ctx != null && ctx.session().active()) {
            ctx.auth().logout();
        }
        CountDownLatch stageLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            if (dummyStage != null) {
                dummyStage.close();
            }
            stageLatch.countDown();
        });
        stageLatch.await(5, TimeUnit.SECONDS);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Verify PosView Delivery Customer Details ScrollPane and State Preservation")
    void testDeliveryCustomerDetailsScrollingAndStatePreservation() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                AppWindow appWin = new AppWindow(dummyStage);
                MainShell shell = new MainShell(appWin, ctx);
                PosView posView = new PosView(ctx, shell);
                posView.selectOrderTypeForTesting(OrderType.DELIVERY);

                // Check that stepContentPane contains a vertical ScrollPane on Step 1
                StackPane stepContent = null;
                for (Node child : posView.getChildren()) {
                    if (child instanceof StackPane sp && sp != posView.lookup(".wizard-header-bar")) {
                        stepContent = sp;
                        break;
                    }
                }
                assertNotNull(stepContent, "Step content pane must be present");
                assertFalse(stepContent.getChildren().isEmpty(), "Step 1 should be rendered");

                Node step1Node = stepContent.getChildren().get(0);
                assertTrue(step1Node instanceof VBox, "Step 1 root should be a VBox");
                VBox step1VBox = (VBox) step1Node;

                // Find the ScrollPane inside Step 1
                ScrollPane step1Scroll = null;
                for (Node n : step1VBox.getChildren()) {
                    if (n instanceof ScrollPane sp) {
                        step1Scroll = sp;
                        break;
                    }
                }
                assertNotNull(step1Scroll, "Step 1 must have a properly configured ScrollPane");
                assertTrue(step1Scroll.isFitToWidth(), "Step 1 ScrollPane must have fitToWidth = true");
                assertFalse(step1Scroll.isFitToHeight(), "Step 1 ScrollPane must not force fitToHeight = true so content can expand vertically");
                assertTrue(step1Scroll.isPannable(), "Step 1 ScrollPane should be pannable");

                // Locate customer details fields
                TextField nameField = null;
                TextField phoneField = null;
                TextArea addrArea = null;
                TextArea instrArea = null;
                TextArea notesArea = null;
                TextField chargeField = null;

                Node scrollContent = step1Scroll.getContent();
                assertTrue(scrollContent instanceof VBox, "Scroll content should be a VBox");
                VBox formContainer = (VBox) scrollContent;

                for (Node n : formContainer.getChildren()) {
                    if (n instanceof VBox vbox && vbox.getStyleClass().contains("card")) {
                        // This is deliveryDetailsBox
                        for (Node fieldBox : vbox.getChildren()) {
                            if (fieldBox instanceof VBox vb && vb.getChildren().size() >= 2) {
                                Node input = vb.getChildren().get(1);
                                if (input instanceof TextField tf) {
                                    if ("Customer Name".equals(tf.getPromptText())) nameField = tf;
                                    else if ("Customer Phone".equals(tf.getPromptText())) phoneField = tf;
                                    else if ("Delivery Charge (PKR)".equals(tf.getPromptText())) chargeField = tf;
                                } else if (input instanceof TextArea ta) {
                                    if (ta.getPromptText().contains("Delivery Address")) addrArea = ta;
                                    else if (ta.getPromptText().contains("Delivery Instructions")) instrArea = ta;
                                    else if (ta.getPromptText().contains("Customer Notes")) notesArea = ta;
                                }
                            }
                        }
                    }
                }

                assertNotNull(nameField, "Customer Name field must be present");
                assertNotNull(phoneField, "Customer Phone field must be present");
                assertNotNull(addrArea, "Delivery Address area must be present");
                assertNotNull(instrArea, "Delivery Instructions area must be present");
                assertNotNull(notesArea, "Customer Notes area must be present");
                assertNotNull(chargeField, "Delivery Charge field must be present");

                // Enter customer details
                nameField.setText("Muhammad Bilal");
                phoneField.setText("03001234567");
                addrArea.setText("House 12, Street 5, Gulberg III, Lahore");
                instrArea.setText("Ring the bell twice, near Main Market");
                notesArea.setText("Please deliver hot and include extra napkins");
                chargeField.setText("250.00");

                // Validate that values were synchronized to cart
                Cart cart = posView.getCart();
                assertEquals("Muhammad Bilal", cart.customerName);
                assertEquals("03001234567", cart.customerPhone);
                assertEquals("House 12, Street 5, Gulberg III, Lahore", cart.customerAddress);
                assertTrue(cart.notes.contains("Ring the bell twice"));
                assertTrue(cart.notes.contains("Please deliver hot"));
                assertEquals(new BigDecimal("250.00"), cart.deliveryCharge);

                // Navigate to next steps and return to step 1
                posView.goToStepForTesting(PosView.Step.TABLE_SELECTION);
                posView.goToStepForTesting(PosView.Step.MENU_BILLING);
                posView.goToStepForTesting(PosView.Step.ORDER_TYPE);

                // Verify fields retain exact values after navigating back
                assertEquals("Muhammad Bilal", nameField.getText());
                assertEquals("03001234567", phoneField.getText());
                assertEquals("House 12, Street 5, Gulberg III, Lahore", addrArea.getText());
                assertEquals("Ring the bell twice, near Main Market", instrArea.getText());
                assertEquals("Please deliver hot and include extra napkins", notesArea.getText());

            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Verify ConfirmPaymentDialog Vertical ScrollPane and Action Button Reachability")
    void testConfirmPaymentScrollingAndReachability() throws Exception {
        // Create an order with several items
        Product burger = ctx.menu().products(true, null).stream()
                .filter(p -> p.name().contains("Zinger")).findFirst().orElseThrow();
        Product fries = ctx.menu().products(true, null).stream()
                .filter(p -> p.name().contains("Fries")).findFirst().orElseThrow();

        Cart cart = new Cart();
        cart.type = OrderType.TAKEAWAY;
        cart.add(burger, (Size) null, List.of(), 3, "No mayo");
        cart.add(fries, (Size) null, List.of(), 2, "Extra spicy");
        long orderId = ctx.orders().createOrder(cart, false, ctx.session());
        Order order = ctx.orders().find(orderId);
        assertNotNull(order);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                ConfirmPaymentDialog dialog = new ConfirmPaymentDialog(ctx, order, dummyStage, result -> {});
                DialogPane pane = dialog.getDialogPane();
                assertNotNull(pane);

                // Dialog content must be a ScrollPane
                Node contentNode = pane.getContent();
                assertTrue(contentNode instanceof ScrollPane, "ConfirmPaymentDialog content must be wrapped in a ScrollPane");
                ScrollPane sp = (ScrollPane) contentNode;
                assertTrue(sp.isFitToWidth(), "ScrollPane must fit to width");
                assertFalse(sp.isFitToHeight(), "ScrollPane fitToHeight must be false to permit scrolling");
                assertTrue(sp.isPannable(), "ScrollPane should be pannable");

                // Button types check: OK_DONE (Confirm Payment) and CANCEL must be present in the DialogPane button bar
                assertFalse(pane.getButtonTypes().isEmpty(), "Dialog buttons must not be empty");
                boolean hasConfirm = pane.getButtonTypes().stream()
                        .anyMatch(bt -> bt.getButtonData() == ButtonBar.ButtonData.OK_DONE);
                boolean hasCancel = pane.getButtonTypes().stream()
                        .anyMatch(bt -> bt.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE);
                assertTrue(hasConfirm, "Confirm Payment action button must be present in dialog button bar");
                assertTrue(hasCancel, "Cancel action button must be present in dialog button bar");

                // Button is NOT inside the scrollpane (so it is never pushed away or clipped)
                Button okBtn = (Button) pane.lookupButton(pane.getButtonTypes().stream()
                        .filter(bt -> bt.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().get());
                assertNotNull(okBtn);
                assertFalse(sp.getContent().lookupAll(".button").contains(okBtn),
                        "Confirm button must remain fixed in the DialogPane button bar, outside scrollable content");

            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Verify Layout across Representative Desktop Screen Sizes")
    void testRepresentativeDesktopScreenSizes() throws Exception {
        double[][] resolutions = new double[][]{
                {1024, 600},
                {1280, 720},
                {1366, 768},
                {1440, 900},
                {1600, 900},
                {1920, 1080}
        };

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                AppWindow appWin = new AppWindow(dummyStage);
                MainShell shell = new MainShell(appWin, ctx);
                Scene scene = new Scene(shell, 1024, 600);
                dummyStage.setScene(scene);

                for (double[] res : resolutions) {
                    double width = res[0];
                    double height = res[1];

                    dummyStage.setWidth(width);
                    dummyStage.setHeight(height);
                    scene.setRoot(shell);
                    shell.applyCss();
                    shell.layout();

                    // Navigate to key views to ensure they render cleanly without layout exceptions
                    shell.navigate("pos");
                    shell.layout();

                    shell.navigate("orders");
                    shell.layout();

                    shell.navigate("shift");
                    shell.layout();

                    shell.navigate("settings");
                    shell.layout();

                    shell.navigate("backup");
                    shell.layout();

                    shell.navigate("payments");
                    shell.layout();
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Verify Payments & Refunds Page Vertical ScrollPane and Tab Table Accessibility")
    void testPaymentsAndRefundsViewScrolling() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                AppWindow appWin = new AppWindow(dummyStage);
                MainShell shell = new MainShell(appWin, ctx);
                PaymentsView pView = new PaymentsView(ctx, shell);
                assertNotNull(pView);

                boolean hasScroll = pView.getChildren().stream().anyMatch(n -> n instanceof ScrollPane);
                assertTrue(hasScroll, "PaymentsView must contain a ScrollPane wrapping filters, stats, and tabs");

                ScrollPane sp = (ScrollPane) pView.getChildren().stream()
                        .filter(n -> n instanceof ScrollPane).findFirst().orElseThrow();
                assertTrue(sp.isFitToWidth(), "ScrollPane must fit to width");
                assertFalse(sp.isFitToHeight(), "ScrollPane must permit vertical scrolling");
                assertTrue(sp.isPannable(), "ScrollPane should be pannable");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
