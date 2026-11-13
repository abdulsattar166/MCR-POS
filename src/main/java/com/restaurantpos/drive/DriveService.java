package com.restaurantpos.drive;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

public final class DriveService {
    private static final Logger log = LoggerFactory.getLogger(DriveService.class);
    private static final String APP_NAME = "Mubarak Cafe & Restaurant POS";
    private static final List<String> SCOPES = List.of(DriveScopes.DRIVE);
    private static final String FOLDER_NAME = "Mubarak Cafe & Restaurant POS Backups";
    private static final String DEFAULT_FOLDER_ID = "1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH";
    private static final String SCOPE_VERSION = "full-drive-v1";
    private static final Pattern FOLDER_IN_URL = Pattern.compile("(?:/folders/|[?&]id=)([A-Za-z0-9_-]{10,})");
    private final SettingsService settings;

    public DriveService(SettingsService settings) { this.settings = settings; }

    public record RemoteFile(String id, String name, long size, String createdTime, String webViewLink) {}
    public record UploadResult(String fileId, String fileName, String webViewLink, String folderId) {}

    public Path credentialsPath() { return Path.of(settings.googleCredentialsPath()); }
    public Path tokenDirectory() { return AppPaths.config().resolve("drive-tokens"); }

    public boolean credentialsPresent() { return Files.exists(credentialsPath()); }

    public boolean authorized() {
        try {
            if (!credentialsPresent()) return false;
            var flow = flow();
            Credential credential = flow.loadCredential("owner");
            return credential != null && (credential.getRefreshToken() != null || credential.getAccessToken() != null);
        } catch (Exception e) { return false; }
    }

    private NetHttpTransport transport() {
        try { return GoogleNetHttpTransport.newTrustedTransport(); }
        catch (Exception e) { throw new AppException("Could not create a secure connection for Google Drive: " + e.getMessage(), e); }
    }

