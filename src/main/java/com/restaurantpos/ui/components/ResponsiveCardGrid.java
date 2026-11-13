package com.restaurantpos.ui.components;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

public final class ResponsiveCardGrid extends GridPane {
    private final List<Node> cards = new ArrayList<>();
    private final double minCardWidth;
    private final int minColumns;
    private final int maxColumns;
    private int columns = -1;

    public ResponsiveCardGrid(double gap, double minCardWidth, int maxColumns) {
        this(gap, minCardWidth, 1, maxColumns);
    }

    public ResponsiveCardGrid(double gap, double minCardWidth, int minColumns, int maxColumns) {
        this.minCardWidth = Math.max(100, minCardWidth);
        this.minColumns = Math.max(1, minColumns);
        this.maxColumns = Math.max(this.minColumns, maxColumns);
        setHgap(gap);
        setVgap(gap);
        setMaxWidth(Double.MAX_VALUE);
        setPadding(Insets.EMPTY);
        widthProperty().addListener((obs, old, val) -> reflow(val.doubleValue()));
    }

    public void setCards(List<? extends Node> items) {
        cards.clear();
        if (items != null) {
            cards.addAll(items);
        }
        columns = -1;
        reflow(getWidth());
    }

    public int columnCount() { return Math.max(1, columns); }

    private void reflow(double width) {
        if (cards.isEmpty()) {
            getChildren().clear();
            getColumnConstraints().clear();
            columns = -1;
            return;
        }

        double hPadding = getPadding() != null ? getPadding().getLeft() + getPadding().getRight() : 0.0;
        double available = (width > hPadding) ? (width - hPadding) : (minCardWidth * minColumns);
        int next = (int) Math.floor((available + getHgap()) / (minCardWidth + getHgap()));
        next = Math.max(minColumns, Math.min(maxColumns, next));

        boolean isSingleNonCard = cards.size() == 1
                && !(cards.get(0).getStyleClass().contains("product-card") || cards.get(0).getStyleClass().contains("stat-card"));

        if (next == columns && getChildren().size() == cards.size()) return;
        columns = next;
        getChildren().clear();
        getColumnConstraints().clear();

        for (int i = 0; i < columns; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / columns);
            cc.setHgrow(Priority.ALWAYS);
            cc.setFillWidth(true);
            getColumnConstraints().add(cc);
        }

        if (isSingleNonCard) {
            Node emptyNode = cards.get(0);
            GridPane.setColumnSpan(emptyNode, columns);
            GridPane.setHgrow(emptyNode, Priority.ALWAYS);
            GridPane.setFillWidth(emptyNode, true);
            add(emptyNode, 0, 0);
            requestLayout();
            return;
        }

        for (int i = 0; i < cards.size(); i++) {
            Node card = cards.get(i);
            if (card.getStyleClass().contains("stat-card") || card.getStyleClass().contains("product-card")) {
                String existing = card.getStyle();
                card.setStyle((existing == null || existing.isBlank() ? "" : existing + "; ") + "-fx-min-width: 0; -fx-pref-width: 0;");
            }
            GridPane.setHgrow(card, Priority.ALWAYS);
            GridPane.setFillWidth(card, true);
            add(card, i % columns, i / columns);
        }
        requestLayout();
    }
}
