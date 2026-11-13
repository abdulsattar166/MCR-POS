package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.backup.BackupService;
import com.restaurantpos.drive.DriveService;
import com.restaurantpos.model.SystemModels.BackupRecord;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.Money;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class BackupView extends AppView {
    private final CheckBox includeDrive = new CheckBox("Upload to Google Drive when available");
    private final Label driveStatus = Ui.chip("", "chip-dark");
    private final Label lastBackup = Ui.muted("No backup yet");
    private final Label busy = Ui.muted("");
    private final TableView<BackupRecord> history = Ui.table();
    private final TableView<Path> localBackups = Ui.table();
    private final TableView<DriveService.RemoteFile> remote = Ui.table();
    private final TextField credentials = Ui.textField("OAuth client JSON path");
    private final TextField folder = Ui.textField("Drive folder URL or ID");
    private final ProgressIndicator progress = new ProgressIndicator();

    public BackupView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("Backup & Recovery", "Verified local snapshots, report artifacts and owner-authorized Google Drive backup.",
                Ui.primary("Backup now", "fas-cloud-upload-alt", this::backup),
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));

        includeDrive.setSelected(true);
        progress.setPrefSize(22, 22);
        progress.setVisible(false); progress.setManaged(false);
        HBox backupBar = new HBox(12, includeDrive, progress, busy, Ui.spacer(), lastBackup);
        backupBar.setAlignment(Pos.CENTER_LEFT);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        history.getColumns().addAll(
                Ui.col("Created", b -> Ui.dateTime(b.createdAt()), 140),
                Ui.col("Local snapshot", b -> Path.of(b.path()).getFileName().toString(), 270),
                Ui.col("Size", b -> b.size() == 0 ? "—" : String.format("%.1f MB", b.size() / 1_048_576.0), 80),
                Ui.col("Status", BackupRecord::status, 100),
                Ui.col("Drive", b -> b.driveStatus() == null ? "—" : b.driveStatus(), 110),
                Ui.col("Details", b -> b.details() == null ? "" : b.details(), 260));
        history.setPlaceholder(Ui.empty("No backups", "Run your first backup to create a verified local snapshot."));

        localBackups.getColumns().addAll(
                Ui.col("Backup folder", p -> p.getFileName().toString(), 380),
                Ui.col("Contents", p -> {
                    Path zip = p.resolveSibling(p.getFileName() + ".zip");
                    return Files.exists(zip) ? "Folder + ZIP" : "Folder";
                }, 140));
        localBackups.setPlaceholder(Ui.empty("No local backups", "Backups are stored in C:\\RestaurantPOS\\backups."));

        remote.getColumns().addAll(
                Ui.col("File", DriveService.RemoteFile::name, 300),
                Ui.col("Size", f -> f.size() == 0 ? "—" : String.format("%.1f MB", f.size() / 1_048_576.0), 90),
                Ui.col("Created", DriveService.RemoteFile::createdTime, 190));
        remote.setPlaceholder(Ui.empty("No Drive backups", "Connect Google Drive and authorise the owner account."));

        history.setPrefHeight(250);
        VBox historyTab = new VBox(12, backupBar, history,
                new HBox(8,
                        Ui.secondary("Restore selected backup", "fas-undo", this::restoreSelected),
                        Ui.ghost("Restore from file", "fas-file-import", this::restoreFile),
                        Ui.ghost("Open backups folder", "fas-folder-open", this::openFolder)));
        historyTab.setPadding(new Insets(14));
        VBox.setVgrow(history, Priority.ALWAYS);

        Button browse = Ui.secondary("Choose client secret JSON", "fas-file", this::chooseCredentials);
        Button connect = Ui.primary("Connect Google Drive", "fas-link", this::connectDrive);
        Button disconnect = Ui.ghost("Disconnect", "fas-unlink", () -> {
            ctx.drive().disconnect();
            driveStatus.setText("Not connected");
            driveStatus.getStyleClass().setAll("chip", "chip-dark");
            toast("Google Drive authorization cleared.");
        });
        Button saveFolder = Ui.ghost("Save destination", "fas-save", () -> {
            String id = DriveService.folderIdFrom(folder.getText());
            if (id.isBlank()) { warn("Paste a Google Drive folder link or folder ID."); return; }
            ctx.settings().save("google.drive.folder", id);
            folder.setText(id);
            toast("Drive destination saved.");
        });
        Button verify = Ui.secondary("Verify folder access", "fas-check-circle", this::verifyDrive);
        Button openDrive = Ui.ghost("Open Drive folder", "fas-external-link-alt", this::openDriveFolder);
        credentials.setText(ctx.settings().googleCredentialsPath());
        folder.setText(ctx.settings().driveFolder());
        Label info = Ui.muted("Google Drive is used only for manual owner-triggered backups. The POS keeps operating when Drive is offline. "
                + "Paste the shared folder link above. Connect with a Google account that can open that folder. "
                + "The folder link is converted to its ID automatically and verified before uploading.");
        FlowPane folderBar = new FlowPane(8, 8, folder, saveFolder, verify, openDrive);
        folderBar.setAlignment(Pos.CENTER_LEFT);
        VBox driveTab = new VBox(13,
                Ui.card("Connection status", "fas-cloud", new FlowPane(10, 8, driveStatus, connect, disconnect),
                        new FlowPane(8, 8, credentials, browse), folderBar, info),
                Ui.card("Drive backups", "fas-cloud-download-alt",
                        new FlowPane(8, 8, Ui.button("Refresh Drive list", "fas-sync-alt", "secondary-button", this::loadRemote),
                                Ui.ghost("Download & restore selected", "fas-download", this::restoreRemote)),
                        remote));
        driveTab.setPadding(new Insets(14));
        remote.setPrefHeight(250);
        VBox.setVgrow(remote, Priority.ALWAYS);

        VBox localTab = new VBox(12, Ui.muted("Local SQLite snapshots are independent of Google Drive. "
                        + "Restoring replaces the live database and may discard transactions made after the snapshot."),
                localBackups, new FlowPane(8, 8, Ui.secondary("Restore selected folder", "fas-undo", this::restoreLocalSelected),
                        Ui.secondary("Restore from file", "fas-file-import", this::restoreFile),
                        Ui.ghost("Open folder", "fas-folder-open", this::openFolder)));
        localTab.setPadding(new Insets(14));
        localBackups.setPrefHeight(250);
        VBox.setVgrow(localBackups, Priority.ALWAYS);

        tabs.getTabs().addAll(new Tab("Backups", Ui.scroll(historyTab)), new Tab("Local recovery", Ui.scroll(localTab)), new Tab("Google Drive", Ui.scroll(driveTab)));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        history.getItems().setAll(ctx.systemRepo().backups(100));
        localBackups.getItems().setAll(ctx.backups().localBackups());
        credentials.setText(ctx.settings().googleCredentialsPath());
        folder.setText(ctx.settings().driveFolder());
        animateBusy(driveStatus, ctx.drive().statusText());
        if (!ctx.systemRepo().backups(1).isEmpty()) {
            BackupRecord last = ctx.systemRepo().backups(1).get(0);
            lastBackup.setText("Last backup " + Ui.dateTime(last.createdAt()) + "  ·  " + last.driveStatus());
        }
    }

    private void animateBusy(Label chip, String text) {
        chip.setText(text);
        chip.getStyleClass().setAll("chip", text.startsWith("Connected") ? "chip-green"
                : text.startsWith("Configured") ? "chip-amber" : "chip-dark");
    }

    private void backup() {
        if (!ctx.session().isOwner()) { warn("Only the owner can run a backup."); return; }
        if (!Ui.confirm(nav.window(), "Create backup", "Create a verified local database snapshot with Excel/PDF reports"
                + (includeDrive.isSelected() ? " and upload it to Google Drive when authorized?" : "?"))) return;
        run("Creating verified backup...", () -> ctx.backups().createBackup(ctx.session(), includeDrive.isSelected()),
                result -> {
                    nav.showToast(result.message(), "SUCCESS");
                    refresh();
                    if (result.folder() != null) {
                        Ui.info(nav.window(), "Backup complete",
                                "Local snapshot: " + result.folder() + "\nDrive: " + result.driveStatus());
                    }
                });
    }

    private void chooseCredentials() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Google OAuth client JSON", "*.json"));
        File f = fc.showOpenDialog(nav.window());
        if (f == null) return;
        try {
            Path target = AppPaths.config().resolve("client_secret.json");
            if (!f.toPath().toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                Files.copy(f.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ctx.settings().save("google.credentials.path", target.toString());
            credentials.setText(target.toString());
            toast("Google credentials path saved securely in the local config folder.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void connectDrive() {
        if (!ctx.drive().credentialsPresent()) {
            warn("Choose the OAuth client JSON first.");
            return;
        }
        run("Opening browser for Google authorization...", () -> {
            String message = ctx.drive().authorize();
            ctx.drive().ensureFolder(ctx.settings().driveFolder());
            return message + " Backup folder verified.";
        }, message -> {
            toast(message);
            refresh();
        });
    }

    private void verifyDrive() {
        if (!ctx.drive().authorized()) { warn("Connect Google Drive first."); return; }
        run("Verifying folder access...", () -> ctx.drive().ensureFolder(ctx.settings().driveFolder()), id -> {
            refresh();
            Ui.info(nav.window(), "Google Drive ready",
                    "The backup folder is accessible.\n\nFolder ID: " + id + "\n" + ctx.drive().folderUrl());
        });
    }

    private void openDriveFolder() {
        String url = ctx.drive().folderUrl();
        if (url.isBlank()) { warn("Save a Google Drive folder first."); return; }
        try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
        catch (Exception e) { Ui.error(nav.window(), "Could not open Drive folder", e.getMessage()); }
    }

    private void loadRemote() {
        if (!ctx.drive().authorized()) { warn("Connect Google Drive first."); return; }
        run("Loading Drive backups...", () -> ctx.drive().listBackups(ctx.settings().driveFolder()), list -> {
            remote.getItems().setAll(list);
            toast("Drive backup list refreshed.");
        });
    }

    private void restoreSelected() {
        BackupRecord record = history.getSelectionModel().getSelectedItem();
        if (record == null) { warn("Select a backup from history."); return; }
        restore(Path.of(record.path()));
    }

    private void restoreLocalSelected() {
        Path selected = localBackups.getSelectionModel().getSelectedItem();
        if (selected == null) { warn("Select a local backup folder."); return; }
        restore(selected);
    }

    private void restoreFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Database or backup archive", "*.db", "*.zip"));
        File f = fc.showOpenDialog(nav.window());
        if (f == null) return;
        restore(f.toPath());
    }

    private void restore(Path selected) {
        if (!ctx.session().isOwner()) { warn("Only the owner can restore a backup."); return; }
        if (!Ui.confirm(nav.window(), "Restore database",
                "Restoring an older database will replace current data and MAY LOSE transactions created after the backup.\n\n"
                        + "A safety snapshot of the current database is created first.\n\n"
                        + "Selected: " + selected + "\n\nContinue with restore?")) return;
        run("Validating and restoring database...", () -> ctx.backups().restoreLocal(selected, ctx.session()), safety -> {
            refresh();
            Ui.info(nav.window(), "Database restored",
                    "The database was restored and verified.\n\nSafety snapshot:\n" + safety
                            + "\n\nRestart the application if any screen appears stale.");
            nav.showToast("Database restored. Restart is recommended.", "SUCCESS");
        });
    }

    private void restoreRemote() {
        DriveService.RemoteFile f = remote.getSelectionModel().getSelectedItem();
        if (f == null) { warn("Select a Drive backup."); return; }
        if (!ctx.session().isOwner()) { warn("Only the owner can restore a backup."); return; }
        if (!Ui.confirm(nav.window(), "Restore from Google Drive",
                "Download and restore " + f.name() + "?\n\nCurrent data may be replaced. A safety snapshot is created before restore.")) return;
        run("Downloading and restoring backup...", () -> ctx.backups().restoreFromDrive(f.id(), ctx.session()), safety -> {
            refresh();
            Ui.info(nav.window(), "Restore complete", "Downloaded, verified and restored.\nSafety snapshot: " + safety
                    + "\n\nRestart the application to be safe.");
        });
    }

    private void openFolder() {
        try { java.awt.Desktop.getDesktop().open(AppPaths.backups().toFile()); }
        catch (Exception e) { Ui.error(nav.window(), "Could not open folder", e.getMessage()); }
    }

    private <T> void run(String message, ThrowingSupplier<T> work, java.util.function.Consumer<T> onSuccess) {
        progress.setVisible(true); progress.setManaged(true);
        busy.setText(message);
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.get(); }
        };
        task.setOnSucceeded(e -> {
            progress.setVisible(false); progress.setManaged(false);
            busy.setText("");
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            progress.setVisible(false); progress.setManaged(false);
            busy.setText("");
            Throwable ex = task.getException();
            Ui.error(nav.window(), "Operation failed", ex == null ? "Unknown error" : ex.getMessage());
        });
        Thread t = new Thread(task, "backup-operation");
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
