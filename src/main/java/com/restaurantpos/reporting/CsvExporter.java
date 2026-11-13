package com.restaurantpos.reporting;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CsvExporter {
    private CsvExporter() {}

    public static Path export(ReportData data, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path target = directory.resolve(ExcelExporter.fileName(data) + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            w.write('\ufeff');
            w.write(escape(data.title()));
            w.newLine();
            w.write(escape("Period," + data.period()));
            w.newLine();
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < data.columns().size(); i++) {
                if (i > 0) header.append(',');
                header.append(escape(data.columns().get(i).label()));
            }
            w.write(header.toString());
            w.newLine();
            for (Object[] row : data.rows()) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < data.columns().size(); c++) {
                    if (c > 0) line.append(',');
                    Object v = c < row.length ? row[c] : null;
                    line.append(escape(ReportData.format(v, data.columns().get(c).type())));
                }
                w.write(line.toString());
                w.newLine();
            }
        }
        return target;
    }

    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
