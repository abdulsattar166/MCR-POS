package com.restaurantpos.ui.components;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.ui.animation.Animations;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Ui {
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    public static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private Ui() {}

    public static Label label(String text, String... classes) {
        Label l = new Label(text);
        l.getStyleClass().addAll(classes);
        return l;
    }

    public static Label h1(String text) { return label(text, "h1"); }
    public static Label h2(String text) { return label(text, "h2"); }
    public static Label h3(String text) { return label(text, "h3"); }
    public static Label muted(String text) { return label(text, "muted"); }
    public static Label body(String text) { return label(text, "body"); }

    public static Label chip(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", styleClass);
        return l;
    }

    public static FontIcon icon(String literal, int size) {
        FontIcon i = new FontIcon(literal);
        i.setIconSize(size);
        return i;
    }

    public static Button button(String text, String iconLiteral, String styleClass, Runnable action) {
        Button b = new Button(text);
        if (iconLiteral != null) b.setGraphic(icon(iconLiteral, 14));
        b.getStyleClass().add(styleClass);
        b.setOnAction(e -> action.run());
        b.setCursor(javafx.scene.Cursor.HAND);
        return b;
    }

    public static Button primary(String text, String iconLiteral, Runnable action) {
        return button(text, iconLiteral, "primary-button", action);
    }

    public static Button secondary(String text, String iconLiteral, Runnable action) {
        return button(text, iconLiteral, "secondary-button", action);
    }

    public static Button danger(String text, String iconLiteral, Runnable action) {
        return button(text, iconLiteral, "danger-button", action);
    }

    public static Button ghost(String text, String iconLiteral, Runnable action) {
        return button(text, iconLiteral, "ghost-button", action);
    }

    public static Button iconButton(String iconLiteral, String tooltip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon(iconLiteral, 15));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        b.setCursor(javafx.scene.Cursor.HAND);
        return b;
    }

    public static VBox card(Node... children) {
        VBox v = new VBox(10, children);
        v.getStyleClass().add("card");
        v.setPadding(new Insets(18));
        return v;
    }

    public static VBox card(String title, String iconLiteral, Node... children) {
        HBox titleRow = new HBox(9);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (iconLiteral != null) {
            FontIcon i = icon(iconLiteral, 16);
            i.getStyleClass().add("accent-icon");
            titleRow.getChildren().add(i);
        }
        titleRow.getChildren().add(h3(title));
        VBox v = new VBox(12, titleRow);
        v.getChildren().addAll(children);
        v.getStyleClass().add("card");
        v.setPadding(new Insets(18));
        return v;
    }

    public static Region spacer() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); VBox.setVgrow(r, Priority.ALWAYS); return r; }

    public static HBox row(double spacing, Node... nodes) {
        HBox h = new HBox(spacing, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static VBox column(double spacing, Node... nodes) { return new VBox(spacing, nodes); }

    public static TextField textField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.getStyleClass().add("text-field");
        return f;
    }

    public static PasswordField passwordField(String prompt) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        f.getStyleClass().add("text-field");
        return f;
    }

    public static TextArea textArea(String prompt, int rows) {
        TextArea a = new TextArea();
        a.setPromptText(prompt);
        a.setPrefRowCount(rows);
        a.setWrapText(true);
        a.getStyleClass().add("text-area");
        return a;
    }

    public static ComboBox<String> combo(List<String> items, String prompt) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(items));
        c.setPromptText(prompt);
        c.setMaxWidth(Double.MAX_VALUE);
        c.getStyleClass().add("combo-box");
        return c;
    }

    public static DatePicker datePicker(LocalDate value) {
        DatePicker d = new DatePicker(value);
        d.setMaxWidth(Double.MAX_VALUE);
        d.getStyleClass().add("date-picker");
        return d;
    }

    public static Label fieldLabel(String text) { return label(text, "field-label"); }

    public static VBox field(String label, Node input) {
        VBox v = new VBox(5, fieldLabel(label), input);
        VBox.setVgrow(input, Priority.NEVER);
        return v;
    }

    public static GridPane form(int columns) {
        GridPane g = new GridPane();
        g.setHgap(16); g.setVgap(12);
        for (int i = 0; i < columns; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setHgrow(Priority.ALWAYS); cc.setPercentWidth(100.0 / columns);
            g.getColumnConstraints().add(cc);
        }
        return g;
    }

    public static ScrollPane scroll(Node content) {
        ScrollPane s = new ScrollPane(content);
        s.setFitToWidth(true);
        s.setFitToHeight(false);
        s.setPannable(true);
        s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        s.getStyleClass().add("edge-to-edge");
        return s;
    }

    public static ScrollPane scrollBoth(Node content) {
        ScrollPane s = new ScrollPane(content);
        s.setFitToHeight(true);
        s.setFitToWidth(true);
        s.setPannable(true);
        s.getStyleClass().add("edge-to-edge");
        return s;
    }

    public static Label moneyLabel(BigDecimal amount, String styleClass) {
        return label(Money.fmt(amount), styleClass);
    }

    public static String money(BigDecimal v) { return Money.fmt(v); }

    public static String dateTime(LocalDateTime dt) { return dt == null ? "—" : dt.format(DATETIME); }
    public static String date(LocalDate d) { return d == null ? "—" : d.format(DATE); }

    public static ImageView image(String relative, double width, double height) {
        Path p = Images.resolve(relative);
        ImageView view;
        try {
            Image img = (p != null && Files.isRegularFile(p)) ? new Image(p.toUri().toString(), width, height, true, true, false) : null;
            view = img == null ? null : new ImageView(img);
        } catch (Exception e) { view = null; }
        if (view == null) {
            view = new ImageView();
            view.setFitWidth(width);
            view.setFitHeight(height);
        }
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(width, height);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        view.setClip(clip);
        return view;
    }

    public static StackPane imagePane(String relative, double width, double height, String fallback) {
        StackPane pane = new StackPane();
        pane.setPrefSize(width, height);
        pane.setMinSize(width, height);
        pane.setMaxSize(width, height);
        pane.getStyleClass().add("image-frame");

        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(pane.widthProperty());
        clip.heightProperty().bind(pane.heightProperty());
        pane.setClip(clip);

        Path p = Images.resolve(relative);
        boolean exists = p != null && Files.isRegularFile(p);
        if (exists) {
            ImageView view = image(relative, width, height);
            view.fitWidthProperty().bind(pane.widthProperty());
            view.fitHeightProperty().bind(pane.heightProperty());
            pane.getChildren().add(view);
        } else {
            Label l = label(fallback == null || fallback.isBlank() ? "?" : fallback.substring(0, 1).toUpperCase(java.util.Locale.ROOT), "image-fallback");
            l.setAlignment(Pos.CENTER);
            pane.getChildren().add(l);
        }
        return pane;
    }

    public static <T> TableView<T> table() {
        TableView<T> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(empty("No records found", "Records will appear here once data is available."));
        t.getStyleClass().add("data-table");
        return t;
    }

    public static <T> TableColumn<T, String> col(String title, Function<T, String> value, double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setMinWidth(50);
        return c;
    }

    public static <T> TableColumn<T, String> moneyCol(String title, Function<T, BigDecimal> value, double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> {
            BigDecimal v = value.apply(cd.getValue());
            return new javafx.beans.property.ReadOnlyStringWrapper(v == null ? "" : Money.fmt(v));
        });
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.setPrefWidth(width);
        return c;
    }

    public static VBox empty(String title, String message) {
        FontIcon i = icon("fas-inbox", 34);
        i.getStyleClass().add("empty-icon");
        VBox v = new VBox(8, i, h3(title), muted(message));
        v.setAlignment(Pos.CENTER);
        v.setPadding(new Insets(36));
        return v;
    }

    public static void applyStyles(Scene scene) {
        var url = Ui.class.getResource("/css/app.css");
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    private static void styleDialog(Dialog<?> dialog) {
        if (dialog.getDialogPane().getScene() != null) applyStyles(dialog.getDialogPane().getScene());
        else dialog.setOnShown(e -> applyStyles(dialog.getDialogPane().getScene()));
        dialog.getDialogPane().getStyleClass().add("app-dialog");
    }

    public static void error(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        styleDialog(a);
        a.showAndWait();
    }

    public static void error(Window owner, Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        error(owner, "Something went wrong", msg);
    }

    public static void info(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        styleDialog(a);
        a.showAndWait();
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        a.getButtonTypes().setAll(new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE),
                new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE));
        styleDialog(a);
        return a.showAndWait().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).isPresent();
    }

    public static String prompt(Window owner, String title, String header, String prompt, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(header);
        d.setContentText(prompt);
        styleDialog(d);
        return d.showAndWait().orElse(null);
    }

    public static File chooseImage(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        return chooser.showOpenDialog(owner);
    }

    public static void toast(StackPane host, String message, String severity) {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(430);
        box.getStyleClass().addAll("toast", "toast-" + severity.toLowerCase());
        FontIcon i = icon(switch (severity) {
            case "SUCCESS" -> "fas-check-circle";
            case "WARNING", "CRITICAL" -> "fas-exclamation-triangle";
            default -> "fas-info-circle";
        }, 16);
        Label text = label(message, "toast-text");
        text.setWrapText(true);
        box.getChildren().addAll(i, text);
        StackPane.setAlignment(box, Pos.TOP_RIGHT);
        StackPane.setMargin(box, new Insets(18, 22, 0, 0));
        host.getChildren().add(box);
        box.setOpacity(0);
        box.setTranslateX(20);
        FadeTransition ft = new FadeTransition(Duration.millis(180), box);
        ft.setToValue(1);
        TranslateTransition tt = new TranslateTransition(Duration.millis(220), box);
        tt.setToX(0);
        PauseTransition wait = new PauseTransition(Duration.seconds(4));
        FadeTransition out = new FadeTransition(Duration.millis(280), box);
        out.setToValue(0);
        out.setOnFinished(e -> host.getChildren().remove(box));
        new SequentialTransition(new ParallelTransition(ft, tt), wait, out).play();
    }

    public static void notify(StackPane host, String message) { toast(host, message, "SUCCESS"); }

    public static HBox toolbar(Node... items) {
        HBox h = new HBox(10, items);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("toolbar");
        return h;
    }

    public static HBox pageHeader(String title, String subtitle, Node... actions) {
        VBox text = new VBox(3, h1(title), muted(subtitle));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox h = new HBox(12, text);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getChildren().addAll(actions);
        h.getStyleClass().add("page-header");
        return h;
    }

    public static void setTitledPaneContent(TableView<?> table) { table.setPlaceholder(empty("Nothing here yet", "")); }

    public static void showTextPreview(Window owner, String title, String text) {
        Dialog<Void> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle(title);
        d.setResizable(true);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setFont(javafx.scene.text.Font.font("Consolas", 12));
        area.setPrefSize(560, 620);
        area.setStyle("-fx-font-family: 'Consolas';");
        d.getDialogPane().setContent(area);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        styleDialog(d);
        d.showAndWait();
    }

    public static void bindSearch(TextField field, Runnable action) {
        field.textProperty().addListener((obs, o, n) -> action.run());
        field.setOnAction(e -> action.run());
    }

    public static void flash(Node node, String severity) {
        node.getStyleClass().add("flash-" + severity);
        PauseTransition p = new PauseTransition(Duration.millis(900));
        p.setOnFinished(e -> node.getStyleClass().remove("flash-" + severity));
        p.play();
    }
}
