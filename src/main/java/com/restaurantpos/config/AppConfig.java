package com.restaurantpos.config;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.exception.AppException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Machine-local bootstrap configuration (outside the database and never committed). */
public final class AppConfig {
    private static final Properties PROPS = new Properties();
    private static boolean loaded;

    private AppConfig() {}

    public static synchronized void load() {
        if (loaded) return;
        Path file = file();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) { PROPS.load(in); }
            catch (IOException e) { throw new AppException("Unable to read configuration: " + e.getMessage(), e); }
        }
        loaded = true;
    }

    public static Path file() { return AppPaths.config().resolve("app.properties"); }

    public static String get(String key, String fallback) {
        load();
        return PROPS.getProperty(key, fallback);
    }

    public static int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public static synchronized void set(String key, String value) {
        load();
        PROPS.setProperty(key, value);
        save();
    }

    public static synchronized void save() {
        try (OutputStream out = Files.newOutputStream(file())) {
            PROPS.store(out, "Mubarak Cafe & Restaurant POS local configuration");
        } catch (IOException e) {
            throw new AppException("Unable to save configuration: " + e.getMessage(), e);
        }
    }
}
