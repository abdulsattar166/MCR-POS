package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.dao.UserRepository;
import com.restaurantpos.model.SystemModels.AuditEvent;
import com.restaurantpos.reporting.CsvExporter;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportData.Column;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class AuditView extends AppView {
    private final TableView<AuditEvent> auditTable = Ui.table();
    private final TableView<Object[]> loginTable = Ui.table();
    private final TextField search = Ui.textField("Search actor, action, entity or details...");
    private final Label summary = Ui.muted("");

    public AuditView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Audit & Security", "Immutable operational history of sensitive actions and sign-in attempts.",
                Ui.button("Export audit CSV", "fas-file-csv", "secondary-button", this::exportAudit),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        auditTable.getColumns().addAll(
                Ui.col("Time", a -> Ui.dateTime(a.createdAt()), 140),
                Ui.col("User", AuditEvent::username, 180),
                Ui.col("Action", AuditEvent::action, 190),
                Ui.col("Entity", a -> a.entity() == null ? "" : a.entity(), 120),
                Ui.col("Ref", a -> a.entityId() == null ? "" : String.valueOf(a.entityId()), 60),
                Ui.col("Details", a -> a.details() == null ? "" : a.details(), 440));
        auditTable.setPlaceholder(Ui.empty("No audit events", "Sensitive changes are recorded here automatically."));
        search.setPrefWidth(320);
        Ui.bindSearch(search, this::loadAudit);
        HBox bar = new HBox(10, search, Ui.spacer(), summary);
        bar.setAlignment(Pos.CENTER_LEFT);

        loginTable.getColumns().addAll(
                Ui.col("Time", r -> String.valueOf(r[4]), 150),
                Ui.col("Username", r -> String.valueOf(r[1]), 240),
                Ui.col("Result", r -> ((Boolean) r[2]) ? "Success" : "Failed", 90),
                Ui.col("Details", r -> String.valueOf(r[3]), 380));
        loginTable.setPlaceholder(Ui.empty("No login activity", "Sign-in attempts are recorded automatically."));
        loginTable.setPrefHeight(300);

        VBox auditTab = new VBox(10, bar, auditTable);
        VBox.setVgrow(auditTable, Priority.ALWAYS);
        auditTab.setPadding(new Insets(14));
        VBox loginTab = new VBox(10, Ui.muted("Recent sign-in attempts, including failures and lockouts."), loginTable);
        loginTab.setPadding(new Insets(14));
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Audit trail", auditTab), new Tab("Login activity", loginTab));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        loadAudit();
        List<Object[]> rows = new ArrayList<>();
        for (UserRepository.LoginAttempt a : ctx.users().recentAttempts(300)) {
            rows.add(new Object[]{a.id(), a.username(), a.success(), a.detail(), Ui.dateTime(a.createdAt())});
        }
        loginTable.getItems().setAll(rows);
        long failed = rows.stream().filter(r -> !((Boolean) r[2])).count();
        summary.setText(auditTable.getItems().size() + " audit event(s)  ·  " + failed + " failed sign-in(s)");
    }

    private void loadAudit() {
        List<AuditEvent> events = ctx.systemRepo().auditEvents(500, search.getText());
        auditTable.getItems().setAll(events);
        summary.setText(events.size() + " event(s)");
    }

    private void exportAudit() {
        try {
            ReportData data = new ReportData("AUDIT_LOG", "Audit Trail", "Latest 500 events",
                    List.of(Column.dateTime("Time", 16), Column.text("User", 18), Column.text("Action", 20),
                            Column.text("Entity", 14), Column.text("Reference", 10), Column.text("Details", 40)),
                    auditTable.getItems().stream().map(a -> new Object[]{a.createdAt() == null ? "" : a.createdAt().toString(),
                            a.username(), a.action(), a.entity(), a.entityId(), a.details()}).map(o -> (Object[]) o).toList());
            var path = CsvExporter.export(data, com.restaurantpos.app.AppPaths.csv());
            ctx.audit().log("AUDIT_EXPORTED", "audit_log", null, path.getFileName().toString());
            toast("Audit CSV saved: " + path.getFileName());
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }
}
