package com.restaurantpos.reporting;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.service.AuditService;

import java.nio.file.Path;
import java.time.LocalDate;

public final class ReportExporter {
    private final AuditService audit;
    private final com.restaurantpos.service.SettingsService settings;

    public ReportExporter(AuditService audit, com.restaurantpos.service.SettingsService settings) {
        this.audit = audit; this.settings = settings;
    }

    public enum Format { EXCEL, PDF, CSV }

    public Path export(ReportData data, Format format, Session session) throws Exception {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER, com.restaurantpos.enums.Role.CASHIER);
        Path target = switch (format) {
            case EXCEL -> ExcelExporter.export(data, AppPaths.excel(), settings.restaurantName(), settings.restaurantAddress());
            case PDF -> PdfExporter.export(data, AppPaths.pdf(), settings.restaurantName(), settings.restaurantAddress());
            case CSV -> CsvExporter.export(data, AppPaths.csv());
        };
        audit.log("REPORT_EXPORTED", "reports", null, data.title() + " (" + format + ") -> " + target.getFileName());
        return target;
    }

    public Path exportTo(ReportData data, Format format, Path directory, String restaurant) throws Exception {
        return switch (format) {
            case EXCEL -> ExcelExporter.export(data, directory, restaurant, settings.restaurantAddress());
            case PDF -> PdfExporter.export(data, directory, restaurant, settings.restaurantAddress());
            case CSV -> CsvExporter.export(data, directory);
        };
    }
}
