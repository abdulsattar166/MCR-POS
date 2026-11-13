package com.restaurantpos.ui.components;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.model.Catalog.AddOn;
import com.restaurantpos.model.Catalog.Category;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Catalog.Size;
import com.restaurantpos.service.OrderService.DraftItemEdit;
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
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Modal product-selection dialog for adding one or more menu items to an existing DRAFT order
 * without creating a new order or reopening the New Order wizard.
 */
public final class AddDraftItemsDialog extends Dialog<List<DraftItemEdit>> {
    private final AppContext ctx;
    private final TextField searchField = Ui.textField("Search menu item by name or SKU...");
    private final FlowPane categoryBar = new FlowPane(6, 6);
    private final ResponsiveCardGrid productGrid = new ResponsiveCardGrid(185, 12, 12);
    private final VBox stagedList = new VBox(8);
    private final Label stagedSummary = Ui.muted("No items selected yet.");
    private final List<DraftItemEdit> staged = new ArrayList<>();
    private Long selectedCategoryId = null;

    public AddDraftItemsDialog(AppContext ctx, String orderNo, Window owner) {
        this.ctx = ctx;
        initOwner(owner);
        setTitle("Add Items to Draft Order " + orderNo);
        setHeaderText(null);
        setResizable(true);

        buildCategories();
        Ui.bindSearch(searchField, this::loadProducts);

        ScrollPane gridScroll = new ScrollPane(productGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        VBox left = new VBox(10, searchField, categoryBar, gridScroll);
        HBox.setHgrow(left, Priority.ALWAYS);

        ScrollPane stagedScroll = new ScrollPane(stagedList);
        stagedScroll.setFitToWidth(true);
        stagedScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(stagedScroll, Priority.ALWAYS);

        VBox right = new VBox(10, Ui.h3("Items to Add"), stagedSummary, new Separator(), stagedScroll);
        right.getStyleClass().add("card");
        right.setPadding(new Insets(12));
        right.setMinWidth(290);
        right.setPrefWidth(320);

        HBox root = new HBox(14, left, right);
        root.setPrefSize(960, 600);
        root.setPadding(new Insets(10));

        getDialogPane().setContent(root);
        ButtonType addBtnType = new ButtonType("Add to Draft Order", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, addBtnType);

        Button okBtn = (Button) getDialogPane().lookupButton(addBtnType);
        okBtn.getStyleClass().add("primary-button");
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (staged.isEmpty()) {
                Ui.error(getDialogPane().getScene().getWindow(), "No Items Selected",
                        "Select at least one product from the menu before adding to the draft order.");
                ev.consume();
            }
        });

        setResultConverter(bt -> bt == addBtnType ? List.copyOf(staged) : null);
        setOnShown(e -> {
            Ui.applyStyles(getDialogPane().getScene());
            Animations.pop(getDialogPane());
            searchField.requestFocus();
        });

