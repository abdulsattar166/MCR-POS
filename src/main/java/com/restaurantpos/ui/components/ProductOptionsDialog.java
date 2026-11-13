package com.restaurantpos.ui.components;

import com.restaurantpos.model.Catalog.AddOn;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ProductOptionsDialog extends Dialog<ProductOptionsDialog.Choice> {
    public record Choice(int qty, Size size, List<AddOn> addons, String instructions) {}

    private final Product product;
    private int qty = 1;
    private final List<CheckBox> addonBoxes = new ArrayList<>();
    private final ComboBox<Size> sizeBox = new ComboBox<>();
    private final Label total = Ui.label("", "total-amount");
    private final TextArea notes = Ui.textArea("Special instructions (optional)", 2);
    private final Label warningLabel = Ui.label("", "inline-error");
    private ButtonType addBtnType;

    public ProductOptionsDialog(Product product, List<Size> sizes, List<AddOn> addons, Window owner) {
        this.product = product;
        initOwner(owner);
        setTitle(product.name());
        setHeaderText(null);
        setResizable(true);

        HBox top = new HBox(14);
        top.setAlignment(Pos.TOP_LEFT);
        StackPane image = Ui.imagePane(product.imagePath(), 140, 100, product.name());
        VBox info = new VBox(4, Ui.h3(product.name()), Ui.muted(product.description() == null ? "" : product.description()),
                Ui.label(Money.fmt(product.effectivePrice()), "product-price"));
        info.setPrefWidth(330);
        ((Label) info.getChildren().get(1)).setWrapText(true);
        top.getChildren().addAll(image, info);

        VBox form = new VBox(10);
        if (!sizes.isEmpty()) {
            sizeBox.getItems().setAll(sizes);
            sizeBox.getSelectionModel().selectFirst();
            sizeBox.setMaxWidth(Double.MAX_VALUE);
            sizeBox.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(Size s) {
                    if (s == null) return "";
                    if (s.isRanged()) {
                        if (!s.isConfigured()) {
                            return s.name() + " [Range: " + Money.fmt(s.minPrice()) + " – " + Money.fmt(s.maxPrice()) + "] (Price not configured)";
                        } else {
                            return s.name() + " — " + Money.fmt(s.price());
                        }
                    }
                    if (s.price() != null) {
                        return s.name() + " — " + Money.fmt(s.price());
                    }
                    return s.name() + (s.extraPrice().signum() > 0 ? "  +" + Money.fmt(s.extraPrice()) : "");
                }
                @Override public Size fromString(String s) { return null; }
            });
            sizeBox.valueProperty().addListener((o, a, b) -> recalc());
            form.getChildren().add(Ui.field("Select Portion / Size", sizeBox));
        }
        if (!addons.isEmpty()) {
            FlowPane addonPane = new FlowPane(10, 7);
            for (AddOn a : addons) {
                CheckBox cb = new CheckBox(a.name() + "  +" + Money.fmt(a.price()));
                cb.setUserData(a);
                cb.selectedProperty().addListener((o, x, y) -> recalc());
                addonBoxes.add(cb);
                addonPane.getChildren().add(cb);
            }
            form.getChildren().add(Ui.field("Add-ons", addonPane));
        }
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);
        warningLabel.setWrapText(true);
        form.getChildren().addAll(Ui.field("Instructions", notes), warningLabel);

        HBox qtyRow = new HBox(10);
        qtyRow.setAlignment(Pos.CENTER_LEFT);
        Button minus = new Button("−");
        Button plus = new Button("+");
        Label qtyLabel = Ui.label("1", "qty-label");
        minus.getStyleClass().add("qty-button");
        plus.getStyleClass().add("qty-button");
        minus.setOnAction(e -> { qty = Math.max(1, qty - 1); qtyLabel.setText(String.valueOf(qty)); recalc(); });
        plus.setOnAction(e -> { qty = Math.min(99, qty + 1); qtyLabel.setText(String.valueOf(qty)); recalc(); });
        qtyRow.getChildren().addAll(Ui.fieldLabel("Quantity"), minus, qtyLabel, plus, Ui.spacer(), Ui.muted("Line total"), total);

        VBox content = new VBox(16, top, new Separator(), form, qtyRow);
        content.setPadding(new Insets(12, 10, 12, 10));
        content.setPrefWidth(500);

        ScrollPane contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(false);
        contentScroll.setPannable(true);
        contentScroll.getStyleClass().add("edge-to-edge");
        var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double screenH = screenBounds != null ? screenBounds.getHeight() : 768;
        double maxDialogHeight = Math.max(340, Math.min(750, screenH - 120));
        contentScroll.setMaxHeight(maxDialogHeight);
        contentScroll.setPrefHeight(Math.min(maxDialogHeight, 560));

        getDialogPane().setContent(contentScroll);
        addBtnType = new ButtonType("Add to order", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, addBtnType);
        if (getDialogPane().getScene() != null) Ui.applyStyles(getDialogPane().getScene());
        else setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));
        getDialogPane().getStyleClass().add("app-dialog");
        setResultConverter(b -> b == addBtnType ? new Choice(qty,
                sizeBox.getValue(), selectedAddons(), notes.getText()) : null);
        setOnShown(e -> {
            Animations.pop(getDialogPane());
            recalc();
        });
        recalc();
    }

    private List<AddOn> selectedAddons() {
        List<AddOn> list = new ArrayList<>();
        for (CheckBox cb : addonBoxes) if (cb.isSelected()) list.add((AddOn) cb.getUserData());
        return list;
    }

    private void recalc() {
        Size s = sizeBox.getValue();
        boolean priceUnconfigured = s != null && s.isRanged() && !s.isConfigured();
        Node addBtn = addBtnType != null ? getDialogPane().lookupButton(addBtnType) : null;

        if (priceUnconfigured) {
            warningLabel.setText("Price not configured for " + s.name() + " (Range: "
                    + Money.fmt(s.minPrice()) + " – " + Money.fmt(s.maxPrice())
                    + "). An administrator must configure the selling price in Menu Management before this portion can be added to an order.");
            warningLabel.setVisible(true);
            warningLabel.setManaged(true);
            total.setText("Price not configured");
            if (addBtn != null) addBtn.setDisable(true);
            return;
        }

        warningLabel.setVisible(false);
        warningLabel.setManaged(false);
        if (addBtn != null) addBtn.setDisable(false);

        BigDecimal unit = product.effectivePrice();
        if (s != null) {
            BigDecimal eff = s.effectivePrice(product.effectivePrice());
            if (eff != null) unit = eff;
        }
        for (AddOn a : selectedAddons()) unit = unit.add(a.price());
        total.setText(Money.fmt(unit.multiply(BigDecimal.valueOf(qty))));
    }
}
