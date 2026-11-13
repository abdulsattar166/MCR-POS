package com.restaurantpos.app;

import com.restaurantpos.config.AppConfig;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.SchemaManager;
import com.restaurantpos.dao.SeedData;
import com.restaurantpos.util.Images;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.DoubleConsumer;

public final class Bootstrap {
    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private static volatile boolean ready;

    public static synchronized void resetForTesting() { ready = false; }

    private Bootstrap() {}

    public static void initialize() { initialize(s -> {}, d -> {}); }

    public static synchronized void initialize(java.util.function.Consumer<String> status, DoubleConsumer progress) {
        if (ready) { progress.accept(1); return; }
        status.accept("Checking local storage...");
        progress.accept(0.08);
        AppPaths.verifyWritable();
        AppConfig.load();

        status.accept("Opening local database...");
        progress.accept(0.28);
        Database db = Database.get();

        status.accept("Applying database migrations...");
        progress.accept(0.45);
        new SchemaManager().migrate();

        status.accept("Preparing menu and restaurant data...");
        progress.accept(0.62);
        new SeedData().seed();

        status.accept("Loading branding...");
        progress.accept(0.78);
        Images.generateLogo();

        status.accept("Verifying integrity...");
        progress.accept(0.9);
        validate();
        ready = true;
        progress.accept(1.0);
        status.accept("Ready — offline mode active");
        log.info("Initialization complete. Data directory: {} (schema v{}, integrity={})",
                AppPaths.root(), new SchemaManager().version(), db.integrityOk());
    }

    public static void validate() {
        Database db = Database.get();
        long users = db.scalarLong("SELECT COUNT(*) FROM users");
        long categories = db.scalarLong("SELECT COUNT(*) FROM categories");
        if (users == 0) throw new com.restaurantpos.exception.AppException("No user accounts exist in the local database.");
        if (categories == 0) throw new com.restaurantpos.exception.AppException("The menu has no categories. Seed data could not be initialized.");
        if (!db.integrityOk()) throw new com.restaurantpos.exception.AppException("Database integrity check failed.");
        if (!java.nio.file.Files.exists(AppPaths.database())) {
            throw new com.restaurantpos.exception.AppException("Local database file is missing.");
        }
    }

    public static boolean ready() { return ready; }
}
