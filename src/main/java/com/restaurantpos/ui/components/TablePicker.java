package com.restaurantpos.ui.components;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.model.Floor.DiningTable;
import com.restaurantpos.model.Floor.FloorPlan;
import com.restaurantpos.ui.animation.Animations;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

import java.util.List;

public final class TablePicker extends Dialog<DiningTable> {
    private final AppContext ctx;
    private final FlowPane grid = new FlowPane(10, 10);
    private final Button useTableButton;
    private DiningTable selected;
    private long floorId;

    public TablePicker(AppContext ctx, Window owner, String title) {
        this.ctx = ctx;
        this.ctx.getClass();
        initOwner(owner);
        setTitle(title);
        setHeaderText(null);
        setResizable(true);

        List<FloorPlan> floors = ctx.tables().floors(true);
        HBox floorTabs = new HBox(8);
        floorTabs.setPadding(new Insets(0, 0, 8, 0));
        ToggleGroup group = new ToggleGroup();
        for (FloorPlan f : floors) {
            ToggleButton b = new ToggleButton(f.name());
            b.setToggleGroup(group);
            b.getStyleClass().add("table-toggle");
            b.setUserData(f.id());
            b.setOnAction(e -> { floorId = f.id(); selected = null; rebuild(); });
            floorTabs.getChildren().add(b);
            if (floorId == 0) { floorId = f.id(); b.setSelected(true); }
        }
        Label hint = Ui.muted("Select an available table to proceed. Occupied tables must be cleared first.");
        grid.setPrefWrapLength(680);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(720, 430);
        scroll.getStyleClass().add("edge-to-edge");

        VBox content = new VBox(10, hint, floorTabs, scroll);
        content.setPadding(new Insets(10, 4, 4, 4));
        getDialogPane().setContent(content);
        ButtonType choose = new ButtonType("Use table", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, choose);
        if (getDialogPane().getScene() != null) Ui.applyStyles(getDialogPane().getScene());
        else setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));
        getDialogPane().getStyleClass().add("app-dialog");
        useTableButton = (Button) getDialogPane().lookupButton(choose);
        useTableButton.setDisable(true);
        useTableButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (selected == null) {
                e.consume();
                return;
            }
            // Validate table status one more time before accepting
            DiningTable latest = ctx.tables().table(selected.id());
            if (latest == null || (!latest.active()) || 
                (latest.status() != TableStatus.AVAILABLE && 
                 latest.status() != TableStatus.RESERVED && 
                 latest.status() != TableStatus.CLEANING)) {
                Ui.error(owner, "Table unavailable", 
                    "Table " + selected.tableNo() + " is no longer available. Please select another table.");
                e.consume();
                selected = null;
                rebuild();
            }
        });
        setResultConverter(b -> b == choose ? selected : null);
        rebuild();
        setOnShown(e -> Ui.applyStyles(getDialogPane().getScene()));
    }

    private void rebuild() {
        grid.getChildren().clear();
        List<DiningTable> tables = ctx.tables().tables(floorId, true);
        for (DiningTable t : tables) {
            VBox card = new VBox(4);
            card.setAlignment(Pos.CENTER);
            card.getStyleClass().add("card-flat");
            card.setPrefSize(118, 92);
            card.setPadding(new Insets(10));
            Rectangle shape = new Rectangle(30, 30);
            shape.setArcWidth(t.shape().equals("ROUND") ? 30 : 5);
            shape.setArcHeight(t.shape().equals("ROUND") ? 30 : 5);
            shape.getStyleClass().add(switch (t.status()) {
                case AVAILABLE -> "table-available";
                case OCCUPIED -> "table-occupied";
                case RESERVED -> "table-reserved";
                case CLEANING -> "table-cleaning";
                case OUT_OF_SERVICE -> "table-out";
            });
            Label no = Ui.label(t.tableNo(), "h3");
            Label cap = Ui.muted(t.capacity() + " seats · " + t.status().label());
            card.getChildren().addAll(shape, no, cap);
            boolean selectable = t.status() == TableStatus.AVAILABLE || t.status() == TableStatus.RESERVED || t.status() == TableStatus.CLEANING;
            card.setOpacity(selectable ? 1 : 0.55);
            if (selectable) {
                card.setCursor(javafx.scene.Cursor.HAND);
                if (selected != null && selected.id() == t.id()) {
                    card.getStyleClass().add("selected-table");
                    card.setStyle("-fx-border-color: -pos-accent; -fx-border-width: 2; -fx-border-radius: 8;");
                }
                card.setOnMouseClicked(e -> {
                    selected = t;
                    grid.getChildren().forEach(n -> {
                        n.getStyleClass().remove("selected-table");
                        n.setStyle("");
                    });
                    card.getStyleClass().add("selected-table");
                    card.setStyle("-fx-border-color: -pos-accent; -fx-border-width: 2; -fx-border-radius: 8;");
                    updateButtonState();
                });
                card.setOnMouseEntered(e -> Animations.hoverLift(card));
                card.setOnMouseExited(e -> Animations.hoverReset(card));
            }
            grid.getChildren().add(card);
        }
        if (tables.isEmpty()) grid.getChildren().add(Ui.empty("No tables on this floor", "Add tables in Floors & Tables."));
        updateButtonState();
    }

    private void updateButtonState() {
        if (useTableButton != null) {
            useTableButton.setDisable(selected == null);
        }
    }
}