        loadProducts();
        renderStaged();
    }

    private void buildCategories() {
        categoryBar.getChildren().clear();
        Button allBtn = new Button("All Categories");
        allBtn.getStyleClass().add(selectedCategoryId == null ? "primary-button" : "secondary-button");
        allBtn.setOnAction(e -> {
            selectedCategoryId = null;
            buildCategories();
            loadProducts();
        });
        categoryBar.getChildren().add(allBtn);

        for (Category c : ctx.menu().categories(true)) {
            Button b = new Button(c.name());
            boolean active = selectedCategoryId != null && selectedCategoryId == c.id();
            b.getStyleClass().add(active ? "primary-button" : "secondary-button");
            b.setOnAction(e -> {
                selectedCategoryId = c.id();
                buildCategories();
                loadProducts();
            });
            categoryBar.getChildren().add(b);
        }
    }

    private void loadProducts() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<Product> products = ctx.menu().products(true, selectedCategoryId);
        if (!q.isBlank()) {
            products = products.stream()
                    .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(q)
                            || (p.sku() != null && p.sku().toLowerCase(Locale.ROOT).contains(q)))
                    .toList();
        }
        java.util.Map<Long, List<Size>> allSizes = ctx.menu().allActiveSizes();
        java.util.Map<Long, List<AddOn>> allAddOns = ctx.menu().allActiveAddOns();
        List<Node> cards = new ArrayList<>();
        for (Product p : products) {
            boolean hasOptions = !allSizes.getOrDefault(p.id(), List.of()).isEmpty()
                    || !allAddOns.getOrDefault(p.id(), List.of()).isEmpty();
            cards.add(new ProductCard(p, hasOptions, () -> pickProduct(p)));
        }
        productGrid.setCards(cards);
    }

    private void pickProduct(Product p) {
        List<Size> sizes = ctx.menu().sizes(p.id());
        List<AddOn> addons = ctx.menu().addOns(p.id());
        if (sizes.isEmpty() && addons.isEmpty()) {
            addOrMergeStaged(p, null, List.of(), 1, "");
            return;
        }
        ProductOptionsDialog dlg = new ProductOptionsDialog(p, sizes, addons, getDialogPane().getScene().getWindow());
        dlg.showAndWait().ifPresent(choice -> addOrMergeStaged(p, choice.size(), choice.addons(), choice.qty(), choice.instructions()));
    }

    private void addOrMergeStaged(Product p, Size size, List<AddOn> addons, int qty, String instructions) {
        BigDecimal unit = p.effectivePrice();
        StringBuilder detail = new StringBuilder();
        if (size != null) {
            BigDecimal sized = size.effectivePrice(unit);
            unit = sized != null ? sized : unit.add(Money.nz(size.extraPrice()));
            detail.append(size.name());
        }
        if (addons != null && !addons.isEmpty()) {
            for (AddOn a : addons) {
                unit = unit.add(Money.nz(a.price()));
            }
            if (!detail.isEmpty()) detail.append(" · ");
            detail.append(addons.stream().map(AddOn::name).collect(Collectors.joining(", ")));
        }
        String detailStr = detail.toString();
        String instr = instructions == null ? "" : instructions.trim();

        for (int i = 0; i < staged.size(); i++) {
            DraftItemEdit cur = staged.get(i);
            if (java.util.Objects.equals(cur.productId(), p.id())
                    && java.util.Objects.equals(cur.variantId(), size == null ? null : size.id())
                    && java.util.Objects.equals(cur.addons(), detailStr)
                    && java.util.Objects.equals(cur.instructions(), instr)) {
                staged.set(i, new DraftItemEdit(null, cur.productId(), cur.variantId(), cur.name(), cur.sku(),
                        cur.unitPrice(), cur.cost(), cur.qty() + qty, cur.addons(), cur.instructions(), cur.discount()));
                renderStaged();
                return;
            }
        }
        staged.add(new DraftItemEdit(null, p.id(), size == null ? null : size.id(), p.name(), p.sku(),
                unit, Money.nz(p.cost()), qty, detailStr, instr, Money.ZERO));
        renderStaged();
    }

    private void renderStaged() {
        stagedList.getChildren().clear();
        if (staged.isEmpty()) {
            stagedSummary.setText("Click any menu item on the left to add.");
            stagedList.getChildren().add(Ui.muted("No items staged."));
            return;
        }
        BigDecimal total = staged.stream().map(DraftItemEdit::lineTotal).reduce(Money.ZERO, Money::add);
        int totalQty = staged.stream().mapToInt(DraftItemEdit::qty).sum();
        stagedSummary.setText(totalQty + " item(s)  ·  " + Money.fmt(total));

        for (int i = 0; i < staged.size(); i++) {
            final int idx = i;
            DraftItemEdit item = staged.get(i);
            VBox info = new VBox(2,
                    Ui.label(item.name(), "h3"),
                    Ui.muted((item.addons().isBlank() ? "" : item.addons() + " · ") + Money.fmt(item.unitPrice()) + " each"));
            HBox.setHgrow(info, Priority.ALWAYS);

            Button minus = Ui.ghost("-", null, () -> {
                if (item.qty() <= 1) staged.remove(idx);
                else staged.set(idx, new DraftItemEdit(null, item.productId(), item.variantId(), item.name(),
                        item.sku(), item.unitPrice(), item.cost(), item.qty() - 1, item.addons(), item.instructions(), item.discount()));
                renderStaged();
            });
            Label qLbl = Ui.label(String.valueOf(item.qty()), "h3");
            Button plus = Ui.ghost("+", null, () -> {
                staged.set(idx, new DraftItemEdit(null, item.productId(), item.variantId(), item.name(),
                        item.sku(), item.unitPrice(), item.cost(), item.qty() + 1, item.addons(), item.instructions(), item.discount()));
                renderStaged();
            });
            Label lineTot = Ui.label(Money.fmt(item.lineTotal()), "h3");
            HBox row = new HBox(6, info, minus, qLbl, plus, lineTot);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 8, 6, 8));
            row.setStyle("-fx-background-color: #FAF8F5; -fx-background-radius: 8; -fx-border-color: #EDE6DE; -fx-border-radius: 8;");
            stagedList.getChildren().add(row);
        }
    }
}
