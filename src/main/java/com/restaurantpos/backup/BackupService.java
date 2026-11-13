package com.restaurantpos.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.SchemaManager;
import com.restaurantpos.dao.SystemRepository;
import com.restaurantpos.drive.DriveService;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.reporting.ReportData;
import com.restaurantpos.reporting.ReportExporter;
import com.restaurantpos.security.Session;
import com.restaurantpos.service.AuditService;
import com.restaurantpos.service.ReportService;
import com.restaurantpos.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private final Database db = Database.get();
    private final SettingsService settings;
    private final ReportService reports;
    private final ReportExporter exporter;
    private final DriveService drive;
    private final SystemRepository system;
    private final AuditService audit;

    public BackupService(SettingsService settings, ReportService reports, ReportExporter exporter,
                         DriveService drive, SystemRepository system, AuditService audit) {
        this.settings = settings;
        this.reports = reports;
        this.exporter = exporter;
        this.drive = drive;
        this.system = system;
        this.audit = audit;
    }

    public record ManifestEntry(String file, long size, String sha256) {}
    public record Manifest(String application, String version, String createdAt, String database, List<ManifestEntry> files) {}
    public record BackupResult(boolean success, String message, Path folder, Path archive, String driveStatus, long recordId) {}

    public BackupResult createBackup(Session session, boolean attemptDrive) {
        if (!session.isOwner()) throw new AppException("Only the owner can create a cloud backup.");
        String stamp = LocalDateTime.now().format(STAMP);
        Path folder = AppPaths.backups().resolve("RestaurantPOS_Backup_" + stamp);
        try {
            Files.createDirectories(folder);
            Path databaseCopy = folder.resolve("restaurant.db");
            db.backupTo(databaseCopy);
            if (!validateDatabase(databaseCopy)) {
                throw new AppException("The generated database snapshot failed validation. The backup was aborted.");
            }

            Path reportDir = folder.resolve("reports");
            Files.createDirectories(reportDir);
            LocalDate to = LocalDate.now();
            LocalDate from = to.withDayOfMonth(1);
            ReportData daily = reports.build("SALES_DAILY", from, to);
            ReportData products = reports.build("PRODUCT_PERFORMANCE", from, to);
            List<Path> artifacts = new ArrayList<>();
            artifacts.add(databaseCopy);
            try {
                artifacts.add(exporter.exportTo(daily, ReportExporter.Format.EXCEL, reportDir, settings.restaurantName()));
                artifacts.add(exporter.exportTo(daily, ReportExporter.Format.PDF, reportDir, settings.restaurantName()));
                artifacts.add(exporter.exportTo(products, ReportExporter.Format.EXCEL, reportDir, settings.restaurantName()));
            } catch (Exception e) {
                log.warn("Some backup report artifacts could not be generated", e);
            }

            List<ManifestEntry> entries = new ArrayList<>();
            for (Path p : artifacts) {
                entries.add(new ManifestEntry(folder.relativize(p).toString().replace('\\', '/'), Files.size(p), sha256(p)));
            }
            Manifest manifest = new Manifest("Mubarak Cafe & Restaurant POS", settings.appVersion(),
                    LocalDateTime.now().toString(), "restaurant.db", entries);
            Path manifestFile = folder.resolve("manifest.json");
            Files.writeString(manifestFile, new GsonBuilder().setPrettyPrinting().create().toJson(manifest));

            Path archive = AppPaths.backups().resolve(folder.getFileName() + ".zip");
            zipFolder(folder, archive);
            String checksum = sha256(archive);
            long recordId = system.recordBackup(folder.toString(), Files.size(archive), checksum, "LOCAL_OK", "PENDING",
                    entries.size() + " artifact(s)");

            String driveStatus = "SKIPPED";
            String driveDetail = "Cloud upload not requested.";
            if (attemptDrive) {
                if (!drive.credentialsPresent()) {
                    driveStatus = "NOT_CONFIGURED";
                    driveDetail = "Google Drive credentials are not configured.";
                } else if (!drive.authorized()) {
                    driveStatus = "NOT_AUTHORIZED";
                    driveDetail = "Google Drive authorization is required.";
                } else {
                    try {
                        DriveService.UploadResult uploaded = drive.upload(archive, settings.driveFolder());
                        driveStatus = "UPLOADED";
                        driveDetail = "Uploaded to Google Drive: " + uploaded.webViewLink();
                        system.updateBackupDrive(recordId, driveStatus, driveDetail);
                        audit.notify("BACKUP", "Cloud backup complete", archive.getFileName() + " uploaded to Google Drive.", "SUCCESS");
                    } catch (Exception e) {
                        driveStatus = "PENDING";
                        driveDetail = "Cloud upload pending: " + e.getMessage();
                        system.updateBackupDrive(recordId, driveStatus, driveDetail);
                        audit.notify("BACKUP", "Cloud upload pending", "Local backup succeeded. " + driveDetail, "WARNING");
                    }
                }
                if (!"UPLOADED".equals(driveStatus)) system.updateBackupDrive(recordId, driveStatus, driveDetail);
            }

            audit.log("BACKUP_CREATED", "backups", recordId, folder.getFileName() + " / " + driveStatus);
            audit.notify("BACKUP", "Local backup complete", "Database snapshot and reports saved to " + folder.getFileName() + ".", "SUCCESS");
            String message = "Backup verified and saved locally. " + ("UPLOADED".equals(driveStatus) ? "Uploaded to Google Drive."
                    : "Drive: " + driveStatus + ".");
            return new BackupResult(true, message, folder, archive, driveStatus, recordId);
        } catch (Exception e) {
            log.error("Backup failed", e);
            try { system.recordBackup(folder.toString(), 0, "", "FAILED", "NONE", e.getMessage()); } catch (Exception ignored) {}
            audit.log("BACKUP_FAILED", "backups", null, e.getMessage());
            audit.notify("BACKUP", "Backup failed", e.getMessage(), "CRITICAL");
            throw new AppException("Backup failed: " + e.getMessage(), e);
        }
    }

    public List<Path> localBackups() {
        try (var stream = Files.list(AppPaths.backups())) {
            return stream.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("RestaurantPOS_Backup_"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        } catch (Exception e) { return List.of(); }
    }

    public Path databaseIn(Path backupFolder) {
        if (Files.isRegularFile(backupFolder)) {
            String name = backupFolder.getFileName().toString();
            if (name.endsWith(".db")) return backupFolder;
            if (name.endsWith(".zip")) return extractDb(backupFolder);
        }
        Path candidate = backupFolder.resolve("restaurant.db");
        return Files.exists(candidate) ? candidate : null;
    }

    public boolean validateDatabase(Path file) {
        if (file == null || !Files.exists(file)) return false;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + file.toAbsolutePath() + "?mode=ro");
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) return false;
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('users','orders','settings','app_meta')")) {
                return rs.next() && rs.getInt(1) >= 4;
            }
        } catch (Exception e) {
            log.warn("Backup validation failed for {}: {}", file, e.getMessage());
            return false;
        }
    }

    public int backupSchemaVersion(Path file) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + file.toAbsolutePath() + "?mode=ro");
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT value FROM app_meta WHERE key='schema_version'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        } catch (Exception e) { return 0; }
    }

    public Path restoreLocal(Path selected, Session session) {
        if (!session.isOwner()) throw new AppException("Only the owner can restore a database backup.");
        Path backupDb = databaseIn(selected);
        if (backupDb == null || !Files.exists(backupDb)) throw new AppException("The selected backup does not contain a database file.");
        if (!validateDatabase(backupDb)) throw new AppException("The selected database backup failed validation and cannot be restored.");
        int version = backupSchemaVersion(backupDb);
        int current = new SchemaManager().version();
        if (version > current) throw new AppException("This backup was created by a newer application version and cannot be restored.");
        Path safety = AppPaths.backups().resolve("PreRestore_Safety_" + LocalDateTime.now().format(STAMP) + ".db");
        try {
            db.backupTo(safety);
            if (!validateDatabase(safety)) throw new AppException("The safety snapshot could not be verified.");
            db.closeQuietly();
            Path target = AppPaths.database();
            Files.copy(backupDb, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(Path.of(target + "-wal"));
            Files.deleteIfExists(Path.of(target + "-shm"));
            new SchemaManager().migrate();
            if (!db.integrityOk()) throw new AppException("The restored database failed its integrity check.");
            long id = system.recordBackup(selected.toString(), Files.size(backupDb), sha256(backupDb), "RESTORED",
                    "NONE", "Safety snapshot: " + safety.getFileName());
            audit.log("DATABASE_RESTORED", "backups", id, "Restored from " + selected.getFileName());
            audit.notify("RESTORE", "Database restored", "The database was restored. Restart the application if any screen looks stale.", "SUCCESS");
            return safety;
        } catch (Exception e) {
            log.error("Restore failed", e);
            try {
                if (Files.exists(safety)) {
                    db.closeQuietly();
                    Files.copy(safety, AppPaths.database(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception restoreError) {
                log.error("Safety rollback also failed", restoreError);
            }
            audit.log("RESTORE_FAILED", "backups", null, e.getMessage());
            throw new AppException("Restore failed: " + e.getMessage(), e);
        }
    }

    public Path restoreFromDrive(String fileId, Session session) {
        if (!session.isOwner()) throw new AppException("Only the owner can restore a database backup.");
        Path dir = AppPaths.temp().resolve("drive-restore-" + LocalDateTime.now().format(STAMP));
        Path archive = dir.resolve("drive-backup.zip");
        drive.download(fileId, archive);
        Path backupDb = extractDb(archive);
        return restoreLocal(backupDb, session);
    }

    public Path extractDb(Path zip) {
        try {
            Path targetDir = AppPaths.temp().resolve("restore-" + LocalDateTime.now().format(STAMP));
            Files.createDirectories(targetDir);
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    if (entry.getName().endsWith("restaurant.db")) {
                        Path out = targetDir.resolve("restaurant.db");
                        Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                        return out;
                    }
                }
            }
            throw new AppException("No restaurant.db file was found in the backup archive.");
        } catch (AppException e) { throw e; }
        catch (Exception e) { throw new AppException("Could not extract the backup archive: " + e.getMessage(), e); }
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new AppException("Checksum calculation failed: " + e.getMessage(), e); }
    }

    private void zipFolder(Path folder, Path zip) throws Exception {
        Files.deleteIfExists(zip);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.CREATE))) {
            try (var stream = Files.walk(folder)) {
                for (Path p : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String name = folder.relativize(p).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(name));
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}
