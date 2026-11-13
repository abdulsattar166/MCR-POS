package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.enums.CashMoveType;
import com.restaurantpos.enums.ShiftStatus;
import com.restaurantpos.model.Sales.CashMovement;
import com.restaurantpos.model.Sales.Shift;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Tooltip;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.ui.components.ResponsiveCardGrid;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ShiftView extends AppView {
    private final VBox currentCard = new VBox(12);
    private final VBox historyCard = new VBox(10);
    private final Label statusChip = new Label();

    public ShiftView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        
        VBox content = new VBox(16);
        content.setPadding(new Insets(0));
        
        content.getChildren().add(Ui.pageHeader("My Shift & Cash", "Open your drawer, record cash movements and reconcile at the end of the shift.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));
        currentCard.getStyleClass().add("card");
        currentCard.setPadding(new Insets(18));
        historyCard.getStyleClass().add("card");
        historyCard.setPadding(new Insets(18));
        content.getChildren().addAll(currentCard, historyCard);
        VBox.setVgrow(historyCard, Priority.ALWAYS);
        
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        
        getChildren().add(scroll);
        refresh();
    }

    @Override
    public void refresh() {
        Shift shift = ctx.shifts().current(ctx.session().userId());
        currentCard.getChildren().clear();
        HBox header = new HBox(10, Ui.h2("Current shift"), statusChip, Ui.spacer());
        header.setAlignment(Pos.CENTER_LEFT);
        currentCard.getChildren().add(header);
        if (shift == null) {
            statusChip.setText("No open shift");
            statusChip.getStyleClass().setAll("chip", "chip-amber");
            currentCard.getChildren().add(Ui.muted("Payments require an open shift. Count your opening cash and start the shift."));
            TextField opening = Ui.textField("Opening cash (PKR)");
            opening.setText("0");
            Button open = Ui.primary("Open shift", "fas-play", () -> {
                try {
                    ctx.shifts().open(Money.of(opening.getText()), ctx.session());
                    refresh();
                    toast("Shift opened.");
                } catch (Exception e) { Ui.error(nav.window(), e); }
            });
            opening.setPrefWidth(220);
            currentCard.getChildren().add(new HBox(10, Ui.field("Opening cash", opening), open));
            currentCard.getChildren().add(new Separator());
            currentCard.getChildren().add(Ui.muted("Owner accounts may take payments without a shift, but a shift is recommended for cash reconciliation."));
        } else {
            statusChip.setText("Shift #" + shift.id() + " open");
            statusChip.getStyleClass().setAll("chip", "chip-green");
            BigDecimal cashSales = ctx.financeRepo().cashSales(shift.id());
            BigDecimal cashIn = ctx.financeRepo().cashIn(shift.id());
            BigDecimal cashOut = ctx.financeRepo().cashOut(shift.id());
            BigDecimal cashRefunds = ctx.financeRepo().cashRefunds(shift.id());
            BigDecimal expected = ctx.shifts().expected(shift.id());
            BigDecimal totalSales = ctx.shifts().sales(shift.id());
            ResponsiveCardGrid shiftStats = new ResponsiveCardGrid(12, 200, 6);
            shiftStats.setCards(java.util.List.of(
                    smallStat("Opening cash", Money.fmt(shift.openingCash()), "fas-door-open", () -> nav.navigate("payments")),
                    smallStat("Cash sales", Money.fmt(cashSales), "fas-money-bill-wave", () -> nav.navigate("payments")),
                    smallStat("Cash in", Money.fmt(cashIn), "fas-arrow-down", () -> cashDialog(CashMoveType.CASH_IN)),
                    smallStat("Cash refunds", Money.fmt(cashRefunds), "fas-arrow-up", () -> nav.navigate("payments")),
                    smallStat("Cash out", Money.fmt(cashOut), "fas-arrow-up", () -> cashDialog(CashMoveType.CASH_OUT)),
                    smallStat("Expected drawer", Money.fmt(expected), "fas-calculator", () -> nav.navigate("payments"))));
            currentCard.getChildren().addAll(shiftStats,
                    Ui.muted("Opened " + Ui.dateTime(shift.openedAt()) + "  ·  " + ctx.shifts().orderCount(shift.id())
                            + " paid order(s)  ·  total collected " + Money.fmt(totalSales)
                            + "  ·  digital " + Money.fmt(Money.sub(totalSales, cashSales))));
            HBox actions = new HBox(8,
                    Ui.secondary("Cash in", "fas-plus", () -> cashDialog(CashMoveType.CASH_IN)),
                    Ui.secondary("Cash out", "fas-minus", () -> cashDialog(CashMoveType.CASH_OUT)),
                    Ui.ghost("Shift report", "fas-file-alt", () -> shiftReport(shift)),
                    Ui.spacer(),
                    Ui.danger("Close shift", "fas-stop", () -> closeDialog(shift)));
            actions.setAlignment(Pos.CENTER_LEFT);
            currentCard.getChildren().add(actions);

            List<CashMovement> moves = ctx.shifts().movements(shift.id());
            currentCard.getChildren().add(Ui.label("CASH MOVEMENTS", "field-label"));
            TableView<CashMovement> table = Ui.table();
            table.getColumns().addAll(
                    Ui.col("Time", m -> Ui.dateTime(m.createdAt()), 140),
                    Ui.col("Type", m -> m.type().label(), 90),
                    Ui.moneyCol("Amount", CashMovement::amount, 110),
                    Ui.col("Reason", CashMovement::reason, 300),
                    Ui.col("By", CashMovement::userName, 140));
            table.getItems().setAll(moves);
            table.setPrefHeight(150);
            currentCard.getChildren().add(table);
        }
        loadHistory();
    }

    private VBox smallStat(String label, String value, String icon, Runnable action) {
        VBox box = new VBox(3);
        box.getStyleClass().add("card-flat");
        box.setPadding(new Insets(10, 12, 10, 12));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setStyle("-fx-min-width: 0; -fx-pref-width: 0;");
        HBox top = new HBox(6, Ui.icon(icon, 12), Ui.label(label.toUpperCase(), "stat-label"));
        top.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(top, new Label(value) {{ getStyleClass().add("h2"); }});
        if (action != null) {
            box.setCursor(javafx.scene.Cursor.HAND);
            box.setFocusTraversable(true);
            box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            box.setAccessibleText(label + " card. Activate to open related action.");
            Tooltip.install(box, new Tooltip("Open " + label.toLowerCase()));
            box.setOnMouseClicked(e -> action.run());
            box.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) { action.run(); e.consume(); } });
            box.setOnMouseEntered(e -> Animations.hoverLift(box));
            box.setOnMouseExited(e -> Animations.hoverReset(box));
        }
        return box;
    }

    private VBox smallStat(String label, String value, String icon) {
        return smallStat(label, value, icon, null);
    }

    private void cashDialog(CashMoveType type) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle(type.label());
        d.setHeaderText(null);
        TextField amount = Ui.textField("Amount (PKR)");
        TextArea reason = Ui.textArea("Reason (required)", 2);
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        VBox box = new VBox(11, Ui.muted(type == CashMoveType.CASH_IN
                        ? "Record cash added to the drawer, such as change float." : "Record cash removed for expenses, banking or petty cash."),
                Ui.field("Amount", amount), Ui.field("Reason", reason), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(420);
        d.getDialogPane().setContent(box);
        ButtonType save = new ButtonType("Record " + type.label(), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(save);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                ctx.shifts().cashMovement(type, Money.of(amount.getText()), reason.getText(), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == save).isPresent()) { refresh(); toast(type.label() + " recorded."); }
    }

    private void closeDialog(Shift shift) {
        BigDecimal expected = ctx.shifts().expected(shift.id());
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(nav.window());
        d.setTitle("Close shift #" + shift.id());
        d.setHeaderText(null);
        TextField counted = Ui.textField("Counted cash");
        TextArea notes = Ui.textArea("Closing notes (optional)", 2);
        Label difference = Ui.label("", "h2");
        Label error = Ui.label("", "inline-error"); error.setVisible(false); error.setManaged(false);
        counted.textProperty().addListener((o, a, b) -> {
            try {
                BigDecimal diff = Money.sub(Money.of(b), expected);
                difference.setText((diff.signum() < 0 ? "Short " : diff.signum() > 0 ? "Over " : "Balanced  ")
                        + Money.fmt(diff.abs()));
                difference.getStyleClass().setAll(diff.signum() == 0 ? "success-text" : "danger-text", "h2");
            } catch (Exception e) { difference.setText(""); }
        });
        counted.setText(Money.plain(expected));
        VBox box = new VBox(11,
                Ui.muted("Expected closing cash is calculated from opening cash, cash sales, cash in, cash refunds and cash out. "
                        + "Digital payments are not counted as physical cash."),
                new HBox(20, new VBox(2, Ui.label("EXPECTED DRAWER", "field-label"), Ui.label(Money.fmt(expected), "h2")),
                        new VBox(2, Ui.label("DIFFERENCE", "field-label"), difference)),
                Ui.field("Actual counted cash", counted), Ui.field("Notes", notes), error);
        box.setPadding(new Insets(10, 2, 2, 2)); box.setPrefWidth(480);
        d.getDialogPane().setContent(box);
        ButtonType close = new ButtonType("Close shift", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, close);
        if (d.getDialogPane().getScene() != null) Ui.applyStyles(d.getDialogPane().getScene());
        else d.setOnShown(e -> Ui.applyStyles(d.getDialogPane().getScene()));
        Button ok = (Button) d.getDialogPane().lookupButton(close);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                if (!Ui.confirm(nav.window(), "Close shift", "Close shift #" + shift.id() + " with counted cash "
                        + Money.fmt(Money.of(counted.getText())) + "?\n\nA closed shift cannot be edited.")) { ev.consume(); return; }
                ctx.shifts().close(shift.id(), Money.of(counted.getText()), notes.getText(), ctx.session());
            } catch (Exception ex) {
                error.setText(ex.getMessage()); error.setVisible(true); error.setManaged(true);
                ev.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == close).isPresent()) {
            refresh();
            toast("Shift closed and reconciled.");
        }
    }

    private void loadHistory() {
        historyCard.getChildren().clear();
        HBox head = new HBox(10, Ui.h2(ctx.canManage() ? "Shift history" : "My previous shifts"), Ui.spacer());
        head.setAlignment(Pos.CENTER_LEFT);
        List<Shift> shifts = ctx.shifts().list(LocalDate.now().minusDays(30), LocalDate.now(),
                ctx.canManage() ? null : ctx.session().userId());
        TableView<Shift> table = Ui.table();
        table.getColumns().addAll(
                Ui.col("Shift", s -> "#" + s.id(), 55),
                Ui.col("Cashier", Shift::userName, 140),
                Ui.col("Opened", s -> Ui.dateTime(s.openedAt()), 125),
                Ui.col("Closed", s -> Ui.dateTime(s.closedAt()), 125),
                Ui.col("Duration", Shift::formattedDuration, 80),
                Ui.moneyCol("Opening", Shift::openingCash, 90),
                Ui.moneyCol("Expected", Shift::expectedCash, 95),
                Ui.moneyCol("Counted", Shift::countedCash, 95),
                Ui.moneyCol("Difference", Shift::difference, 90),
                Ui.col("Status", s -> s.status().label(), 75));
        table.getItems().setAll(shifts);
        table.setPrefHeight(250);
        table.setPlaceholder(Ui.empty("No shift history", "Closed shifts appear here for reconciliation."));
        historyCard.getChildren().addAll(head, table);
    }

    private void shiftReport(Shift shift) {
        StringBuilder b = new StringBuilder();
        b.append("MUBARAK CAFE & RESTAURANT — SHIFT REPORT\n");
        b.append("Shift #").append(shift.id()).append("  ·  ").append(shift.userName()).append('\n');
        b.append("Opened:   ").append(Ui.dateTime(shift.openedAt())).append('\n');
        if (shift.closedAt() != null) b.append("Closed:   ").append(Ui.dateTime(shift.closedAt())).append('\n');
        b.append("Duration: ").append(shift.formattedDuration()).append('\n');
        b.append("Status:   ").append(shift.status().label()).append('\n');
        b.append("Cashier:  ").append(shift.userName()).append("\n\n");
        b.append("Opening cash       ").append(Money.fmt(shift.openingCash())).append('\n');
        b.append("Cash sales         ").append(Money.fmt(ctx.financeRepo().cashSales(shift.id()))).append('\n');
        b.append("Cash in            ").append(Money.fmt(ctx.financeRepo().cashIn(shift.id()))).append('\n');
        b.append("Cash refunds       ").append(Money.fmt(ctx.financeRepo().cashRefunds(shift.id()))).append('\n');
        b.append("Cash out           ").append(Money.fmt(ctx.financeRepo().cashOut(shift.id()))).append('\n');
        b.append("Expected drawer    ").append(Money.fmt(ctx.shifts().expected(shift.id()))).append('\n');
        b.append("Total collected    ").append(Money.fmt(ctx.shifts().sales(shift.id()))).append('\n');
        b.append("Paid orders        ").append(ctx.shifts().orderCount(shift.id())).append("\n\n");
        b.append("Cash movements:\n");
        for (CashMovement m : ctx.shifts().movements(shift.id())) {
            b.append(String.format("%-10s %12s  %s%n", m.type().label(), Money.plain(m.amount()), m.reason()));
        }
        Ui.showTextPreview(nav.window(), "Shift #" + shift.id() + " report", b.toString());
    }
}
