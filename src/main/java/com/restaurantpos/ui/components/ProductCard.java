package com.restaurantpos.ui.components;

import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Clean, modern product card component for the restaurant POS food menu.
 * Constrains food images within a dedicated, fixed-height, rounded viewport
 * while preserving aspect ratio, preventing any overflow or card overlapping.
 */
public final class ProductCard extends VBox {
    public static final double IMAGE_HEIGHT = 115.0;

    private final Product product;

    public ProductCard(Product product, boolean hasOptions, Runnable onClick) {
        this.product = product;

        getStyleClass().add("product-card");
        if (!product.available()) {
            getStyleClass().add("product-card-unavailable");
        }

        // Card layout & sizing constraints
        setSpacing(0);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setPrefWidth(Region.USE_COMPUTED_SIZE);

        // 1. Dedicated fixed-height image container
        StackPane imageContainer = createImageContainer(product.imagePath(), IMAGE_HEIGHT, product.name());
        VBox.setVgrow(imageContainer, Priority.NEVER);

        // 2. Details area below image with comfortable padding
        VBox details = new VBox(4);
        details.getStyleClass().add("product-card-details");
        details.setPadding(new Insets(8, 10, 10, 10));
        VBox.setVgrow(details, Priority.ALWAYS);

        Label name = Ui.label(product.name(), "product-name");
        name.setWrapText(true);
        name.setMinHeight(34);

        String priceText;
        if (product.effectivePrice() != null && product.effectivePrice().signum() > 0) {
            priceText = Money.fmt(product.effectivePrice());
        } else if (hasOptions) {
            priceText = "Portion Options";
        } else {
            priceText = "Price not configured";
        }
        Label price = Ui.label(priceText, "product-price");

        Label badge = Ui.chip(hasOptions ? "Sizes/Options" : "Regular", hasOptions ? "primary" : "neutral");

        details.getChildren().addAll(name, price, badge);

        getChildren().addAll(imageContainer, details);

        if (onClick != null) {
            setOnMouseClicked(e -> onClick.run());
        }
    }

    public Product product() {
        return product;
    }

    /**
     * Creates a dedicated, fixed-height image container where the food image
     * is proportionally scaled with preserveRatio=true, constrained to container dimensions,
     * clipped to rounded top corners, and gracefully falls back to a clean placeholder.
     */
    public static StackPane createImageContainer(String imagePath, double height, String fallbackText) {
        StackPane container = new StackPane();
        container.getStyleClass().add("image-frame");
        container.setPrefHeight(height);
        container.setMinHeight(height);
        container.setMaxHeight(height);
        container.setMinWidth(0);
        container.setMaxWidth(Double.MAX_VALUE);

        // Dynamic clip ensures image or background never extends outside container boundaries
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(container.widthProperty());
        clip.heightProperty().bind(container.heightProperty());
        container.setClip(clip);

        Label fallback = Ui.label(
                (fallbackText == null || fallbackText.isBlank()) ? "?" : fallbackText.substring(0, 1).toUpperCase(Locale.ROOT),
                "image-fallback"
        );
        fallback.setAlignment(Pos.CENTER);

        Image img = Images.getCachedImage(imagePath, 320, 210);
        if (img == null) {
            container.getChildren().add(fallback);
            return container;
        }

        try {
            ImageView view = new ImageView(img);
            view.setSmooth(true);
            view.setPreserveRatio(true);

            // Bind ImageView dimensions to container to prevent horizontal or vertical overflow
            view.fitWidthProperty().bind(container.widthProperty());
            view.fitHeightProperty().bind(container.heightProperty());

            if (img.isError()) {
                container.getChildren().setAll(fallback);
            } else {
                img.errorProperty().addListener((obs, oldVal, isErr) -> {
                    if (isErr != null && isErr) {
                        Platform.runLater(() -> container.getChildren().setAll(fallback));
                    }
                });
                container.getChildren().add(view);
            }
        } catch (Exception e) {
            container.getChildren().add(fallback);
        }

        return container;
    }
}
