package com.restaurantpos.app;

import com.restaurantpos.exception.AppException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class AppPaths {
    private static Path root;

    private AppPaths() {}

    public static synchronized Path root() {
        if (root == null) {
            String configured = System.getProperty("pos.home");
            if (configured == null || configured.isBlank()) configured = System.getenv("POS_HOME");
            if (configured == null || configured.isBlank()) configured = "C:\\RestaurantPOS";
            root = Paths.get(configured).toAbsolutePath().normalize();
        }
        return root;
    }

    public static synchronized void overrideRoot(Path newRoot) {
        root = newRoot.toAbsolutePath().normalize();
    }

    public static Path data()        { return ensure(root().resolve("data")); }
    public static Path backups()     { return ensure(root().resolve("backups")); }
    public static Path excel()       { return ensure(root().resolve("reports").resolve("Excel")); }
    public static Path pdf()         { return ensure(root().resolve("reports").resolve("PDF")); }
    public static Path csv()         { return ensure(root().resolve("reports").resolve("CSV")); }
    public static Path foodImages()  { return ensure(root().resolve("images").resolve("food")); }
    public static Path staffImages() { return ensure(root().resolve("images").resolve("staff")); }
    public static Path brandImages() { return ensure(root().resolve("images").resolve("restaurant")); }
    public static Path receipts()    { return ensure(root().resolve("receipts")); }
    public static Path logs()        { return ensure(root().resolve("logs")); }
    public static Path exports()     { return ensure(root().resolve("exports")); }
    public static Path temp()        { return ensure(root().resolve("temp")); }
    public static Path config()      { return ensure(root().resolve("config")); }

    public static Path database()    { return data().resolve("restaurant.db"); }

    public static List<Path> all() {
        return List.of(data(), backups(), excel(), pdf(), csv(), foodImages(), staffImages(),
                brandImages(), receipts(), logs(), exports(), temp(), config());
    }

    private static Path ensure(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new AppException("Cannot create application directory: " + path + ". " + e.getMessage(), e);
        }
    }

    public static void verifyWritable() {
        try {
            for (Path p : all()) {
                if (!Files.isWritable(p)) throw new AppException("Directory is not writable: " + p);
            }
            Path probe = temp().resolve(".write-test");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new AppException("Application data directory is not writable: " + root() + ". " + e.getMessage(), e);
        }
    }
}
