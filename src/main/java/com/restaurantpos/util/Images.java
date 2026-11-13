package com.restaurantpos.util;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.exception.AppException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Random;

public final class Images {
    private static final java.util.Map<String, javafx.scene.image.Image> IMAGE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private Images() {}

    public static javafx.scene.image.Image getCachedImage(String relative, double width, double height) {
        if (relative == null || relative.isBlank()) return null;
        String cacheKey = relative + "_" + (int)width + "x" + (int)height;
        return IMAGE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                String norm = relative.replace('\\', '/');
                String resPath = norm.startsWith("/") ? norm : "/images/" + norm;
                java.net.URL resUrl = Images.class.getResource(resPath);
                if (resUrl != null) {
                    return new javafx.scene.image.Image(resUrl.toExternalForm(), width, height, true, true, true);
                }
                String alt = null;
                if (norm.endsWith(".jpg")) alt = norm.substring(0, norm.length() - 4) + ".png";
                else if (norm.endsWith(".png")) alt = norm.substring(0, norm.length() - 4) + ".jpg";
                if (alt != null) {
                    String altRes = alt.startsWith("/") ? alt : "/images/" + alt;
                    java.net.URL altUrl = Images.class.getResource(altRes);
                    if (altUrl != null) {
                        return new javafx.scene.image.Image(altUrl.toExternalForm(), width, height, true, true, true);
                    }
                }
                Path file = resolve(relative);
                if (file != null && Files.isRegularFile(file)) {
                    return new javafx.scene.image.Image(file.toUri().toString(), width, height, true, true, true);
                }
            } catch (Exception ignored) {}
            return null;
        });
    }

    public static void clearCache() {
        IMAGE_CACHE.clear();
        BrandLogo.clearCache();
    }

    public static Path resolve(String relative) {
        if (relative == null || relative.isBlank()) return null;
        Path p = Path.of(relative);
        if (p.isAbsolute() && Files.isRegularFile(p)) return p;

        Path inHome = AppPaths.root().resolve("images").resolve(relative);
        if (Files.isRegularFile(inHome)) return inHome;

        // Check alternate extension (.jpg <-> .png)
        String alt = null;
        if (relative.endsWith(".jpg")) alt = relative.substring(0, relative.length() - 4) + ".png";
        else if (relative.endsWith(".png")) alt = relative.substring(0, relative.length() - 4) + ".jpg";
        if (alt != null) {
            Path inHomeAlt = AppPaths.root().resolve("images").resolve(alt);
            if (Files.isRegularFile(inHomeAlt)) return inHomeAlt;
        }

        // Check directly in food images directory
        String baseName = p.getFileName().toString();
        Path inFood = AppPaths.foodImages().resolve(baseName);
        if (Files.isRegularFile(inFood)) return inFood;
        if (alt != null) {
            Path inFoodAlt = AppPaths.foodImages().resolve(Path.of(alt).getFileName().toString());
            if (Files.isRegularFile(inFoodAlt)) return inFoodAlt;
        }

        // Try extracting from classpath resources
        try {
            String norm = relative.replace('\\', '/');
            String resPath = norm.startsWith("/") ? norm : "/images/" + norm;
            try (InputStream in = Images.class.getResourceAsStream(resPath)) {
                if (in != null) {
                    Files.createDirectories(inHome.getParent());
                    Files.copy(in, inHome, StandardCopyOption.REPLACE_EXISTING);
                    return inHome;
                }
            }
            if (alt != null) {
                String altNorm = alt.replace('\\', '/');
                String altRes = altNorm.startsWith("/") ? altNorm : "/images/" + altNorm;
                try (InputStream in = Images.class.getResourceAsStream(altRes)) {
                    if (in != null) {
                        Path inHomeAlt = AppPaths.root().resolve("images").resolve(alt);
                        Files.createDirectories(inHomeAlt.getParent());
                        Files.copy(in, inHomeAlt, StandardCopyOption.REPLACE_EXISTING);
                        return inHomeAlt;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (Files.isRegularFile(inHome)) return inHome;
        return null;
    }

    public static String importFood(Path source, String sku)  { return importInto(source, AppPaths.foodImages(), "food", sku); }
    public static String importStaff(Path source, String sku) { return importInto(source, AppPaths.staffImages(), "staff", sku); }
    public static String importBrand(Path source, String sku) { return importInto(source, AppPaths.brandImages(), "restaurant", sku); }

    private static String importInto(Path source, Path dir, String sub, String sku) {
        try {
            String ext = extension(source);
            String safe = (sku == null || sku.isBlank() ? "image" : sku.replaceAll("[^A-Za-z0-9_-]", "_"))
                    .toLowerCase(Locale.ROOT);
            String fileName = safe + "_" + System.currentTimeMillis() + "." + ext;
            Path target = dir.resolve(fileName);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            BufferedImage img = ImageIO.read(target.toFile());
            if (img == null) {
                Files.deleteIfExists(target);
                throw new AppException("The selected file is not a supported image.");
            }
            return sub + "/" + fileName;
        } catch (IOException e) {
            throw new AppException("Could not import image: " + e.getMessage(), e);
        }
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "png" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) { case "png", "jpg", "jpeg", "gif" -> ext.equals("jpeg") ? "jpg" : ext; default -> "png"; };
    }

    /** Ensures the bundled official restaurant logo picture is synchronized to the brand directory. */
    public static void generateLogo() {
        BrandLogo.ensureOfficialLogoFile();
    }

    public static void generateFoodImage(String sku, String name, String category, int seed) {
        Path target = AppPaths.foodImages().resolve(fileName(sku));
        if (Files.exists(target)) return;
        Random rnd = new Random(seed * 7919L + sku.hashCode());
        int baseHue = Math.abs((category + sku).hashCode()) % 360;
        Color c1 = Color.getHSBColor(baseHue / 360f, 0.52f, 0.78f);
        Color c2 = Color.getHSBColor(((baseHue + 28) % 360) / 360f, 0.72f, 0.42f);
        BufferedImage img = new BufferedImage(640, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, c1, 640, 420, c2));
        g.fillRect(0, 0, 640, 420);

        g.setColor(new Color(255, 255, 255, 36));
        for (int i = 0; i < 9; i++) {
            int x = rnd.nextInt(640), y = rnd.nextInt(320);
            g.fillOval(x, y, 60 + rnd.nextInt(140), 60 + rnd.nextInt(140));
        }
        g.setColor(new Color(24, 26, 32, 165));
        g.fillOval(210, 88, 220, 220);
        g.setColor(new Color(255, 243, 227));
        g.fillOval(232, 110, 176, 176);
        g.setColor(new Color(32, 35, 42));
        g.setFont(new Font("Segoe UI", Font.BOLD, 200));
        String initial = name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(initial, 320 - fm.stringWidth(initial) / 2, 238 + fm.getAscent() / 5);

        g.setColor(new Color(16, 19, 26, 200));
        g.fillRoundRect(36, 318, 568, 72, 36, 36);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 28));
        String display = name == null ? "" : name;
        if (display.length() > 30) display = display.substring(0, 29) + "…";
        g.drawString(display, 58, 362);
        g.setColor(new Color(255, 196, 92));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        g.drawString(category == null ? "" : category.toUpperCase(Locale.ROOT), 58, 388);
        g.dispose();
        try { ImageIO.write(img, "png", target.toFile()); }
        catch (IOException e) { throw new AppException("Could not create a food image for " + name + ".", e); }
    }

    public static String foodRelative(String sku) { return "food/" + fileName(sku); }

    private static String fileName(String sku) {
        String safe = (sku == null || sku.isBlank() ? "item" : sku).replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase(Locale.ROOT);
        return safe + ".png";
    }
}
