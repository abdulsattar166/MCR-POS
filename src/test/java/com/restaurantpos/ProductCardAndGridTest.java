package com.restaurantpos;

import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.ui.components.ProductCard;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ProductCardAndGridTest {

    @BeforeAll
    public static void initJavaFX() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException e) {
            // Already initialized
            latch.countDown();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private Product sampleProduct(String sku, String name, String imagePath) {
        return new Product(
                1L,
                1L,
                "Burgers",
                sku,
                name,
                "Description for " + name,
                imagePath,
                BigDecimal.valueOf(200),
                BigDecimal.valueOf(350),
                null,
                10,
                "Grill",
                true,
                BigDecimal.ZERO,
                true,
                true
        );
    }

    @Test
    public void testProductCardStructureAndConstraints() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Product p = sampleProduct("BUR-002", "Beef Supreme Burger", "food/bur-002.png");
                ProductCard card = new ProductCard(p, true, () -> {});

                assertTrue(card.getStyleClass().contains("product-card"), "Should have product-card style class");
                assertEquals(0, card.getSpacing(), "Card should have 0 spacing between image and details");

                // Check image container
                StackPane imgContainer = (StackPane) card.getChildren().get(0);
                assertEquals(ProductCard.IMAGE_HEIGHT, imgContainer.getPrefHeight(), 0.01);
                assertEquals(ProductCard.IMAGE_HEIGHT, imgContainer.getMinHeight(), 0.01);
                assertEquals(ProductCard.IMAGE_HEIGHT, imgContainer.getMaxHeight(), 0.01);
                assertEquals(Double.MAX_VALUE, imgContainer.getMaxWidth(), 0.01);

                // Clip check
                assertNotNull(imgContainer.getClip(), "Image container must have a clip");
                assertTrue(imgContainer.getClip() instanceof Rectangle, "Clip must be a Rectangle");
                Rectangle clip = (Rectangle) imgContainer.getClip();
                assertTrue(clip.widthProperty().isBound(), "Clip width must be bound to container width");
                assertTrue(clip.heightProperty().isBound(), "Clip height must be bound to container height");

                // ImageView check
                assertTrue(imgContainer.getChildren().get(0) instanceof ImageView, "Should contain ImageView when image file exists");
                ImageView iv = (ImageView) imgContainer.getChildren().get(0);
                assertNotNull(iv.getImage());
                assertFalse(iv.getImage().isError(), "Image must not have error");

                // Details area check
                VBox details = (VBox) card.getChildren().get(1);
                assertTrue(details.getStyleClass().contains("product-card-details"));
                assertEquals(3, details.getChildren().size(), "Details should contain name, price, and badge");

                Label name = (Label) details.getChildren().get(0);
                assertEquals("Beef Supreme Burger", name.getText());
                assertTrue(name.isWrapText(), "Product name must wrap text");

                Label price = (Label) details.getChildren().get(1);
                assertTrue(price.getStyleClass().contains("product-price"));

                Label badge = (Label) details.getChildren().get(2);
                assertEquals("Sizes/Options", badge.getText());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testProductCardWithMissingOrInvalidImage() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Product p = sampleProduct("NON-EXIST", "Missing Image Item", "food/non_existent.png");
                ProductCard card = new ProductCard(p, false, null);

                StackPane imgContainer = (StackPane) card.getChildren().get(0);
                assertNotNull(imgContainer);
                assertFalse(imgContainer.getChildren().isEmpty());

                Label fallback = (Label) imgContainer.getChildren().get(0);
                assertEquals("M", fallback.getText()); // First letter of 'Missing'
                assertTrue(fallback.getStyleClass().contains("image-fallback"));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResponsiveCardGridColumnCalculation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ResponsiveCardGrid grid = new ResponsiveCardGrid(12, 165, 2, 3);
                List<ProductCard> cards = new ArrayList<>();
                for (int i = 1; i <= 6; i++) {
                    Product p = sampleProduct("SKU-" + i, "Burger " + i, "food/bur-001.png");
                    cards.add(new ProductCard(p, false, null));
                }
                grid.setCards(cards);

                // Simulate narrow width (~360px) -> should be 2 columns
                Scene scene = new Scene(grid, 360, 600);
                grid.resize(360, 600);
                int colsNarrow = grid.columnCount();
                assertTrue(colsNarrow >= 2 && colsNarrow <= 3, "Columns must be between 2 and 3");

                // Simulate wide width (~650px) -> should be 3 columns
                grid.resize(650, 600);
                int colsWide = grid.columnCount();
                assertEquals(3, colsWide, "Wide width should produce 3 columns");
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDifferentImageDimensionsAndAspectRatios() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                String[] testImages = {
                        "food/bur-001.png", // 640x420
                        "food/bur-002.png", // Beef Supreme Burger
                        "food/bur-003.png", // Chicken Patty Burger
                        "food/bur-004.png", // Double Zinger Burger
                        "food/food_1792832337347_1792832337349.jpg", // 481x360
                        null,
                        ""
                };

                for (String imgPath : testImages) {
                    Product p = sampleProduct("SKU-TEST", "Very Long Product Name That Definitely Wraps Across Multiple Lines", imgPath);
                    ProductCard card = new ProductCard(p, true, null);

                    // Test layout sizing at fixed card width
                    double testCardWidth = 195.0;
                    card.resize(testCardWidth, 240);

                    StackPane imgContainer = (StackPane) card.getChildren().get(0);
                    assertEquals(ProductCard.IMAGE_HEIGHT, imgContainer.getPrefHeight(), 0.01);
                    assertEquals(ProductCard.IMAGE_HEIGHT, imgContainer.getMinHeight(), 0.01);
                    assertEquals(ProductCard.IMAGE_HEIGHT, imgContainer.getMaxHeight(), 0.01);

                    // Container width must follow card width and never be hardcoded or overflow
                    assertEquals(Double.MAX_VALUE, imgContainer.getMaxWidth(), 0.01);
                    assertEquals(0, imgContainer.getMinWidth(), 0.01);

                    // Clip must exist and be bound
                    Rectangle clip = (Rectangle) imgContainer.getClip();
                    assertNotNull(clip);
                    assertTrue(clip.widthProperty().isBound());
                    assertTrue(clip.heightProperty().isBound());

                    // Check labels below image
                    VBox details = (VBox) card.getChildren().get(1);
                    Label name = (Label) details.getChildren().get(0);
                    assertTrue(name.isWrapText());
                    assertEquals("Very Long Product Name That Definitely Wraps Across Multiple Lines", name.getText());

                    Label price = (Label) details.getChildren().get(1);
                    assertNotNull(price.getText());
                    assertTrue(price.getText().length() > 0);

                    Label badge = (Label) details.getChildren().get(2);
                    assertEquals("Sizes/Options", badge.getText());
                }
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
