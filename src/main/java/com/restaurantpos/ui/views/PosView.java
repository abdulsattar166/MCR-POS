package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.*;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Catalog.*;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.Floor.FloorPlan;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.service.Cart;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.UiState;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.ProductCard;
import com.restaurantpos.ui.components.ProductOptionsDialog;
import com.restaurantpos.ui.components.ReceiptPreviewDialog;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public final class PosView extends AppView {

    public enum Step {
        ORDER_TYPE(1, "1. Order Type", "fas-receipt"),
        TABLE_SELECTION(2, "2. Table Selection", "fas-chair"),
        MENU_BILLING(3, "3. Menu & Billing", "fas-utensils"),
        REVIEW_ORDER(4, "4. Review & Confirm", "fas-check-circle");

        private final int number;
        private final String title;
        private final String icon;

        Step(int number, String title, String icon) {
            this.number = number;
            this.title = title;
            this.icon = icon;
        }

        public int number() { return number; }
        public String title() { return title; }
        public String icon() { return icon; }
    }

    // State
    private Step currentStep = Step.ORDER_TYPE;
    private Cart cart = new Cart();
    private long lastOrderId = 0;
    private boolean isPlacingOrder = false;

    // Wizard Containers
    private final HBox stepIndicatorBar = new HBox(8);
    private final StackPane stepContentPane = new StackPane();

    // Step 1 Controls (Order Type)
    private final Map<OrderType, VBox> orderTypeCards = new LinkedHashMap<>();
    private final TextField customerNameField = Ui.textField("Customer Name");
    private final TextField customerPhoneField = Ui.textField("Customer Phone");
    private final TextArea customerAddressArea = Ui.textArea("Delivery Address (Street, Area, City)", 2);
    private final TextArea deliveryInstructionsArea = Ui.textArea("Delivery Instructions / Landmark (optional)", 2);
    private final TextArea customerNotesArea = Ui.textArea("Customer Notes / Order Remarks (optional)", 2);
    private final TextField deliveryChargeField = Ui.textField("Delivery Charge (PKR)");
    private final VBox deliveryDetailsBox = new VBox(8);

    // Step 2 Controls (Table Selection)
    private final ComboBox<String> floorFilter = new ComboBox<>();
    private final FlowPane tableGrid = new FlowPane(12, 12);
    private final Label selectedTableBanner = Ui.label("No table selected (Optional)", "h3");
    private final Map<Long, VBox> tableCards = new HashMap<>();
    private List<FloorPlan> allFloors = List.of();
    private List<DiningTable> allTables = List.of();
    private Button tableNextBtn;
    private volatile boolean isTransitioning = false;

    // Step 3 Controls (Menu & Live Billing)
    private Map<Long, List<Size>> cachedSizes = Map.of();
    private Map<Long, List<AddOn>> cachedAddOns = Map.of();
    private final HBox categoryList = new HBox(8);
    private final ResponsiveCardGrid productGrid = new ResponsiveCardGrid(12, 140, 1, 4);
    private final VBox cartLinesBox = new VBox(6);
    private final TextField productSearch = Ui.textField("Search food name or SKU...");
    private final Label billContext = Ui.muted("Dine-In  ·  No table");
    private final Label subVal = Ui.label("PKR 0.00", "h3");
    private final Label discVal = Ui.label("PKR 0.00", "h3");
    private final Label taxVal = Ui.label("PKR 0.00", "h3");
    private final Label deliveryVal = Ui.label("PKR 0.00", "h3");
    private final Label totalVal = Ui.label("PKR 0.00", "total-amount");
    private HBox deliveryBillRow;
    private Button reviewButton;
    private Long selectedCategoryId = null; // null represents "All Menu"
    private List<Product> allProducts = List.of();

    // Step 5 Controls (Review)
    private final VBox reviewSummaryBox = new VBox(12);
    private final VBox reviewItemsBox = new VBox(6);
    private final GridPane reviewTotalsGrid = new GridPane();
    private Button placeOrderBtn;
    private final Label placeOrderError = Ui.label("", "inline-error");

    public PosView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        setSpacing(4);

        stepContentPane.setStyle("-fx-min-height: 240;");
        VBox.setVgrow(stepContentPane, Priority.ALWAYS);
        getChildren().add(stepContentPane);

        initDeliveryFieldListeners();

        buildStep1();
        buildStep2();
        buildStep4();
        buildStep5();

        goToStep(Step.ORDER_TYPE);
        ctx.menu().addMenuChangeListener(() -> Platform.runLater(this::loadData));
        refresh();
    }

    @Override
    public void refresh() {
        if (UiState.pendingTableId != null) {
            long tid = UiState.pendingTableId;
            UiState.pendingTableId = null;
            var t = ctx.floorRepo().table(tid);
            if (t != null) {
                cart.type = OrderType.DINE_IN;
                cart.tableId = t.id();
                cart.tableNo = t.tableNo();
                selectOrderType(OrderType.DINE_IN);
                goToStep(Step.MENU_BILLING);
            }
        }
        loadData();
    }

    private void loadData() {
        try {
            allProducts = ctx.menu().products(true, null);
            cachedSizes = ctx.menu().allActiveSizes();
            cachedAddOns = ctx.menu().allActiveAddOns();
            allFloors = ctx.floorRepo().floors(true);
            allTables = ctx.floorRepo().tables(null, true);
            syncCartWithLiveMenu();
            renderTables();
            renderCategories();
            renderProducts();
            renderCartLines();
            recalcBill();
        } catch (Exception e) {
            // Log silently or ignore during init
        }
    }

    private void syncCartWithLiveMenu() {
        if (cart.lines.isEmpty()) return;
        Map<Long, Product> byId = allProducts.stream().collect(Collectors.toMap(Product::id, p -> p, (a, b) -> a));
        cart.lines.removeIf(l -> l.productId > 0 && !byId.containsKey(l.productId));
        for (Cart.CartLine l : cart.lines) {
            if (l.productId <= 0) continue;
            Product p = byId.get(l.productId);
            if (p == null) continue;
            l.name = p.name();
            l.sku = p.sku();
            l.cost = p.cost();
            l.taxRate = p.taxRate();
            l.station = p.station();
            if (l.variantId != null) {
                cachedSizes.getOrDefault(p.id(), List.of()).stream()
                        .filter(s -> s.id() == l.variantId)
                        .findFirst()
                        .ifPresent(s -> {
                            BigDecimal eff = s.effectivePrice(p.effectivePrice());
                            if (eff != null) l.unitPrice = Money.nz(eff);
                        });
            } else if (l.detail == null || l.detail.isBlank()) {
                l.unitPrice = Money.nz(p.effectivePrice());
            }
        }
    }

    // ==========================================
    // STEP INDICATOR
    // ==========================================
    private void buildStepIndicator() {
        stepIndicatorBar.getStyleClass().add("wizard-header-bar");
        stepIndicatorBar.setAlignment(Pos.CENTER_LEFT);
        renderStepIndicator();
    }

    private void renderStepIndicator() {
        stepIndicatorBar.getChildren().clear();
        for (Step s : Step.values()) {
            HBox node = new HBox(8);
            node.getStyleClass().add("wizard-step-node");

            boolean isActive = s == currentStep;
            boolean isDone = s.number() < currentStep.number();

            if (isActive) {
                node.getStyleClass().add("wizard-step-active");
            } else if (isDone) {
                node.getStyleClass().add("wizard-step-done");
            } else {
                node.getStyleClass().add("wizard-step-upcoming");
            }

            FontIcon icon = isDone ? Ui.icon("fas-check-circle", 14) : Ui.icon(s.icon(), 14);
            Label label = new Label(s.title());
            node.getChildren().addAll(icon, label);

            if (isDone || isActive) {
                node.setOnMouseClicked(e -> goToStep(s));
            }

            stepIndicatorBar.getChildren().add(node);
            if (s.number() < 4) {
                Label arrow = new Label("›");
                arrow.setStyle("-fx-text-fill: -pos-muted; -fx-font-size: 16px; -fx-padding: 0 4;");
                stepIndicatorBar.getChildren().add(arrow);
            }
        }
    }

    public Cart getCart() { return cart; }
    public void goToStepForTesting(Step step) { goToStep(step); }
    public void selectOrderTypeForTesting(OrderType type) { selectOrderType(type); }
    public ResponsiveCardGrid getProductGridForTesting() { return productGrid; }
    public Long getSelectedCategoryIdForTesting() { return selectedCategoryId; }
    public void selectCategoryForTesting(Long categoryId) {
        this.selectedCategoryId = categoryId;
        renderCategories();
        renderProducts();
    }
    public void confirmTableForTesting() { confirmTableAndProceed(); }

    private void confirmTableAndProceed() {
        if (isTransitioning) return;
        isTransitioning = true;
        try {
            goToStep(Step.MENU_BILLING);
        } finally {
            Platform.runLater(() -> isTransitioning = false);
        }
    }

    private void goToStep(Step step) {
        this.currentStep = step;
        stepContentPane.getChildren().clear();

        Node stepNode = switch (step) {
            case ORDER_TYPE -> buildStep1();
            case TABLE_SELECTION -> buildStep2();
            case MENU_BILLING -> buildStep4();
            case REVIEW_ORDER -> buildStep5();
        };

        stepContentPane.getChildren().add(stepNode);
        Animations.fadeIn(stepNode, 200, 0);
    }

    // ==========================================
    // STEP 1 — ORDER TYPE & DELIVERY DETAILS
    // ==========================================
    private void initDeliveryFieldListeners() {
        customerNameField.textProperty().addListener((o, a, b) -> cart.customerName = b == null ? "" : b.trim());
        customerPhoneField.textProperty().addListener((o, a, b) -> cart.customerPhone = b == null ? "" : b.trim());
        customerAddressArea.textProperty().addListener((o, a, b) -> cart.customerAddress = b == null ? "" : b.trim());
        deliveryInstructionsArea.textProperty().addListener((o, a, b) -> syncCustomerDetailsToCart());
        customerNotesArea.textProperty().addListener((o, a, b) -> syncCustomerDetailsToCart());
        deliveryChargeField.textProperty().addListener((o, a, b) -> {
            try {
                cart.deliveryCharge = Money.of(b);
                recalcBill();
            } catch (Exception ignored) {}
        });
    }

    private void syncCustomerDetailsToCart() {
        cart.customerName = customerNameField.getText() == null ? "" : customerNameField.getText().trim();
        cart.customerPhone = customerPhoneField.getText() == null ? "" : customerPhoneField.getText().trim();
        cart.customerAddress = customerAddressArea.getText() == null ? "" : customerAddressArea.getText().trim();
        String instr = deliveryInstructionsArea.getText() == null ? "" : deliveryInstructionsArea.getText().trim();
        String userNotes = customerNotesArea.getText() == null ? "" : customerNotesArea.getText().trim();

        if (!instr.isBlank() && !userNotes.isBlank()) {
            cart.notes = "Instructions: " + instr + "\nNotes: " + userNotes;
        } else if (!instr.isBlank()) {
            cart.notes = "Instructions: " + instr;
        } else if (!userNotes.isBlank()) {
            cart.notes = userNotes;
        } else {
            cart.notes = "";
        }

        if (cart.type == OrderType.DELIVERY) {
            try {
                cart.deliveryCharge = Money.of(deliveryChargeField.getText());
            } catch (Exception ex) {
                cart.deliveryCharge = ctx.settings().deliveryDefault();
            }
        }
    }

    private void populateCustomerDetailsFromCart() {
        if (cart.customerName != null && !cart.customerName.equals(customerNameField.getText())) {
            customerNameField.setText(cart.customerName);
        }
        if (cart.customerPhone != null && !cart.customerPhone.equals(customerPhoneField.getText())) {
            customerPhoneField.setText(cart.customerPhone);
        }
        if (cart.customerAddress != null && !cart.customerAddress.equals(customerAddressArea.getText())) {
            customerAddressArea.setText(cart.customerAddress);
        }

        String n = cart.notes != null ? cart.notes : "";
        if (n.contains("Instructions:") && n.contains("Notes:")) {
            int idxNotes = n.indexOf("Notes:");
            String instr = n.substring("Instructions:".length(), idxNotes).trim();
            String notesPart = n.substring(idxNotes + "Notes:".length()).trim();
            if (!instr.equals(deliveryInstructionsArea.getText())) deliveryInstructionsArea.setText(instr);
            if (!notesPart.equals(customerNotesArea.getText())) customerNotesArea.setText(notesPart);
        } else if (n.startsWith("Instructions:")) {
            String instr = n.substring("Instructions:".length()).trim();
            if (!instr.equals(deliveryInstructionsArea.getText())) deliveryInstructionsArea.setText(instr);
            if (!"".equals(customerNotesArea.getText())) customerNotesArea.setText("");
        } else {
            if (!"".equals(deliveryInstructionsArea.getText())) deliveryInstructionsArea.setText("");
            if (!n.equals(customerNotesArea.getText())) customerNotesArea.setText(n);
        }

        BigDecimal charge = cart.deliveryCharge.signum() > 0 ? cart.deliveryCharge : ctx.settings().deliveryDefault();
        String chargeStr = Money.plain(charge);
        if (!chargeStr.equals(deliveryChargeField.getText())) {
            deliveryChargeField.setText(chargeStr);
        }
    }

    private Node buildStep1() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(8, 4, 8, 4));

        Label title = Ui.label("Select Order Type", "h2");
        Label subtitle = Ui.muted("Choose how the customer will be served. Dine-In allows optional table assignment; takeaway, pickup and side orders can proceed without a table.");

        FlowPane cardRow = new FlowPane(12, 12);
        cardRow.setAlignment(Pos.CENTER_LEFT);
        cardRow.setPrefWrapLength(740);
        orderTypeCards.clear();

        cardRow.getChildren().addAll(
                createOrderTypeCard(OrderType.DINE_IN, "Dine-In", "Table service dining (table optional)", "fas-chair"),
                createOrderTypeCard(OrderType.TAKEAWAY, "Takeaway", "Customer carries food away", "fas-shopping-bag"),
                createOrderTypeCard(OrderType.DELIVERY, "Delivery", "Delivered to customer address", "fas-motorcycle"),
                createOrderTypeCard(OrderType.PICKUP, "Pickup", "Customer collection / pre-order", "fas-box"),
                createOrderTypeCard(OrderType.SIDE_ORDER, "Side Order", "Quick counter items / no table", "fas-bolt")
        );

        // Delivery details pane
        deliveryDetailsBox.getChildren().clear();
        deliveryDetailsBox.getStyleClass().add("card");
        deliveryDetailsBox.setPadding(new Insets(16));
        deliveryDetailsBox.setMaxWidth(680);
        deliveryDetailsBox.getChildren().addAll(
                Ui.label("DELIVERY & CUSTOMER DETAILS", "section-label"),
                Ui.field("Customer Name *", customerNameField),
                Ui.field("Customer Phone *", customerPhoneField),
                Ui.field("Delivery Address *", customerAddressArea),
                Ui.field("Delivery Instructions / Landmark", deliveryInstructionsArea),
                Ui.field("Customer Notes / Order Remarks", customerNotesArea),
                Ui.field("Delivery Charge (PKR)", deliveryChargeField)
        );
        deliveryDetailsBox.setVisible(cart.type == OrderType.DELIVERY);
        deliveryDetailsBox.setManaged(cart.type == OrderType.DELIVERY);

        populateCustomerDetailsFromCart();

        VBox scrollContent = new VBox(14, title, subtitle, cardRow, deliveryDetailsBox);
        scrollContent.setPadding(new Insets(4, 6, 12, 4));

        ScrollPane scroll = new ScrollPane(scrollContent);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        scroll.setPannable(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Buttons
        Button nextBtn = Ui.primary("Next: Table Selection  ›", "fas-arrow-right", () -> {
            if (cart.type == OrderType.DELIVERY) {
                syncCustomerDetailsToCart();
                if (cart.customerName.isBlank() || cart.customerPhone.isBlank() || cart.customerAddress.isBlank()) {
                    warn("Customer name, phone and delivery address are required for delivery.");
                    return;
                }
            } else {
                cart.deliveryCharge = Money.ZERO;
            }
            goToStep(Step.TABLE_SELECTION);
        });

        HBox bot = new HBox(12, Ui.spacer(), nextBtn);
        bot.setAlignment(Pos.CENTER_RIGHT);
        bot.setPadding(new Insets(8, 0, 0, 0));

        root.getChildren().addAll(scroll, new Separator(), bot);
        VBox.setVgrow(root, Priority.ALWAYS);
        selectOrderType(cart.type != null ? cart.type : OrderType.DINE_IN);
        return root;
    }

    private VBox createOrderTypeCard(OrderType type, String title, String desc, String icon) {
        VBox card = new VBox(8);
        card.getStyleClass().add("wizard-card");
        card.setPrefWidth(160);
        card.setMinWidth(140);
        card.setAlignment(Pos.CENTER);

        FontIcon ico = Ui.icon(icon, 32);
        ico.setIconColor(javafx.scene.paint.Color.web("#b9823f"));
        Label lTitle = Ui.label(title, "h3");
        Label lDesc = Ui.muted(desc);
        lDesc.setWrapText(true);
        lDesc.setAlignment(Pos.CENTER);
        lDesc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        card.getChildren().addAll(ico, lTitle, lDesc);
        card.setOnMouseClicked(e -> selectOrderType(type));

        orderTypeCards.put(type, card);
        return card;
    }

    private void selectOrderType(OrderType type) {
        cart.type = type;
        for (Map.Entry<OrderType, VBox> entry : orderTypeCards.entrySet()) {
            if (entry.getKey() == type) {
                entry.getValue().getStyleClass().remove("wizard-card-selected");
                entry.getValue().getStyleClass().add("wizard-card-selected");
            } else {
                entry.getValue().getStyleClass().remove("wizard-card-selected");
            }
        }
        boolean isDelivery = type == OrderType.DELIVERY;
        deliveryDetailsBox.setVisible(isDelivery);
        deliveryDetailsBox.setManaged(isDelivery);
    }

    // ==========================================
    // STEP 2 — TABLE SELECTION (OPTIONAL)
    // ==========================================
    private Node buildStep2() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(14, 4, 14, 4));

        Label title = Ui.label("Step 2: Table Selection (Optional)", "h2");
        Label subtitle = Ui.muted("Select an available dining table, or continue without assigning a table.");

        HBox topFilter = new HBox(12);
        topFilter.setAlignment(Pos.CENTER_LEFT);
        floorFilter.getItems().clear();
        floorFilter.getItems().add("All Floors");
        allFloors = ctx.floorRepo().floors(true);
        for (FloorPlan f : allFloors) floorFilter.getItems().add(f.name());
        floorFilter.setValue("All Floors");
        floorFilter.valueProperty().addListener((o, a, b) -> renderTables());

        Button clearTableBtn = Ui.ghost("Clear Selection (No Table)", "fas-times", () -> {
            cart.tableId = null;
            cart.tableNo = null;
            renderTables();
        });

        topFilter.getChildren().addAll(Ui.label("Floor:", "field-label"), floorFilter, clearTableBtn, Ui.spacer(), selectedTableBanner);

        tableGrid.setPadding(new Insets(10, 0, 10, 0));
        ScrollPane scroll = new ScrollPane(tableGrid);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(340);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button backBtn = Ui.secondary("Back: Order Type", "fas-arrow-left", () -> goToStep(Step.ORDER_TYPE));
        Button continueWithoutBtn = Ui.button("Continue Without Table", "fas-forward", "secondary-button", () -> {
            cart.tableId = null;
            cart.tableNo = null;
            confirmTableAndProceed();
        });
        tableNextBtn = Ui.primary("Next: Menu & Billing  ›", "fas-arrow-right", this::confirmTableAndProceed);

        HBox bot = new HBox(12, backBtn, Ui.spacer(), continueWithoutBtn, tableNextBtn);
        bot.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(title, subtitle, topFilter, scroll, new Separator(), bot);
        renderTables();
        return root;
    }

    private void renderTables() {
        tableGrid.getChildren().clear();
        tableCards.clear();
        allTables = ctx.floorRepo().tables(null, true);

        String selectedFloorName = floorFilter.getValue();
        Long filterFloorId = null;
        if (selectedFloorName != null && !selectedFloorName.equals("All Floors")) {
            for (FloorPlan fp : allFloors) {
                if (fp.name().equals(selectedFloorName)) {
                    filterFloorId = fp.id();
                    break;
                }
            }
        }

        if (cart.tableId != null) {
            selectedTableBanner.setText("✓ Table " + cart.tableNo + " Selected");
            selectedTableBanner.setStyle("-fx-text-fill: -pos-green; -fx-font-weight: bold;");
        } else {
            selectedTableBanner.setText("No Table Selected (Optional)");
            selectedTableBanner.setStyle("-fx-text-fill: -pos-muted;");
        }

        if (tableNextBtn != null) {
            if (cart.tableId != null) {
                tableNextBtn.setText("Select Table " + cart.tableNo + " & Continue  ›");
            } else {
                tableNextBtn.setText("Next: Menu & Billing  ›");
            }
        }

        // Dedicated, clearly visible "No Table" card
        VBox noTableCard = new VBox(6);
        noTableCard.setPrefWidth(125);
        noTableCard.setMinWidth(110);
        noTableCard.setAlignment(Pos.CENTER);
        noTableCard.getStyleClass().add("wizard-card");
        if (cart.tableId == null) {
            noTableCard.getStyleClass().add("wizard-card-selected");
        }
        FontIcon noIco = Ui.icon("fas-ban", 24);
        noIco.setIconColor(javafx.scene.paint.Color.web("#969aa3"));
        Label noTitle = Ui.label("No Table", "h3");
        Label noSub = Ui.muted("Counter/Takeaway");
        Label noBadge = Ui.chip("Optional", "neutral");
        noTableCard.getChildren().addAll(noIco, noTitle, noSub, noBadge);
        noTableCard.setOnMouseClicked(e -> {
            cart.tableId = null;
            cart.tableNo = null;
            renderTables();
            if (e.getClickCount() == 2) {
                confirmTableAndProceed();
            }
        });
        tableGrid.getChildren().add(noTableCard);

        for (DiningTable t : allTables) {
            if (filterFloorId != null && t.floorId() != filterFloorId) continue;

            VBox card = new VBox(6);
            card.setPrefWidth(125);
            card.setMinWidth(110);
            card.setAlignment(Pos.CENTER);

            boolean isThisSelected = cart.tableId != null && cart.tableId.equals(t.id());

            String cssClass = switch (t.status()) {
                case AVAILABLE -> "table-card-avail";
                case OCCUPIED -> "table-card-occ";
                case RESERVED -> "table-card-res";
                case CLEANING, OUT_OF_SERVICE -> "table-card-oos";
                default -> "table-card-avail";
            };
            card.getStyleClass().addAll(cssClass);

            if (isThisSelected) {
                card.getStyleClass().add("wizard-card-selected");
            }

            FontIcon icon = Ui.icon(t.shape().equalsIgnoreCase("ROUND") ? "fas-circle" : "fas-square", 24);
            Label no = Ui.label(t.tableNo(), "h3");
            Label cap = Ui.muted(t.capacity() + " Seats");
            Label statusBadge = Ui.chip(t.status().label(), t.status() == TableStatus.AVAILABLE ? "primary" : "accent");

            card.getChildren().addAll(icon, no, cap, statusBadge);

            if (t.status() == TableStatus.AVAILABLE || isThisSelected) {
                card.setOnMouseClicked(e -> {
                    cart.tableId = t.id();
                    cart.tableNo = t.tableNo();
                    renderTables();
                    if (e.getClickCount() == 2) {
                        confirmTableAndProceed();
                    }
                });
            } else {
                card.setOnMouseClicked(e -> {
                    warn("Table " + t.tableNo() + " is " + t.status().label().toLowerCase() + ". Please choose an available table or continue without a table.");
                });
            }

            tableCards.put(t.id(), card);
            tableGrid.getChildren().add(card);
        }

        if (tableGrid.getChildren().isEmpty()) {
            tableGrid.getChildren().add(Ui.empty("No tables found", "No dining tables configured on this floor."));
        }
    }

    // ==========================================
    // STEP 3 — MENU & LIVE BILLING
    // ==========================================
    private Node buildStep4() {
        VBox root = new VBox(4);
        root.setPadding(new Insets(2, 4, 4, 4));

        HBox mainLayout = new HBox(12);
        VBox.setVgrow(mainLayout, Priority.ALWAYS);

        // Center Product grid & horizontal category bar
        VBox centerPane = new VBox(8);
        HBox.setHgrow(centerPane, Priority.ALWAYS);

        // Horizontal Category Navigation Bar
        categoryList.setPadding(new Insets(2, 2, 4, 2));
        categoryList.setAlignment(Pos.CENTER_LEFT);
        ScrollPane catScroll = new ScrollPane(categoryList);
        catScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        catScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        catScroll.setFitToHeight(true);
        catScroll.setMinHeight(46);
        catScroll.setPrefHeight(48);
        catScroll.getStyleClass().add("edge-to-edge");

        productSearch.setPrefWidth(280);
        Ui.bindSearch(productSearch, this::renderProducts);
        HBox searchRow = new HBox(8, Ui.icon("fas-search", 13), productSearch);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        productGrid.setPadding(new Insets(6));
        ScrollPane prodScroll = new ScrollPane(productGrid);
        prodScroll.setFitToWidth(true);
        prodScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(prodScroll, Priority.ALWAYS);

        centerPane.getChildren().addAll(catScroll, searchRow, prodScroll);

        // Right Live Bill / Cart Panel
        VBox cartPane = new VBox(10);
        cartPane.getStyleClass().add("cart-panel");
        cartPane.setMinWidth(360);
        cartPane.setPrefWidth(385);
        cartPane.setPadding(new Insets(14));

        Label cartTitle = Ui.label("Current Bill", "cart-title");
        Button clearBtn = Ui.iconButton("fas-trash-alt", "Clear Cart", this::confirmClearCart);
        HBox cartHead = new HBox(8, cartTitle, Ui.spacer(), clearBtn);
        cartHead.setAlignment(Pos.CENTER_LEFT);

        // Active-order & held-order controls in Current Bill area
        Button holdBtn = Ui.ghost("Hold Order", "fas-hand-paper", this::holdCurrentOrder);
        Button openHeldBtn = Ui.ghost("Held Orders", "fas-pause-circle", this::openHeldOrders);
        Button resetOrderBtn = Ui.ghost("Reset Wizard", "fas-redo", this::confirmResetWizard);
        HBox billActions = new HBox(6, holdBtn, openHeldBtn, resetOrderBtn);
        billActions.setAlignment(Pos.CENTER_LEFT);

        ScrollPane cartLinesScroll = new ScrollPane(cartLinesBox);
        cartLinesScroll.setFitToWidth(true);
        cartLinesScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(cartLinesScroll, Priority.ALWAYS);

        GridPane totals = new GridPane();
        totals.setHgap(10); totals.setVgap(4);
        int r = 0;
        totals.add(Ui.muted("Subtotal:"), 0, r); totals.add(subVal, 1, r++);
        totals.add(Ui.muted("Discounts:"), 0, r); totals.add(discVal, 1, r++);
        totals.add(Ui.muted("Tax:"), 0, r); totals.add(taxVal, 1, r++);
        deliveryBillRow = new HBox(8, Ui.muted("Delivery:"), Ui.spacer(), deliveryVal);
        totals.add(deliveryBillRow, 0, r++, 2, 1);
        totals.add(new Separator(), 0, r++, 2, 1);
        totals.add(Ui.label("TOTAL:", "stat-label"), 0, r); totals.add(totalVal, 1, r++);

        reviewButton = Ui.primary("Review Order  ›", "fas-arrow-right", () -> {
            if (cart.lines.isEmpty()) {
                warn("Please add at least one item to proceed.");
                return;
            }
            goToStep(Step.REVIEW_ORDER);
        });
        reviewButton.setMaxWidth(Double.MAX_VALUE);
        reviewButton.setPrefHeight(42);

        Button backBtn = Ui.secondary("Back", "fas-arrow-left", () -> {
            Step backStep = (cart.type == OrderType.DINE_IN) ? Step.TABLE_SELECTION : Step.ORDER_TYPE;
            goToStep(backStep);
        });
        backBtn.setMaxWidth(Double.MAX_VALUE);

        cartPane.getChildren().addAll(cartHead, billActions, new Separator(), cartLinesScroll, new Separator(), totals, reviewButton, backBtn);

        mainLayout.getChildren().addAll(centerPane, cartPane);
        root.getChildren().add(mainLayout);

        renderCategories();
        renderProducts();
        renderCartLines();
        recalcBill();
        return root;
    }

    private void holdCurrentOrder() {
        if (cart.lines.isEmpty()) {
            warn("Cannot hold an empty order. Please add at least one food item first.");
            return;
        }
        try {
            long heldId = ctx.orders().createOrder(cart, true, ctx.session());
            Order o = ctx.orders().find(heldId);
            toast("Order " + o.orderNo() + " held successfully.");
            resetWizard();
        } catch (Exception ex) {
            Ui.error(nav.window(), ex);
        }
    }

    private void confirmResetWizard() {
        if (!cart.lines.isEmpty()) {
            if (!Ui.confirm(nav.window(), "Reset Order Wizard", "Are you sure you want to discard the current bill and reset the order?")) {
                return;
            }
        }
        resetWizard();
    }

    private void updateBillContext() {
        String typeStr = cart.type != null ? cart.type.label() : "Dine-In";
        String tableStr = cart.tableNo != null ? "Table " + cart.tableNo : "No Table";
        billContext.setText(typeStr + "  ·  " + tableStr);
    }

    private void renderCategories() {
        categoryList.getChildren().clear();

        // 1. "All Menu (No Category / Skip Category)" button
        Button allBtn = new Button("All Menu (No Category)");
        allBtn.getStyleClass().add("category-button");
        allBtn.setMinWidth(Region.USE_PREF_SIZE);
        if (selectedCategoryId == null) {
            allBtn.getStyleClass().add("active");
        }
        allBtn.setOnAction(e -> {
            selectedCategoryId = null;
            renderCategories();
            renderProducts();
        });
        allBtn.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                selectedCategoryId = null;
                renderCategories();
                renderProducts();
                productSearch.requestFocus();
            }
        });
        categoryList.getChildren().add(allBtn);

        // 2. Active categories loaded dynamically from database
        var dbCats = ctx.menu().categories(true);
        for (Category c : dbCats) {
            Button btn = new Button(c.name());
            btn.getStyleClass().add("category-button");
            btn.setMinWidth(Region.USE_PREF_SIZE);
            if (selectedCategoryId != null && selectedCategoryId == c.id()) {
                btn.getStyleClass().add("active");
            }
            btn.setOnAction(e -> {
                selectedCategoryId = c.id();
                renderCategories();
                renderProducts();
            });
            btn.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    selectedCategoryId = c.id();
                    renderCategories();
                    renderProducts();
                    productSearch.requestFocus();
                }
            });
            categoryList.getChildren().add(btn);
        }
    }

    private void renderProducts() {
        String q = productSearch.getText() == null ? "" : productSearch.getText().trim().toLowerCase();

        List<Product> matched = allProducts.stream()
                .filter(Product::available)
                .filter(p -> {
                    if (!q.isEmpty()) {
                        return p.name().toLowerCase().contains(q)
                                || p.sku().toLowerCase().contains(q)
                                || (p.description() != null && p.description().toLowerCase().contains(q));
                    }
                    if (selectedCategoryId == null) {
                        return true;
                    }
                    return p.categoryId() == selectedCategoryId;
                })
                .toList();

        if (matched.isEmpty()) {
            String catName = "All Menu";
            if (selectedCategoryId != null) {
                var c = ctx.menu().categories(true).stream()
                        .filter(cat -> cat.id() == selectedCategoryId).findFirst().orElse(null);
                if (c != null) catName = c.name();
            }
            productGrid.setCards(List.of(Ui.empty("No food items available",
                    q.isEmpty() ? "No products found in " + catName + "."
                            : "No menu items match '" + q + "'.")));
            return;
        }

        List<Node> cards = new ArrayList<>(matched.size());
        for (Product p : matched) {
            var sizes = cachedSizes.getOrDefault(p.id(), List.of());
            var addons = cachedAddOns.getOrDefault(p.id(), List.of());
            boolean hasOptions = !sizes.isEmpty() || !addons.isEmpty();

            ProductCard card = new ProductCard(p, hasOptions, () -> onProductClicked(p));
            cards.add(card);
        }
        productGrid.setCards(cards);
    }

    private void onProductClicked(Product p) {
        var sizes = cachedSizes.getOrDefault(p.id(), List.of());
        var addons = cachedAddOns.getOrDefault(p.id(), List.of());

        if (sizes.isEmpty() && addons.isEmpty()) {
            // Direct 1 qty add
            cart.add(p, (Size) null, List.of(), 1, "");
            renderCartLines();
            recalcBill();
        } else {
            ProductOptionsDialog dialog = new ProductOptionsDialog(p, sizes, addons, nav.window());
            dialog.showAndWait().ifPresent(choice -> {
                cart.add(p, choice.size(), choice.addons(), choice.qty(), choice.instructions());
                renderCartLines();
                recalcBill();
            });
        }
    }

    private void renderCartLines() {
        cartLinesBox.getChildren().clear();
        if (cart.lines.isEmpty()) {
            cartLinesBox.getChildren().add(Ui.empty("Cart is empty", "Add food items from the menu."));
            reviewButton.setDisable(true);
            return;
        }

        reviewButton.setDisable(false);
        for (Cart.CartLine line : cart.lines) {
            VBox row = new VBox(3);
            row.setStyle("-fx-background-color: -pos-surface-alt; -fx-padding: 8; -fx-background-radius: 6;");

            HBox top = new HBox(6,
                    Ui.label(line.qty + " × " + line.name, "h3"),
                    Ui.spacer(),
                    Ui.label(Money.fmt(line.net()), "h3")
            );
            top.setAlignment(Pos.CENTER_LEFT);

            HBox detailsRow = new HBox(6);
            if (line.detail != null && !line.detail.isBlank()) {
                detailsRow.getChildren().add(Ui.chip(line.detail, "accent"));
            }
            if (line.instructions != null && !line.instructions.isBlank()) {
                detailsRow.getChildren().add(Ui.muted("*" + line.instructions));
            }

            HBox controls = new HBox(6);
            controls.setAlignment(Pos.CENTER_RIGHT);

            Button minus = new Button("−"); minus.getStyleClass().add("qty-button");
            Button plus = new Button("+"); plus.getStyleClass().add("qty-button");
            Button editNote = Ui.iconButton("fas-edit", "Instructions", () -> {
                String n = Ui.prompt(nav.window(), "Instructions", "Instructions for " + line.name, "Notes", line.instructions);
                if (n != null) { line.instructions = n.trim(); renderCartLines(); }
            });
            Button del = Ui.iconButton("fas-trash-alt", "Remove", () -> {
                cart.remove(line);
                renderCartLines();
                recalcBill();
            });

            minus.setOnAction(e -> {
                if (line.qty > 1) { line.qty--; }
                else { cart.remove(line); }
                renderCartLines();
                recalcBill();
            });
            plus.setOnAction(e -> {
                line.qty++;
                renderCartLines();
                recalcBill();
            });

            controls.getChildren().addAll(minus, plus, editNote, del);

            row.getChildren().addAll(top, detailsRow, controls);
            cartLinesBox.getChildren().add(row);
        }
    }

    private void recalcBill() {
        Cart.Totals t = ctx.orders().quote(cart);
        subVal.setText(Money.fmt(t.subtotal));
        discVal.setText(Money.fmt(t.discount));
        taxVal.setText(Money.fmt(t.tax));
        deliveryVal.setText(Money.fmt(t.delivery));
        totalVal.setText(Money.fmt(t.total));

        boolean isDelivery = cart.type == OrderType.DELIVERY;
        deliveryBillRow.setVisible(isDelivery);
        deliveryBillRow.setManaged(isDelivery);
    }

    private void confirmClearCart() {
        if (cart.lines.isEmpty()) return;
        if (Ui.confirm(nav.window(), "Clear Cart", "Are you sure you want to remove all items from this order?")) {
            cart.lines.clear();
            renderCartLines();
            recalcBill();
        }
    }

    // ==========================================
    // STEP 5 — REVIEW, PLACE ORDER & RECEIPT
    // ==========================================
    private Node buildStep5() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(14, 4, 14, 4));

        Label title = Ui.label("Step 5: Review & Place Order", "h2");
        Label subtitle = Ui.muted("Verify all order details. Clicking 'Place Order' will save the order as UNPAID and generate a customer bill.");

        HBox split = new HBox(16);
        VBox.setVgrow(split, Priority.ALWAYS);

        // Left Order summary & details
        VBox leftSummary = new VBox(12);
        leftSummary.getStyleClass().add("card");
        leftSummary.setPadding(new Insets(16));
        leftSummary.setPrefWidth(380);
        leftSummary.setMinWidth(320);

        Label oType = Ui.label("Order Type: " + (cart.type != null ? cart.type.label() : "—"), "h3");
        Label oTable = Ui.label("Table: " + (cart.tableNo != null ? cart.tableNo : "None (Unassigned)"), "h3");
        Label oCashier = Ui.label("Cashier: " + ctx.session().user().fullName(), "h3");

        VBox deliveryInfo = new VBox(4);
        if (cart.type == OrderType.DELIVERY) {
            deliveryInfo.getChildren().addAll(
                    Ui.label("Customer: " + cart.customerName, "h3"),
                    Ui.muted("Phone: " + cart.customerPhone),
                    Ui.muted("Address: " + cart.customerAddress)
            );
            if (cart.notes != null && !cart.notes.isBlank()) {
                deliveryInfo.getChildren().add(Ui.muted("Notes / Instructions:\n" + cart.notes));
            }
        }

        Label bannerNotice = new Label("ORDER WILL BE STORED AS UNPAID — PAYMENT PENDING\nCashier must collect and confirm payment at the counter.");
        bannerNotice.setStyle("-fx-background-color: -pos-amber-soft; -fx-text-fill: #8a6113; -fx-font-weight: bold; -fx-padding: 10 12; -fx-background-radius: 8;");
        bannerNotice.setWrapText(true);

        leftSummary.getChildren().addAll(
                Ui.label("ORDER INFORMATION", "section-label"),
                oType, oTable, oCashier,
                new Separator(),
                deliveryInfo,
                bannerNotice
        );

        // Right Items & Financial Totals
        VBox rightBill = new VBox(12);
        HBox.setHgrow(rightBill, Priority.ALWAYS);
        rightBill.getStyleClass().add("card");
        rightBill.setPadding(new Insets(16));

        reviewItemsBox.getChildren().clear();
        for (Cart.CartLine line : cart.lines) {
            HBox itemRow = new HBox(12,
                    Ui.label(line.qty + " × " + line.name, "h3"),
                    line.detail != null && !line.detail.isBlank() ? Ui.chip(line.detail, "accent") : new Label(),
                    Ui.spacer(),
                    Ui.muted("Unit: " + Money.fmt(line.unitPrice)),
                    Ui.label(Money.fmt(line.net()), "h3")
            );
            itemRow.setAlignment(Pos.CENTER_LEFT);
            reviewItemsBox.getChildren().add(itemRow);
        }

        ScrollPane itemScroll = new ScrollPane(reviewItemsBox);
        itemScroll.setFitToWidth(true);
        itemScroll.setPrefHeight(180);
        itemScroll.getStyleClass().add("edge-to-edge");

        Cart.Totals t = ctx.orders().quote(cart);
        reviewTotalsGrid.getChildren().clear();
        reviewTotalsGrid.setHgap(16); reviewTotalsGrid.setVgap(4);
        int r = 0;
        reviewTotalsGrid.add(Ui.muted("Subtotal:"), 0, r); reviewTotalsGrid.add(Ui.label(Money.fmt(t.subtotal), "h3"), 1, r++);
        if (t.discount.signum() > 0) {
            reviewTotalsGrid.add(Ui.muted("Discounts:"), 0, r); reviewTotalsGrid.add(Ui.label("-" + Money.fmt(t.discount), "h3"), 1, r++);
        }
        if (t.tax.signum() > 0) {
            reviewTotalsGrid.add(Ui.muted("Tax:"), 0, r); reviewTotalsGrid.add(Ui.label(Money.fmt(t.tax), "h3"), 1, r++);
        }
        if (t.delivery.signum() > 0) {
            reviewTotalsGrid.add(Ui.muted("Delivery:"), 0, r); reviewTotalsGrid.add(Ui.label(Money.fmt(t.delivery), "h3"), 1, r++);
        }
        Label finalPayable = Ui.label(Money.fmt(t.total), "total-amount");
        reviewTotalsGrid.add(Ui.label("TOTAL PAYABLE:", "field-label"), 0, r); reviewTotalsGrid.add(finalPayable, 1, r++);

        rightBill.getChildren().addAll(
                Ui.label("ITEMIZED BILL", "section-label"),
                itemScroll,
                new Separator(),
                reviewTotalsGrid
        );

        split.getChildren().addAll(leftSummary, rightBill);

        ScrollPane splitScroll = new ScrollPane(split);
        splitScroll.setFitToWidth(true);
        splitScroll.setFitToHeight(false);
        splitScroll.setPannable(true);
        splitScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(splitScroll, Priority.ALWAYS);

        // Action Buttons
        Button backToMenuBtn = Ui.secondary("‹  Back to Menu", "fas-utensils", () -> goToStep(Step.MENU_BILLING));
        Button cancelCreationBtn = Ui.ghost("Cancel Order", "fas-times", () -> {
            if (Ui.confirm(nav.window(), "Cancel Order Creation", "Discard this order and return to step 1?")) {
                resetWizard();
            }
        });

        placeOrderError.setVisible(false);
        placeOrderError.setManaged(false);

        placeOrderBtn = Ui.primary("Confirm Order  ✓", "fas-check", this::placeOrder);
        placeOrderBtn.setPrefHeight(44);
        placeOrderBtn.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        HBox bot = new HBox(12, backToMenuBtn, cancelCreationBtn, Ui.spacer(), placeOrderError, placeOrderBtn);
        bot.setAlignment(Pos.CENTER_RIGHT);
        bot.setPadding(new Insets(6, 0, 0, 0));

        root.getChildren().addAll(title, subtitle, splitScroll, new Separator(), bot);
        VBox.setVgrow(root, Priority.ALWAYS);
        return root;
    }

    private void placeOrder() {
        if (isPlacingOrder) return;
        if (cart.lines.isEmpty()) {
            warn("Order must have at least one item.");
            return;
        }

        isPlacingOrder = true;
        placeOrderBtn.setDisable(true);
        placeOrderError.setVisible(false);

        try {
            // Commit order in single transaction as unpaid draft (DRAFT / UNPAID)
            long orderId = ctx.orders().createOrder(cart, false, ctx.session());
            lastOrderId = orderId;

            Order order = ctx.orders().find(orderId);
            nav.showToast("Order " + order.orderNo() + " saved as Draft (UNPAID). Printing customer bill...", "SUCCESS");

            // Generate and print ONLY the Unpaid Customer Bill asynchronously off the FX thread (no kitchen ticket)
            ctx.printer().printCustomerReceipt(
                    order,
                    false,
                    ctx.session().userId(),
                    cRes -> Platform.runLater(() -> {
                        if (cRes.success()) {
                            nav.showToast("Customer Bill: " + cRes.message(), "SUCCESS");
                        } else {
                            nav.showToast("Customer Bill Print Failed — retry from Draft Orders: " + cRes.message(), "WARNING");
                            Ui.error(nav.window(), "Customer Bill Print Failed",
                                    "Order " + order.orderNo() + " is safely saved as Draft (UNPAID), "
                                            + "but the customer bill failed to print:\n" + cRes.message()
                                            + "\n\nUse 'Print Bill' or 'Receipt Preview' on Draft Orders.");
                        }
                    })
            );

            // Clear wizard state and automatically redirect to Draft Orders
            resetWizard();
            UiState.pendingOrderId = orderId;
            nav.navigate("draft-orders");
        } catch (Exception ex) {
            placeOrderError.setText(ex.getMessage());
            placeOrderError.setVisible(true);
            placeOrderError.setManaged(true);
            Ui.error(nav.window(), ex);
        } finally {
            isPlacingOrder = false;
            placeOrderBtn.setDisable(false);
        }
    }

    private void resetWizard() {
        cart = new Cart();
        cart.shiftId = ctx.shifts().current(ctx.session().userId()) == null ? null : ctx.shifts().current(ctx.session().userId()).id();
        selectedCategoryId = null;
        productSearch.clear();
        customerNameField.clear();
        customerPhoneField.clear();
        customerAddressArea.clear();
        deliveryInstructionsArea.clear();
        customerNotesArea.clear();
        deliveryChargeField.setText(Money.plain(ctx.settings().deliveryDefault()));
        goToStep(Step.ORDER_TYPE);
        loadData();
    }

    private void openHeldOrders() {
        List<Order> held = ctx.orders().held(ctx.session().userId());
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Held Orders");
        d.setHeaderText(null);

        TableView<Order> table = Ui.table();
        table.getColumns().addAll(
                Ui.col("Order", Order::orderNo, 150),
                Ui.col("Type", o -> o.type().label(), 100),
                Ui.col("Table", o -> o.tableNo() == null ? "—" : o.tableNo(), 70),
                Ui.moneyCol("Total", Order::total, 100),
                Ui.col("Held at", o -> Ui.dateTime(o.createdAt()), 130)
        );
        table.getItems().setAll(held);
        table.setPrefSize(680, 300);

        ButtonType resume = new ButtonType("Resume Order", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE, resume);
        d.getDialogPane().setContent(new VBox(10, Ui.muted("Select a held order to resume into the wizard."), table));

        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));

        if (d.showAndWait().filter(b -> b == resume).isPresent()) {
            Order sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                try {
                    cart = ctx.orders().resume(sel.id(), ctx.session());
                    goToStep(Step.MENU_BILLING);
                    toast("Order " + sel.orderNo() + " resumed into wizard.");
                } catch (Exception ex) {
                    Ui.error(nav.window(), ex);
                }
            }
        }
    }
}