    private GoogleAuthorizationCodeFlow flow() throws Exception {
        if (!credentialsPresent()) {
            throw new AppException("Google Drive credentials are not configured. Place the OAuth client JSON at "
                    + credentialsPath() + " and try again.");
        }
        GoogleClientSecrets secrets;
        try (var reader = new InputStreamReader(Files.newInputStream(credentialsPath()), StandardCharsets.UTF_8)) {
            secrets = GoogleClientSecrets.load(GsonFactory.getDefaultInstance(), reader);
        }
        Files.createDirectories(tokenDirectory());
        return new GoogleAuthorizationCodeFlow.Builder(transport(), GsonFactory.getDefaultInstance(), secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokenDirectory().toFile()))
                .setAccessType("offline")
                .build();
    }

    public String authorize() {
        try {
            if (!SCOPE_VERSION.equals(settings.get("google.scope.version", ""))) {
                clearTokenFiles();
                settings.save("google.scope.version", SCOPE_VERSION);
            }
            var flow = flow();
            Credential existing = flow.loadCredential("owner");
            if (existing != null && (existing.getRefreshToken() != null || existing.getAccessToken() != null)) {
                if (existing.getAccessToken() == null || existing.getExpiresInSeconds() == null || existing.getExpiresInSeconds() < 60) {
                    existing.refreshToken();
                }
                return "Google Drive is already authorized.";
            }
            LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
            Credential credential = new com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp(flow, receiver)
                    .authorize("owner");
            settings.save("google.scope.version", SCOPE_VERSION);
            log.info("Google Drive authorization completed");
            return credential.getRefreshToken() != null
                    ? "Google Drive connected. Offline refresh token stored securely."
                    : "Google Drive connected for this session.";
        } catch (Exception e) {
            log.warn("Google Drive authorization failed", e);
            throw new AppException("Google Drive authorization failed: " + e.getMessage()
                    + ". Sign in with an account that can open the backup folder and allow Drive access.", e);
        }
    }

    public void disconnect() {
        clearTokenFiles();
        settings.save("google.scope.version", "");
    }

    private void clearTokenFiles() {
        try {
            Path dir = tokenDirectory();
            if (Files.exists(dir)) {
                try (var stream = Files.list(dir)) {
                    for (Path p : stream.toList()) Files.deleteIfExists(p);
                }
            }
        } catch (Exception e) {
            log.warn("Could not clear Drive credentials", e);
        }
    }

    private Drive drive() {
        try {
            Credential credential = flow().loadCredential("owner");
            if (credential == null) throw new AppException("Google Drive is not authorized. Connect Drive in Backup & Recovery first.");
            if (credential.getAccessToken() == null || (credential.getExpiresInSeconds() != null && credential.getExpiresInSeconds() < 60)) {
                if (credential.getRefreshToken() == null) {
                    throw new AppException("Google Drive authorization has expired. Connect Drive again.");
                }
                credential.refreshToken();
            }
            return new Drive.Builder(transport(), GsonFactory.getDefaultInstance(), credential).setApplicationName(APP_NAME).build();
        } catch (AppException e) { throw e; }
        catch (Exception e) { throw new AppException("Could not connect to Google Drive: " + e.getMessage(), e); }
    }

    public static String folderIdFrom(String input) {
        if (input == null || input.isBlank()) return "";
        String value = input.trim();
        Matcher matcher = FOLDER_IN_URL.matcher(value);
        if (matcher.find()) return matcher.group(1);
        if (value.matches("[A-Za-z0-9_-]{10,}")) return value;
        return value;
    }

    public String folderUrl() {
        String id = folderIdFrom(settings.driveFolder());
        return id.isBlank() ? "" : "https://drive.google.com/drive/folders/" + id;
    }

    public String ensureFolder(String configuredId) {
        try {
            Drive drive = drive();
            String source = configuredId;
            if (source == null || source.isBlank()) source = settings.driveFolder();
            if (source == null || source.isBlank()) source = DEFAULT_FOLDER_ID;
            String id = folderIdFrom(source);
            if (!id.isBlank()) {
                File info;
                try {
                    info = drive.files().get(id)
                            .setFields("id,name,mimeType,trashed")
                            .setSupportsAllDrives(true).execute();
                } catch (Exception e) {
                    throw new AppException("Cannot open the configured Google Drive folder. Check that the signed-in "
                            + "Google account has access to folder " + id + ". " + e.getMessage(), e);
                }
                if (info == null || !"application/vnd.google-apps.folder".equals(info.getMimeType())) {
                    throw new AppException("The configured Drive destination is not a folder: " + id);
                }
                if (Boolean.TRUE.equals(info.getTrashed())) {
                    throw new AppException("The configured Drive folder is in the trash: " + id);
                }
                settings.save("google.drive.folder", id);
                return id;
            }
            FileList found = drive.files().list()
                    .setQ("mimeType='application/vnd.google-apps.folder' and name='" + FOLDER_NAME + "' and trashed=false")
                    .setSpaces("drive").setFields("files(id,name)")
                    .setSupportsAllDrives(true).setIncludeItemsFromAllDrives(true).execute();
            if (found.getFiles() != null && !found.getFiles().isEmpty()) {
                String foundId = found.getFiles().get(0).getId();
                settings.save("google.drive.folder", foundId);
                return foundId;
            }
            File folder = new File();
            folder.setName(FOLDER_NAME);
            folder.setMimeType("application/vnd.google-apps.folder");
            File created = drive.files().create(folder).setFields("id,name").execute();
            settings.save("google.drive.folder", created.getId());
            return created.getId();
        } catch (AppException e) { throw e; }
        catch (Exception e) { throw new AppException("Could not prepare the Google Drive folder: " + e.getMessage(), e); }
    }

    public UploadResult upload(Path file, String folderId) {
        try {
            Drive drive = drive();
            String folder = ensureFolder(folderId);
            File meta = new File();
            meta.setName(file.getFileName().toString());
            meta.setParents(List.of(folder));
            FileContent content = new FileContent("application/zip", file.toFile());
            File uploaded = drive.files().create(meta, content)
                    .setFields("id,name,size,webViewLink").execute();
            return new UploadResult(uploaded.getId(), uploaded.getName(),
                    uploaded.getWebViewLink(), folder);
        } catch (AppException e) { throw e; }
        catch (Exception e) {
            log.warn("Drive upload failed: {}", e.getMessage());
            throw new AppException("Google Drive upload failed: " + e.getMessage(), e);
        }
    }

    public List<RemoteFile> listBackups(String folderId) {
        try {
            Drive drive = drive();
            String folder = ensureFolder(folderId);
            FileList list = drive.files().list()
                    .setQ("'" + folder + "' in parents and trashed=false")
                    .setOrderBy("createdTime desc").setPageSize(50)
                    .setFields("files(id,name,size,createdTime,webViewLink)").execute();
            List<RemoteFile> out = new ArrayList<>();
            if (list.getFiles() != null) {
                for (File f : list.getFiles()) {
                    out.add(new RemoteFile(f.getId(), f.getName(), f.getSize() == null ? 0 : f.getSize(),
                            f.getCreatedTime() == null ? "" : f.getCreatedTime().toString(), f.getWebViewLink()));
                }
            }
            return out;
        } catch (Exception e) {
            throw new AppException("Could not list Google Drive backups: " + e.getMessage(), e);
        }
    }

    public Path download(String fileId, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Drive drive = drive();
            try (OutputStream out = Files.newOutputStream(target)) {
                drive.files().get(fileId).executeMediaAndDownloadTo(out);
            }
            return target;
        } catch (Exception e) {
            throw new AppException("Could not download the Drive backup: " + e.getMessage(), e);
        }
    }

    public String statusText() {
        if (!credentialsPresent()) return "Not configured";
        if (!authorized()) return "Configured — authorization required";
        return "Connected";
    }

    public String folderDisplay() {
        String folder = settings.driveFolder();
        return folder == null || folder.isBlank() ? "Auto-created app folder" : folder;
    }
}
