package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportExporter;
import com.restaurantpos.service.ReportService;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

public final class ReportsView extends AppView {
    private final ListView<ReportService.ReportDefinition> list = new ListView<>();
    private final DatePicker from = Ui.datePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker to = Ui.datePicker(LocalDate.now());
    private final TableView<Object[]> table = Ui.table();
    private final Label title = Ui.label("", "h2");
    private final Label period = Ui.muted("");
    private final Label summary = Ui.muted("");
    private final ProgressIndicator progress = new ProgressIndicator();
    private ReportData current;

    public ReportsView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Reports & Analytics", "Accurate business reports generated directly from the local database.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::load)));

        list.getItems().setAll(ReportService.DEFINITIONS);
        list.setPrefWidth(225);
        list.getSelectionModel().selectFirst();
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ReportService.ReportDefinition item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
                setPadding(new Insets(9, 10, 9, 10));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> load());
        VBox listCard = new VBox(10, Ui.h3("Report library"), list);
        listCard.getStyleClass().add("card");
        listCard.setPadding(new Insets(14));
        listCard.setMinWidth(200);
        listCard.setPrefWidth(225);
        VBox.setVgrow(list, Priority.ALWAYS);

        FlowPane range = new FlowPane(8, 8, Ui.fieldLabel("From"), from, Ui.fieldLabel("To"), to,
                Ui.button("Today", "fas-calendar-day", "ghost-button", () -> {
                    from.setValue(LocalDate.now()); to.setValue(LocalDate.now()); load();
                }),
                Ui.button("This month", "fas-calendar-alt", "ghost-button", () -> {
                    from.setValue(LocalDate.now().withDayOfMonth(1)); to.setValue(LocalDate.now()); load();
                }),
                Ui.button("This year", "fas-calendar", "ghost-button", () -> {
                    from.setValue(LocalDate.now().withDayOfYear(1)); to.setValue(LocalDate.now()); load();
                }));
        range.setAlignment(Pos.CENTER_LEFT);
        from.valueProperty().addListener((o, a, b) -> load());
        to.valueProperty().addListener((o, a, b) -> load());

        Button excel = Ui.secondary("Excel", "fas-file-excel", () -> export(ReportExporter.Format.EXCEL));
        Button pdf = Ui.secondary("PDF", "fas-file-pdf", () -> export(ReportExporter.Format.PDF));
        Button csv = Ui.secondary("CSV", "fas-file-csv", () -> export(ReportExporter.Format.CSV));
        progress.setPrefSize(20, 20);
        progress.setVisible(false); progress.setManaged(false);
        FlowPane actions = new FlowPane(8, 8, excel, pdf, csv, progress, summary);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox titleRow = new HBox(12, new VBox(2, title, period), Ui.spacer(), actions);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getStyleClass().add("card");
        titleRow.setPadding(new Insets(14));

        VBox.setVgrow(table, Priority.ALWAYS);
        VBox right = new VBox(12, titleRow, range, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(14, listCard, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().add(body);
        load();
    }

    @Override
    public void refresh() { load(); }

    private void load() {
        ReportService.ReportDefinition def = list.getSelectionModel().getSelectedItem();
        if (def == null) return;
        try {
            LocalDate start = def.range() ? from.getValue() : null;
            LocalDate end = def.range() ? to.getValue() : null;
            current = ctx.reports().build(def.key(), start, end);
            title.setText(current.title());
            period.setText(current.period() + "  ·  " + current.rows().size() + " row(s)");
            
            // Clear and reset table completely
            table.getColumns().clear();
            table.getItems().clear();
            table.refresh();
            
            // Force table layout to fixed for consistent column alignment
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
            table.setFixedCellSize(40);
            
            // Calculate total width units for proportional distribution
            double totalWidth = current.columns().stream().mapToDouble(ReportData.Column::width).sum();
            
            for (int c = 0; c < current.columns().size(); c++) {
                final int index = c;
                ReportData.Column col = current.columns().get(c);
                TableColumn<Object[], String> column = new TableColumn<>(col.label());
                
                // Set cell value factory
                column.setCellValueFactory(cd -> new javafx.beans.property.ReadOnlyStringWrapper(
                        index < cd.getValue().length ? ReportData.format(cd.getValue()[index], col.type()) : ""));
                
                // Calculate proportional width as percentage of total
                double widthPercent = (col.width() / totalWidth) * 100.0;
                column.setMinWidth(Math.max(80, widthPercent * 8));
                column.setPrefWidth(widthPercent * 10);
                column.setMaxWidth(widthPercent * 20);
                column.setResizable(true);
                
                // Prevent column reordering which can break alignment
                column.setReorderable(false);
                column.setSortable(true);
                
                // Determine alignment based on column type
                final Pos cellAlignment;
                final String headerAlignment;
                
                if (col.type() == ReportData.ColType.CURRENCY || col.type() == ReportData.ColType.INTEGER) {
                    cellAlignment = Pos.CENTER_RIGHT;
                    headerAlignment = "CENTER-RIGHT";
                } else if (col.type() == ReportData.ColType.DATE || col.type() == ReportData.ColType.DATETIME) {
                    cellAlignment = Pos.CENTER;
                    headerAlignment = "CENTER";
                } else {
                    cellAlignment = Pos.CENTER_LEFT;
                    headerAlignment = "CENTER-LEFT";
                }
                
                // Apply header style directly
                column.setStyle("-fx-alignment: " + headerAlignment + "; -fx-padding: 0 10 0 10;");
                
                // Create unified cell factory with strict alignment
                column.setCellFactory(tc -> new TableCell<Object[], String>() {
                    {
                        // Set padding in constructor to ensure consistency
                        setStyle("-fx-padding: 0 10 0 10;");
                    }
                    
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        
                        // Clear all previous state
                        setText(null);
                        setGraphic(null);
                        getStyleClass().removeAll("report-text-col", "report-numeric-col", "report-currency-col");
                        
                        if (empty || item == null) {
                            setAlignment(Pos.CENTER_LEFT);
                        } else {
                            setText(item);
                            setAlignment(cellAlignment);
                            setWrapText(false);
                            setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                            
                            // Set tooltip for truncated content
                            Tooltip tooltip = new Tooltip(item);
                            tooltip.setWrapText(true);
                            tooltip.setMaxWidth(400);
                            setTooltip(tooltip);
                            
                            // Apply consistent CSS class
                            if (col.type() == ReportData.ColType.CURRENCY) {
                                getStyleClass().add("report-currency-col");
                            } else if (col.type() == ReportData.ColType.INTEGER) {
                                getStyleClass().add("report-numeric-col");
                            } else {
                                getStyleClass().add("report-text-col");
                            }
                        }
                    }
                });
                
                table.getColumns().add(column);
            }
            
            // Set items after all columns are configured
            table.getItems().setAll(current.rows());
            table.setPlaceholder(Ui.empty("No data for this period", "Change the date range or record transactions first."));
            
            // Force table refresh to apply new layout
            table.layout();
            table.refresh();
            
            // Calculate totals for currency columns
            StringBuilder totals = new StringBuilder();
            for (int c = 0; c < current.columns().size(); c++) {
                if (current.columns().get(c).type() == ReportData.ColType.CURRENCY) {
                    totals.append(current.columns().get(c).label()).append(": ")
                            .append(Money.fmt(current.sum(c))).append("   ");
                }
            }
            summary.setText(totals.toString());
        } catch (Exception e) {
            table.getColumns().clear();
            table.getItems().clear();
            Ui.error(nav.window(), e);
        }
    }

    private void export(ReportExporter.Format format) {
        if (current == null) return;
        progress.setVisible(true); progress.setManaged(true);
        Task<Path> task = new Task<>() {
            @Override protected Path call() throws Exception { return ctx.exporter().export(current, format, ctx.session()); }
        };
        task.setOnSucceeded(e -> {
            progress.setVisible(false); progress.setManaged(false);
            Path path = task.getValue();
            nav.showToast(current.title() + " saved: " + path.getFileName(), "SUCCESS");
            if (Ui.confirm(nav.window(), "Report exported", "Saved to:\n" + path + "\n\nOpen the containing folder?")) {
                try { java.awt.Desktop.getDesktop().open(path.getParent().toFile()); }
                catch (Exception ex) { Ui.error(nav.window(), "Could not open folder", ex.getMessage()); }
            }
        });
        task.setOnFailed(e -> {
            progress.setVisible(false); progress.setManaged(false);
            Ui.error(nav.window(), task.getException());
        });
        Thread t = new Thread(task, "report-export");
        t.setDaemon(true);
        t.start();
    }
}
