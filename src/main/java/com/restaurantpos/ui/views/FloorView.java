package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.OrderType;
import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.Floor.FloorPlan;
import com.restaurantpos.model.Floor.Reservation;
import com.restaurantpos.model.Floor.Section;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.UiState;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.TablePicker;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class FloorView extends AppView {
    private final ComboBox<FloorPlan> floorBox = new ComboBox<>();
    private final Pane canvas = new Pane();
    private final VBox details = new VBox(12);
    private final Label legend = new Label();
    private final ToggleButton liveBtn = new ToggleButton("Live service");
    private final ToggleButton editBtn = new ToggleButton("Layout editor");
    private final Button addFloor = Ui.secondary("Add floor", "fas-plus", this::floorDialog);
    private final Button addTable = Ui.secondary("Add table", "fas-plus", this::tableDialog);
    private final ScrollPane canvasScroll;
    private FloorPlan floor;
    private Long selectedTableId;
    private boolean editMode;
    private double dragOffsetX, dragOffsetY;
    private TableStatus lastStatus;

    public FloorView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        boolean manager = ctx.canManage();
        HBox header = Ui.pageHeader("Floors & Tables", "Live 2D floor plan, table status, reservations and transfers.",
                addFloor, addTable,
                Ui.button("Reservations", "fas-calendar-check", "secondary-button", this::reservationsDialog));

        HBox modes = new HBox(4);
        modes.getStyleClass().add("pill-tabs");
        liveBtn.getStyleClass().add("toggle-button");
        editBtn.getStyleClass().add("toggle-button");
        ToggleGroup tg = new ToggleGroup();
        liveBtn.setToggleGroup(tg);
        editBtn.setToggleGroup(tg);
        liveBtn.setSelected(true);
        liveBtn.setOnAction(e -> setEditMode(false));
        editBtn.setOnAction(e -> setEditMode(true));
        editBtn.setDisable(!manager);
        modes.getChildren().addAll(liveBtn, editBtn);
        header.getChildren().add(modes);

        floorBox.setPrefWidth(190);
        floorBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(FloorPlan f) { return f == null ? "Select floor" : f.name(); }
            @Override public FloorPlan fromString(String s) { return null; }
        });
        floorBox.valueProperty().addListener((o, a, b) -> { floor = b; rebuildCanvas(); });
        HBox floorBar = new HBox(10, Ui.label("Floor", "field-label"), floorBox, Ui.spacer(), legend);
        floorBar.setAlignment(Pos.CENTER_LEFT);
        legend.getStyleClass().add("muted");

        canvas.getStyleClass().add("floor-canvas");
        canvas.setMinSize(800, 520);
        canvasScroll = new ScrollPane(canvas);
        canvasScroll.setFitToWidth(false);
        canvasScroll.setFitToHeight(false);
        canvasScroll.getStyleClass().add("edge-to-edge");
        canvasScroll.setPannable(true);
        VBox.setVgrow(canvasScroll, Priority.ALWAYS);

        VBox canvasCard = new VBox(10, floorBar, canvasScroll);
        canvasCard.getStyleClass().add("card");
        canvasCard.setPadding(new Insets(14));
        HBox.setHgrow(canvasCard, Priority.ALWAYS);

        VBox detailCard = new VBox(12);
        detailCard.getStyleClass().add("card");
        detailCard.setPadding(new Insets(16));
        detailCard.setMinWidth(310);
        detailCard.setPrefWidth(320);
        ScrollPane detailScroll = new ScrollPane(details);
        detailScroll.setFitToWidth(true);
        detailScroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(detailScroll, Priority.ALWAYS);
        detailCard.getChildren().addAll(Ui.h2("Table details"), detailScroll);

        HBox body = new HBox(14, canvasCard, detailCard);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(header, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        showDetails(null);
        refresh();
    }

    @Override
    public void refresh() {
        Long previous = floor == null ? null : floor.id();
        List<FloorPlan> floors = ctx.tables().floors(true);
        floorBox.getItems().setAll(floors);
        if (floors.isEmpty()) {
            floor = null;
            rebuildCanvas();
            return;
        }
        FloorPlan match = floors.stream().filter(f -> previous != null && f.id() == previous).findFirst().orElse(floors.get(0));
        floorBox.setValue(match);
        floor = match;
        addFloor.setVisible(ctx.canManage());
        addTable.setVisible(ctx.canManage() && editMode);
        rebuildCanvas();
    }

    private void setEditMode(boolean edit) {
        if (edit && !ctx.canManage()) return;
        editMode = edit;
        liveBtn.setSelected(!edit);
        editBtn.setSelected(edit);
        addTable.setVisible(edit);
        rebuildCanvas();
        showDetails(selectedTableId == null ? null : ctx.tables().table(selectedTableId));
    }

    private void rebuildCanvas() {
        canvas.getChildren().clear();
        if (floor == null) {
            canvas.setPrefSize(900, 560);
            canvas.getChildren().add(Ui.empty("No floors configured", "Create a floor to begin laying out tables."));
            legend.setText("");
            return;
        }
        double w = Math.max(900, floor.width());
        double h = Math.max(560, floor.height());
        canvas.setPrefSize(w, h);
        canvas.setMinSize(w, h);
        Canvas bg = new Canvas(w, h);
        drawBackground(bg, floor);
        canvas.getChildren().add(bg);

        Map<Long, Order> active = new HashMap<>();
        for (DiningTable t : ctx.tables().tables(floor.id(), true)) {
            Order o = ctx.orders().openOrderForTable(t.id());
            if (o != null) active.put(t.id(), o);
        }
        for (DiningTable t : ctx.tables().tables(floor.id(), true)) {
            canvas.getChildren().add(tableNode(t, active.get(t.id())));
        }
        if (ctx.tables().tables(floor.id(), true).isEmpty()) {
            canvas.getChildren().add(Ui.empty("This floor has no tables", "Use Add table to place the first table."));
        }
        int total = ctx.tables().totalTables(floor.id());
        int occ = ctx.tables().occupied(floor.id());
        legend.setText(occ + " of " + total + " tables occupied  ·  " + (editMode ? "Drag tables to arrange, positions save automatically" : "Double-click an available table to start an order"));
        showDetails(selectedTableId == null ? null : ctx.tables().table(selectedTableId));
    }

    private void drawBackground(Canvas bg, FloorPlan f) {
        GraphicsContext g = bg.getGraphicsContext2D();
        g.setFill(Color.web("#fbf9f5"));
        g.fillRect(0, 0, bg.getWidth(), bg.getHeight());
        g.setStroke(Color.web("#eee8de"));
        g.setLineWidth(0.6);
        for (int x = 0; x < bg.getWidth(); x += 50) g.strokeLine(x, 0, x, bg.getHeight());
        for (int y = 0; y < bg.getHeight(); y += 50) g.strokeLine(0, y, bg.getWidth(), y);
        g.setFill(Color.web("#f0ebe3"));
        g.fillRoundRect(24, 22, 220, 54, 10, 10);
        g.fillRoundRect(bg.getWidth() - 260, 22, 236, 54, 10, 10);
        g.fillRoundRect(bg.getWidth() / 2 - 80, bg.getHeight() - 44, 160, 24, 8, 8);
        g.setFill(Color.web("#777d84"));
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("COUNTER / TILL", 134, 54);
        g.fillText("SERVICE PASS", bg.getWidth() - 142, 54);
        g.setFont(Font.font("Segoe UI", 10));
        g.fillText("ENTRANCE", bg.getWidth() / 2, bg.getHeight() - 28);
        g.setFill(Color.web("#9a9ea5"));
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 10));
        g.fillText(f.name() + "  ·  " + f.width() + " × " + f.height(), 26, bg.getHeight() - 18);
    }

    private StackPane tableNode(DiningTable t, Order activeOrder) {
        StackPane node = new StackPane();
        node.getStyleClass().add("table-node");
        node.setPrefSize(t.width(), t.height());
        node.setMinSize(t.width(), t.height());
        node.setMaxSize(t.width(), t.height());
        node.setLayoutX(t.x());
        node.setLayoutY(t.y());
        node.setRotate(t.rotation());

        javafx.scene.shape.Shape shape;
        if (t.shape().equals("ROUND")) {
            Circle c = new Circle(Math.min(t.width(), t.height()) / 2);
            shape = c;
        } else {
            Rectangle r = new Rectangle(t.width(), t.height());
            double arc = t.shape().equals("RECTANGLE") ? 12 : 18;
            r.setArcWidth(arc); r.setArcHeight(arc);
            shape = r;
        }
        shape.getStyleClass().add(switch (t.status()) {
            case AVAILABLE -> "table-available";
            case OCCUPIED -> "table-occupied";
            case RESERVED -> "table-reserved";
            case CLEANING -> "table-cleaning";
            case OUT_OF_SERVICE -> "table-out";
        });
        VBox labels = new VBox(-1);
        labels.setAlignment(Pos.CENTER);
        Label no = new Label(t.tableNo());
        no.getStyleClass().add("table-label");
        Label sub = new Label(t.capacity() + " seats");
        sub.getStyleClass().add("table-sub");
        labels.getChildren().addAll(no, sub);
        if (activeOrder != null) {
            Label amt = new Label(Money.fmt(activeOrder.total()));
            amt.getStyleClass().add("table-sub");
            amt.setStyle("-fx-text-fill: #9e3d31; -fx-font-weight: bold;");
            labels.getChildren().add(amt);
        }
        node.getChildren().addAll(shape, labels);
        Tooltip.install(node, new Tooltip(t.tableNo() + " · " + t.capacity() + " seats · " + t.status().label()
                + (activeOrder == null ? "" : "\nOpen order " + activeOrder.orderNo() + " · " + Money.fmt(activeOrder.total()))));
        node.setCursor(editMode ? Cursor.MOVE : Cursor.HAND);

        node.setOnMousePressed(e -> {
            selectedTableId = t.id();
            dragOffsetX = e.getSceneX() - node.localToScene(0, 0).getX();
            dragOffsetY = e.getSceneY() - node.localToScene(0, 0).getY();
            showDetails(t);
            node.toFront();
            if (editMode) e.consume();
        });
        node.setOnMouseDragged(e -> {
            if (!editMode) return;
            double canvasSceneX = canvas.localToScene(0, 0).getX();
            double canvasSceneY = canvas.localToScene(0, 0).getY();
            double newX = Math.max(0, Math.min(canvas.getWidth() - t.width(), e.getSceneX() - canvasSceneX - dragOffsetX));
            double newY = Math.max(0, Math.min(canvas.getHeight() - t.height(), e.getSceneY() - canvasSceneY - dragOffsetY));
            node.setLayoutX(newX);
            node.setLayoutY(newY);
        });
        node.setOnMouseReleased(e -> {
            if (editMode) {
                ctx.tables().updatePosition(t.id(), node.getLayoutX(), node.getLayoutY());
                selectedTableId = t.id();
                showDetails(ctx.tables().table(t.id()));
            }
        });
        node.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !editMode) {
                if (t.status() == TableStatus.AVAILABLE || t.status() == TableStatus.RESERVED || t.status() == TableStatus.CLEANING
                        || t.status() == TableStatus.OCCUPIED) {
                    Order o = ctx.orders().openOrderForTable(t.id());
                    if (o == null) {
                        try {
                            ctx.tables().assignTable(t.id(), ctx.session());
                            nav.startOrder(t.id());
                        } catch (Exception ex) { Ui.error(nav.window(), ex); }
                    } else {
                        orderDetails(o);
                    }
                }
            }
        });
        Animations.pop(node);
        return node;
    }

    private void showDetails(DiningTable t) {
        details.getChildren().clear();
        if (t == null) {
            details.getChildren().add(Ui.empty("Select a table", "Click a table to see its status, active order and actions."));
            return;
        }
        selectedTableId = t.id();
        lastStatus = t.status();
        Order active = ctx.orders().openOrderForTable(t.id());
        Section section = ctx.tables().sections(t.floorId()).stream().filter(s -> t.sectionId() != null && s.id() == t.sectionId()).findFirst().orElse(null);
        HBox title = new HBox(8, Ui.label(t.tableNo(), "h1"), Ui.spacer(), Ui.chip(t.status().label(), statusChip(t.status())));
        title.setAlignment(Pos.CENTER_LEFT);
        VBox summary = new VBox(4, title,
                Ui.muted((section == null ? "No section" : section.name()) + "  ·  " + t.capacity() + " seats  ·  " + t.shape().toLowerCase(Locale.ROOT)));
        details.getChildren().add(summary);

        if (active == null) {
            HBox actions = new HBox(7);
            actions.getChildren().addAll(
                    Ui.primary("New order", "fas-plus", () -> nav.startOrder(t.id())),
                    Ui.secondary("Reserve", "fas-calendar-plus", () -> reserveDialog(t)));
            details.getChildren().add(actions);
        } else {
            VBox orderCard = new VBox(5);
            orderCard.getStyleClass().add("card-flat");
            orderCard.getChildren().addAll(
                    new HBox(8, Ui.label(active.orderNo(), "h3"), Ui.spacer(), Ui.chip(active.paymentStatus().label(), chipFor(active.paymentStatus().name()))),
                    Ui.muted(active.type().label() + "  ·  " + Ui.dateTime(active.createdAt())),
                    new HBox(8, Ui.label(Money.fmt(active.total()), "h2"), Ui.spacer(),
                            Ui.muted(ctx.orders().items(active.id()).size() + " items")));
            FlowPane actions = new FlowPane(7, 7);
            actions.getChildren().addAll(
                    Ui.primary("Order", "fas-external-link-alt", () -> {
                        UiState.pendingOrderId = active.id();
                        nav.navigate("orders");
                    }),
                    Ui.secondary("Transfer", "fas-exchange-alt", () -> transferDialog(active)),
                    Ui.ghost("Merge", "fas-compress-arrows-alt", () -> mergeDialog(active)));
            HBox actions2 = new HBox(7,
                    Ui.secondary("Reserve", "fas-calendar-plus", () -> reserveDialog(t)),
                    Ui.ghost("Clear table", "fas-broom", () -> {
                        if (Ui.confirm(nav.window(), "Clear table", "Mark " + t.tableNo() + " as available?")) {
                            try { ctx.tables().clearTable(t.id(), ctx.session()); refresh(); toast("Table cleared."); }
                            catch (Exception e) { Ui.error(nav.window(), e); }
                        }
                    }));
            details.getChildren().addAll(orderCard, actions, actions2);
        }

        ComboBox<TableStatus> statusBox = new ComboBox<>();
        statusBox.getItems().setAll(TableStatus.values());
        statusBox.setValue(t.status());
        statusBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(TableStatus s) { return s == null ? "" : s.label(); }
            @Override public TableStatus fromString(String s) { return null; }
        });
        statusBox.setMaxWidth(Double.MAX_VALUE);
        statusBox.setOnAction(e -> {
            TableStatus chosen = statusBox.getValue();
            if (chosen == null || chosen == t.status()) return;
            try { ctx.tables().setStatus(t.id(), chosen, ctx.session()); refresh(); }
            catch (Exception ex) { Ui.error(nav.window(), ex); }
        });
        details.getChildren().add(Ui.field("Table status", statusBox));

        if (ctx.canManage()) {
            HBox manage = new HBox(7,
                    Ui.secondary("Edit table", "fas-edit", () -> tableDialog(t)),
                    Ui.ghost("Delete", "fas-trash-alt", () -> deleteTable(t)));
            details.getChildren().add(manage);
        }

        List<Reservation> reservations = ctx.tables().reservations(LocalDate.now()).stream()
                .filter(r -> r.tableId() == t.id() && !"CANCELLED".equals(r.status())).toList();
        details.getChildren().add(Ui.label("TODAY'S RESERVATIONS", "field-label"));
        if (reservations.isEmpty()) {
            details.getChildren().add(Ui.muted("No reservations for this table today."));
        } else {
            for (Reservation r : reservations) {
                VBox row = new VBox(1, Ui.label(r.customerName() + "  ·  " + r.partySize() + " guests", "h3"),
                        Ui.muted(r.reservedAt().format(DateTimeFormatter.ofPattern("hh:mm a")) + "  ·  " + r.status()));
                row.getStyleClass().add("card-flat");
                HBox acts = new HBox(4,
                        Ui.ghost("Seat", "fas-chair", () -> { ctx.tables().updateReservation(r.id(), "SEATED", ctx.session()); refresh(); }),
                        Ui.ghost("Cancel", "fas-times", () -> { ctx.tables().updateReservation(r.id(), "CANCELLED", ctx.session()); refresh(); }));
                row.getChildren().add(acts);
                details.getChildren().add(row);
            }
        }
        Animations.fadeIn(details, 220, 0);
    }

    private String statusChip(TableStatus s) {
        return switch (s) {
            case AVAILABLE -> "chip-green";
            case OCCUPIED -> "chip-red";
            case RESERVED -> "chip-blue";
            case CLEANING -> "chip-amber";
            case OUT_OF_SERVICE -> "chip-dark";
        };
    }

    private String chipFor(String status) {
        return switch (status) {
            case "PAID" -> "chip-green";
            case "PARTIAL", "UNPAID" -> "chip-amber";
            case "REFUNDED", "PARTIALLY_REFUNDED" -> "chip-red";
            default -> "chip-dark";
        };
    }

    private void transferDialog(Order order) {
        TablePicker picker = new TablePicker(ctx, nav.window(), "Transfer " + order.orderNo() + " to another table");
        picker.showAndWait().ifPresent(target -> {
            try {
                ctx.orders().transferTable(order.id(), target.id(), ctx.session());
                refresh(); toast("Table transferred to " + target.tableNo() + ".");
            } catch (Exception e) { Ui.error(nav.window(), e); }
        });
    }

    private void mergeDialog(Order active) {
        List<Order> open = ctx.orders().openOrders().stream()
                .filter(o -> o.id() != active.id() && o.tableId() != null).toList();
        if (open.isEmpty()) { warn("There are no other open table orders to merge."); return; }
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Merge tables");
        d.setHeaderText("Move another open order into " + active.orderNo());
        TableView<Order> table = Ui.table();
        table.getColumns().addAll(
                Ui.col("Order", Order::orderNo, 150),
                Ui.col("Table", o -> o.tableNo() == null ? "—" : o.tableNo(), 80),
                Ui.moneyCol("Total", Order::total, 100),
                Ui.col("Time", o -> Ui.dateTime(o.createdAt()), 140));
        table.getItems().setAll(open);
        table.setPrefSize(560, 280);
        VBox box = new VBox(10, Ui.muted("Only unpaid open orders can be merged. Table status will update automatically."), table);
        box.setPadding(new Insets(10, 2, 2, 2));
        d.getDialogPane().setContent(box);
        ButtonType merge = new ButtonType("Merge into " + active.tableNo(), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, merge);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(merge);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> { if (table.getSelectionModel().getSelectedItem() == null) e.consume(); });
        if (d.showAndWait().filter(b -> b == merge).isPresent()) {
            Order source = table.getSelectionModel().getSelectedItem();
            if (source == null) return;
            try {
                ctx.orders().merge(source.id(), active.id(), ctx.session());
                refresh(); toast("Orders merged.");
            } catch (Exception e) { Ui.error(nav.window(), e); }
        }
    }

    private void reservationsDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Today's reservations");
        d.setHeaderText(null);
        TableView<Reservation> table = Ui.table();
        table.getColumns().addAll(
                Ui.col("Table", Reservation::tableNo, 70),
                Ui.col("Customer", Reservation::customerName, 150),
                Ui.col("Phone", r -> r.phone() == null ? "" : r.phone(), 110),
                Ui.col("Party", r -> String.valueOf(r.partySize()), 60),
                Ui.col("Time", r -> r.reservedAt().format(DateTimeFormatter.ofPattern("hh:mm a")), 90),
                Ui.col("Status", Reservation::status, 80));
        table.getItems().setAll(ctx.tables().reservations(LocalDate.now()));
        table.setPrefSize(660, 340);
        VBox box = new VBox(10, Ui.muted("Reservations for " + Validators.DATE.format(LocalDate.now()) + "."), table);
        box.setPadding(new Insets(10, 2, 2, 2));
        d.getDialogPane().setContent(box);
        ButtonType seat = new ButtonType("Seat", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel reservation", ButtonBar.ButtonData.OTHER);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE, cancel, seat);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        for (ButtonType bt : new ButtonType[]{seat, cancel}) {
            Button b = (Button) d.getDialogPane().lookupButton(bt);
            b.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                Reservation selected = table.getSelectionModel().getSelectedItem();
                if (selected == null) { ev.consume(); return; }
                ctx.tables().updateReservation(selected.id(), bt == seat ? "SEATED" : "CANCELLED", ctx.session());
                table.getItems().setAll(ctx.tables().reservations(LocalDate.now()));
                refresh();
                ev.consume();
            });
        }
        d.showAndWait();
    }

    private void reserveDialog(DiningTable t) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Reserve " + t.tableNo());
        d.setHeaderText(null);
        TextField name = Ui.textField("Customer name");
        TextField phone = Ui.textField("Phone");
        Spinner<Integer> party = new Spinner<>(1, Math.max(1, t.capacity()), Math.min(2, t.capacity()));
        party.setEditable(true);
        DatePicker date = Ui.datePicker(LocalDate.now());
        TextField time = Ui.textField("hh:mm (24h)");
        time.setText("19:30");
        TextArea notes = Ui.textArea("Notes", 2);
        GridPane form = Ui.form(2);
        form.add(Ui.field("Customer", name), 0, 0);
        form.add(Ui.field("Phone", phone), 1, 0);
        form.add(Ui.field("Party size", party), 0, 1);
        form.add(Ui.field("Date", date), 1, 1);
        form.add(Ui.field("Time (24h)", time), 0, 2);
        form.add(Ui.field("Notes", notes), 0, 3, 2, 1);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, form, error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(460);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Create reservation", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                LocalTime lt = LocalTime.parse(time.getText().trim());
                LocalDateTime at = LocalDateTime.of(date.getValue(), lt);
                ctx.tables().reserve(t.id(), name.getText(), phone.getText(), party.getValue(), at, notes.getText(), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Reservation created."); }
    }

    private void floorDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Restaurant floor");
        d.setHeaderText(null);
        TextField name = Ui.textField("Floor name (e.g. Rooftop)");
        TextField desc = Ui.textField("Description");
        Spinner<Integer> width = new Spinner<>(500, 3000, floor == null ? 1100 : floor.width(), 50);
        Spinner<Integer> height = new Spinner<>(400, 2000, floor == null ? 700 : floor.height(), 50);
        width.setEditable(true); height.setEditable(true);
        GridPane form = Ui.form(2);
        form.add(Ui.field("Name", name), 0, 0);
        form.add(Ui.field("Description", desc), 1, 0);
        form.add(Ui.field("Canvas width", width), 0, 1);
        form.add(Ui.field("Canvas height", height), 1, 1);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, form, error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(460);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Save floor", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.tables().saveFloor(new FloorPlan(0, name.getText(), desc.getText(), width.getValue(), height.getValue(), true, 10), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Floor saved."); }
    }

    private void tableDialog() { tableDialog(null); }

    private void tableDialog(DiningTable existing) {
        if (floor == null) { warn("Create a floor first."); return; }
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(existing == null ? "Add table" : "Edit " + existing.tableNo());
        d.setHeaderText(null);
        TextField no = Ui.textField("Table number");
        Spinner<Integer> capacity = new Spinner<>(1, 30, existing == null ? 4 : existing.capacity());
        capacity.setEditable(true);
        ComboBox<String> shape = new ComboBox<>();
        shape.getItems().addAll("ROUND", "SQUARE", "RECTANGLE");
        shape.setValue(existing == null ? "ROUND" : existing.shape());
        shape.setMaxWidth(Double.MAX_VALUE);
        List<Section> sections = ctx.tables().sections(floor.id());
        ComboBox<Section> section = new ComboBox<>();
        section.getItems().setAll(sections);
        section.setMaxWidth(Double.MAX_VALUE);
        section.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Section s) { return s == null ? "No section" : s.name(); }
            @Override public Section fromString(String s) { return null; }
        });
        if (existing != null) sections.stream().filter(s -> s.id() == (existing.sectionId() == null ? -1 : existing.sectionId()))
                .findFirst().ifPresent(section::setValue);
        Button addSection = Ui.ghost("Add section", "fas-plus", () -> {
            String name = Ui.prompt(nav.window(), "New section", "Create a section on " + floor.name(), "Section name", "");
            if (name != null && !name.isBlank()) {
                try {
                    ctx.tables().saveSection(floor.id(), name, null, ctx.session());
                    section.getItems().setAll(ctx.tables().sections(floor.id()));
                } catch (Exception ex) { Ui.error(nav.window(), ex); }
            }
        });
        Spinner<Integer> width = new Spinner<>(50, 400, existing == null ? 100 : (int) existing.width(), 10);
        Spinner<Integer> height = new Spinner<>(50, 300, existing == null ? 100 : (int) existing.height(), 10);
        ComboBox<TableStatus> status = new ComboBox<>();
        status.getItems().setAll(TableStatus.values());
        status.setValue(existing == null ? TableStatus.AVAILABLE : existing.status());
        status.setMaxWidth(Double.MAX_VALUE);
        status.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(TableStatus s) { return s == null ? "" : s.label(); }
            @Override public TableStatus fromString(String s) { return null; }
        });
        CheckBox active = new CheckBox("Table is active");
        active.setSelected(existing == null || existing.active());
        GridPane form = Ui.form(2);
        form.add(Ui.field("Table number", no), 0, 0);
        form.add(Ui.field("Seats", capacity), 1, 0);
        form.add(Ui.field("Shape", shape), 0, 1);
        form.add(new VBox(4, Ui.field("Section", section), addSection), 1, 1);
        form.add(Ui.field("Width", width), 0, 2);
        form.add(Ui.field("Height", height), 1, 2);
        form.add(Ui.field("Status", status), 0, 3);
        form.add(active, 1, 3);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(12, form, Ui.muted("Floor: " + floor.name() + ". Drag the table after saving to position it."), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(500);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Save table", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.tables().saveTable(new DiningTable(existing == null ? 0 : existing.id(), floor.id(),
                        section.getValue() == null ? null : section.getValue().id(), no.getText(),
                        shape.getValue(), capacity.getValue(),
                        existing == null ? 70 + (ctx.tables().tables(floor.id(), true).size() % 5) * 130 : existing.x(),
                        existing == null ? 130 + (ctx.tables().tables(floor.id(), true).size() / 5) * 140 : existing.y(),
                        width.getValue(), height.getValue(), existing == null ? 0 : existing.rotation(),
                        status.getValue(), active.isSelected()), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast("Table saved."); }
    }

    private void deleteTable(DiningTable t) {
        if (!Ui.confirm(nav.window(), "Delete table", "Delete " + t.tableNo() + "? Tables with historical orders are deactivated instead.")) return;
        try { ctx.tables().deleteTable(t.id(), ctx.session()); selectedTableId = null; showDetails(null); refresh(); toast("Table removed."); }
        catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void orderDetails(Order order) {
        if (order == null) return;
        UiState.pendingOrderId = order.id();
        nav.navigate("orders");
    }
}
