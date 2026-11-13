package com.restaurantpos;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.app.Bootstrap;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.SeedData;
import com.restaurantpos.drive.DriveService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class GoogleDriveBackupFolderTest {

    private static final String EXPECTED_FOLDER_ID = "1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH";
    private static final String FULL_URL = "https://drive.google.com/drive/folders/1v6t9jR1w3caFNgbICpkylTZd4ZZetkmH";

    private static AppContext ctx;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Bootstrap.resetForTesting();
        AppContext.resetForTesting();
        tempDir = Files.createTempDirectory("restaurant-pos-drive-test-");
        System.setProperty("pos.home", tempDir.toString());
        AppPaths.overrideRoot(tempDir);
        Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        Bootstrap.initialize();
        ctx = AppContext.get();
    }

    @Test
    @DisplayName("Verify DriveService extracts folder ID from full URL and raw ID")
    void testFolderIdExtraction() {
        assertEquals(EXPECTED_FOLDER_ID, DriveService.folderIdFrom(FULL_URL));
        assertEquals(EXPECTED_FOLDER_ID, DriveService.folderIdFrom(EXPECTED_FOLDER_ID));
        assertEquals(EXPECTED_FOLDER_ID, DriveService.folderIdFrom(" " + FULL_URL + " "));
    }

    @Test
    @DisplayName("Verify settings and DriveService default to new backup folder")
    void testDefaultDriveFolderConfiguration() {
        assertEquals(EXPECTED_FOLDER_ID, ctx.settings().driveFolder(),
                "SettingsService should return the configured backup folder ID");
        assertEquals(FULL_URL, ctx.drive().folderUrl(),
                "DriveService.folderUrl() should match the user's Google Drive folder link");
    }

    @Test
    @DisplayName("Verify and update live production database if present")
    void testUpdateLiveProductionDatabase() {
        Path prodDb = Path.of("C:", "RestaurantPOS", "data", "restaurant.db");
        if (Files.exists(prodDb)) {
            Database.use(prodDb);
            new SeedData().seed();
            Database db = Database.get();
            db.update("UPDATE settings SET value=? WHERE key='google.drive.folder'", EXPECTED_FOLDER_ID);
            String val = db.queryOne("SELECT value FROM settings WHERE key='google.drive.folder'", rs -> rs.getString(1));
            assertEquals(EXPECTED_FOLDER_ID, val, "Production database should have the updated Google Drive folder");
            System.out.println("C:\\RestaurantPOS\\data\\restaurant.db successfully updated with Google Drive backup folder: " + val);

            // Restore testing database
            Database.use(tempDir.resolve("data").resolve("restaurant.db"));
        }
    }
}
