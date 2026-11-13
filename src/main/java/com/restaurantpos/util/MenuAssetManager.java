package com.restaurantpos.util;

import com.restaurantpos.app.AppPaths;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;

/**
 * Manages downloading, processing, optimizing, and local storage of all menu food images
 * across src/main/resources/images/food and runtime C:\RestaurantPOS\images\food.
 */
public final class MenuAssetManager {
    public static final int TARGET_WIDTH = 640;
    public static final int TARGET_HEIGHT = 420;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private MenuAssetManager() {}

    /**
     * Ensures all category and product images exist locally in both project resources and runtime data directory.
     * Extracts bundled classpath image assets instantly without external network requests.
     */
    public static void ensureAllAssetsExist() {
        Path runtimeDir = AppPaths.foodImages();
        try { Files.createDirectories(runtimeDir); } catch (Exception ignored) {}

        Map<String, FoodVisualMeta> visualMeta = getVisualMetadata();
        Path resDir = Path.of("src", "main", "resources", "images", "food");

        for (String filename : visualMeta.keySet()) {
            Path runFile = runtimeDir.resolve(filename);
            Path resFile = resDir.resolve(filename);

            // 1. If runtime file already exists and is a valid photograph (> 25KB), we're good
            if (Files.isRegularFile(runFile) && fileSize(runFile) > 25000) {
                continue;
            }

            // 2. Try copying from local project resources if accessible
            if (Files.isRegularFile(resFile) && fileSize(resFile) > 20000) {
                try {
                    Files.copy(resFile, runFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    continue;
                } catch (Exception ignored) {}
            }

            // 3. Try extracting from classpath resource stream (inside JAR)
            try (InputStream in = MenuAssetManager.class.getResourceAsStream("/images/food/" + filename)) {
                if (in != null) {
                    Files.copy(in, runFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
            } catch (Exception ignored) {}
        }
    }

    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0; }
    }

    private static BufferedImage downloadAndResize(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "RestaurantPOS/1.0 (contact@restaurantpos.local)")
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) {
                BufferedImage raw = ImageIO.read(response.body());
                if (raw != null && raw.getWidth() > 10 && raw.getHeight() > 10) {
                    return scaleToTarget(raw);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static BufferedImage scaleToTarget(BufferedImage src) {
        BufferedImage target = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        double srcAspect = (double) src.getWidth() / src.getHeight();
        double targetAspect = (double) TARGET_WIDTH / TARGET_HEIGHT;

        int sx = 0, sy = 0, sw = src.getWidth(), sh = src.getHeight();
        if (srcAspect > targetAspect) {
            sw = (int) (src.getHeight() * targetAspect);
            sx = (src.getWidth() - sw) / 2;
        } else {
            sh = (int) (src.getWidth() / targetAspect);
            sy = (src.getHeight() - sh) / 2;
        }

        g.drawImage(src, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, sx, sy, sx + sw, sy + sh, null);
        g.dispose();
        return target;
    }

    public static BufferedImage synthesizeFoodImage(String title, String category, String tag, Color theme) {
        BufferedImage img = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Warm culinary background gradient
        Color darkTone = new Color(Math.max(10, theme.getRed() / 4), Math.max(10, theme.getGreen() / 4), Math.max(12, theme.getBlue() / 4));
        Color midTone = new Color(theme.getRed() / 2, theme.getGreen() / 2, theme.getBlue() / 2);
        g.setPaint(new GradientPaint(0, 0, darkTone, TARGET_WIDTH, TARGET_HEIGHT, midTone));
        g.fillRect(0, 0, TARGET_WIDTH, TARGET_HEIGHT);

        // Elegant geometric decorative motifs
        g.setColor(new Color(255, 255, 255, 18));
        g.fillOval(380, -40, 300, 300);
        g.fillOval(-60, 200, 240, 240);

        // Center stylized food plate showcase
        int plateX = TARGET_WIDTH / 2 - 90;
        int plateY = 60;
        int plateSize = 180;

        g.setColor(new Color(0, 0, 0, 70));
        g.fillOval(plateX - 6, plateY - 6, plateSize + 12, plateSize + 12);

        g.setPaint(new GradientPaint(plateX, plateY, new Color(248, 246, 240), plateX + plateSize, plateY + plateSize, new Color(225, 220, 210)));
        g.fillOval(plateX, plateY, plateSize, plateSize);

        g.setColor(new Color(210, 205, 195));
        g.setStroke(new BasicStroke(3));
        g.drawOval(plateX + 16, plateY + 16, plateSize - 32, plateSize - 32);

        // Core culinary food icon / emblem
        g.setPaint(new GradientPaint(plateX + 30, plateY + 30, theme, plateX + plateSize - 30, plateY + plateSize - 30, theme.darker()));
        g.fillOval(plateX + 30, plateY + 30, plateSize - 60, plateSize - 60);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 52));
        String initial = (title == null || title.isBlank()) ? "M" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        FontMetrics fmInit = g.getFontMetrics();
        g.drawString(initial, plateX + (plateSize - fmInit.stringWidth(initial)) / 2, plateY + (plateSize + fmInit.getAscent() - fmInit.getDescent()) / 2 - 2);

        // Bottom card details overlay
        int cardH = 110;
        int cardY = TARGET_HEIGHT - cardH - 16;
        g.setColor(new Color(18, 20, 26, 215));
        g.fillRoundRect(24, cardY, TARGET_WIDTH - 48, cardH, 24, 24);

        g.setColor(new Color(theme.getRed(), theme.getGreen(), theme.getBlue(), 120));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(24, cardY, TARGET_WIDTH - 48, cardH, 24, 24);

        // Category Tag Badge
        g.setColor(theme);
        int tagW = 140;
        int tagH = 22;
        g.fillRoundRect(42, cardY + 16, tagW, tagH, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 11));
        FontMetrics fmTag = g.getFontMetrics();
        String displayTag = (tag != null ? tag : category).toUpperCase(Locale.ROOT);
        if (displayTag.length() > 18) displayTag = displayTag.substring(0, 17) + "…";
        g.drawString(displayTag, 42 + (tagW - fmTag.stringWidth(displayTag)) / 2, cardY + 31);

        // Product Title
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 22));
        FontMetrics fmTitle = g.getFontMetrics();
        String displayTitle = title;
        if (fmTitle.stringWidth(displayTitle) > TARGET_WIDTH - 120) {
            while (displayTitle.length() > 5 && fmTitle.stringWidth(displayTitle + "…") > TARGET_WIDTH - 120) {
                displayTitle = displayTitle.substring(0, displayTitle.length() - 1);
            }
            displayTitle += "…";
        }
        g.drawString(displayTitle, 42, cardY + 66);

        // Subtle category subtitle
        g.setColor(new Color(200, 205, 215));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        g.drawString("Mubarak Cafe & Restaurant  ·  " + category, 42, cardY + 90);

        g.dispose();
        return img;
    }

    private static void saveImage(BufferedImage img, Path file) {
        try {
            Files.createDirectories(file.getParent());
            ImageIO.write(img, "jpg", file.toFile());
        } catch (Exception ignored) {}
    }

    public record FoodVisualMeta(String title, String category, String tag, Color themeColor) {}

    public static Map<String, FoodVisualMeta> getVisualMetadata() {
        Map<String, FoodVisualMeta> m = new LinkedHashMap<>();

        // Categories
        m.put("cat_chai_nashta.jpg", new FoodVisualMeta("Chai & Nashta", "Breakfast & Tea", "Nashta", new Color(185, 105, 45)));
        m.put("cat_rice_drinks.jpg", new FoodVisualMeta("Rice & Drinks", "Biryani & Beverages", "Rice & Drinks", new Color(215, 145, 30)));
        m.put("cat_bbq_fastfood.jpg", new FoodVisualMeta("BBQ & Fast Food", "Grills, Burgers & Fries", "BBQ & Fast Food", new Color(195, 55, 35)));
        m.put("cat_handi_salan.jpg", new FoodVisualMeta("Handi & Salan", "Curries, Daal & Karahi", "Handi & Salan", new Color(160, 75, 40)));
        m.put("cat_signature_dishes.jpg", new FoodVisualMeta("Signature Dishes", "Specialities of Parachinar", "Signature", new Color(170, 40, 45)));

        // Chai & Hot Beverages
        Color chaiColor = new Color(175, 100, 45);
        m.put("tea-001.jpg", new FoodVisualMeta("Chai", "Chai & Nashta", "Chai", chaiColor));
        m.put("tea-002.jpg", new FoodVisualMeta("Special Chai", "Chai & Nashta", "Special Chai", chaiColor));
        m.put("tea-003.jpg", new FoodVisualMeta("Gurh wali Chai", "Chai & Nashta", "Gurh Chai", chaiColor));
        m.put("tea-004.jpg", new FoodVisualMeta("Doodh Qawa", "Chai & Nashta", "Qawa", new Color(120, 140, 50)));
        m.put("tea-005.jpg", new FoodVisualMeta("Sulaimani Qawa", "Chai & Nashta", "Sulaimani", new Color(140, 150, 45)));
        m.put("tea-006.jpg", new FoodVisualMeta("Sada Qawa", "Chai & Nashta", "Green Tea", new Color(110, 145, 60)));
        m.put("tea-007.jpg", new FoodVisualMeta("Karak Chai", "Chai & Nashta", "Karak Chai", chaiColor));
        m.put("tea-008.jpg", new FoodVisualMeta("Doodh Pati Chai", "Chai & Nashta", "Doodh Pati", chaiColor));
        m.put("tea-009.jpg", new FoodVisualMeta("Chocolate Chai", "Chai & Nashta", "Chocolate Chai", new Color(125, 75, 50)));
        m.put("tea-010.jpg", new FoodVisualMeta("Kashmiri Chai", "Chai & Nashta", "Pink Tea", new Color(210, 105, 135)));

        // Eggs & Malai
        Color eggColor = new Color(210, 155, 35);
        m.put("egg-001.jpg", new FoodVisualMeta("Half Fry Egg", "Chai & Nashta", "Eggs", eggColor));
        m.put("egg-002.jpg", new FoodVisualMeta("Omelette", "Chai & Nashta", "Omelette", eggColor));
        m.put("egg-003.jpg", new FoodVisualMeta("Full Fry Egg", "Chai & Nashta", "Eggs", eggColor));
        m.put("egg-004.jpg", new FoodVisualMeta("Boiled Egg", "Chai & Nashta", "Boiled Egg", eggColor));
        m.put("egg-005.jpg", new FoodVisualMeta("Malai (Half)", "Chai & Nashta", "Dairy Malai", new Color(220, 205, 160)));
        m.put("egg-006.jpg", new FoodVisualMeta("Malai (Full)", "Chai & Nashta", "Dairy Malai", new Color(220, 205, 160)));

        // Parathas
        Color parColor = new Color(195, 130, 45);
        m.put("par-001.jpg", new FoodVisualMeta("Lachadar Paratha", "Chai & Nashta", "Paratha", parColor));
        m.put("par-002.jpg", new FoodVisualMeta("Khushk Paratha", "Chai & Nashta", "Paratha", parColor));
        m.put("par-003.jpg", new FoodVisualMeta("Pizza Paratha", "Chai & Nashta", "Pizza Paratha", new Color(190, 65, 35)));
        m.put("par-004.jpg", new FoodVisualMeta("Aloo Paratha", "Chai & Nashta", "Aloo Paratha", parColor));
        m.put("par-005.jpg", new FoodVisualMeta("Aloo Chicken Paratha", "Chai & Nashta", "Chicken Paratha", new Color(205, 95, 30)));
        m.put("par-006.jpg", new FoodVisualMeta("Aloo Cheese Paratha", "Chai & Nashta", "Cheese Paratha", new Color(225, 160, 40)));
        m.put("aloo-paratha.jpg", new FoodVisualMeta("Aloo Paratha", "Chai & Nashta", "Aloo Paratha", parColor));
        m.put("aloo-chicken-paratha.jpg", new FoodVisualMeta("Aloo Chicken Paratha", "Chai & Nashta", "Chicken Paratha", new Color(205, 95, 30)));
        m.put("aloo-cheese-paratha.jpg", new FoodVisualMeta("Aloo Cheese Paratha", "Chai & Nashta", "Cheese Paratha", new Color(225, 160, 40)));
        m.put("par-007.jpg", new FoodVisualMeta("Chicken Paratha", "Chai & Nashta", "Chicken Paratha", parColor));
        m.put("par-008.jpg", new FoodVisualMeta("Chicken Cheese Paratha", "Chai & Nashta", "Cheese Paratha", parColor));
        m.put("par-009.jpg", new FoodVisualMeta("Malai Paratha", "Chai & Nashta", "Malai Paratha", new Color(210, 170, 110)));
        m.put("par-010.jpg", new FoodVisualMeta("Honey Malai Paratha", "Chai & Nashta", "Honey Malai", new Color(220, 160, 40)));
        m.put("par-011.jpg", new FoodVisualMeta("Chocolate Paratha", "Chai & Nashta", "Chocolate Paratha", new Color(115, 65, 45)));
        m.put("par-012.jpg", new FoodVisualMeta("Mayonese Paratha", "Chai & Nashta", "Mayo Paratha", parColor));
        m.put("par-013.jpg", new FoodVisualMeta("Cheese Paratha", "Chai & Nashta", "Cheese Paratha", parColor));
        m.put("par-014.jpg", new FoodVisualMeta("Lahori Paratha", "Chai & Nashta", "Lahori Special", parColor));

        // Rice / Biryani / Pulao
        Color biryaniColor = new Color(215, 125, 25);
        m.put("rce-001.jpg", new FoodVisualMeta("Aloo Biryani (Half)", "Rice & Drinks", "Biryani", biryaniColor));
        m.put("rce-002.jpg", new FoodVisualMeta("Aloo Biryani (Full)", "Rice & Drinks", "Biryani", biryaniColor));
        m.put("rce-003.jpg", new FoodVisualMeta("Single Aloo Chicken Biryani", "Rice & Drinks", "Biryani", biryaniColor));
        m.put("rce-004.jpg", new FoodVisualMeta("Double Chicken Biryani", "Rice & Drinks", "Biryani", biryaniColor));
        m.put("plu-001.jpg", new FoodVisualMeta("Sada Pulao", "Rice & Drinks", "Pulao", new Color(185, 140, 50)));
        m.put("plu-002.jpg", new FoodVisualMeta("Single Chicken Pulao", "Rice & Drinks", "Pulao", new Color(185, 140, 50)));
        m.put("plu-003.jpg", new FoodVisualMeta("Double Chicken Pulao", "Rice & Drinks", "Pulao", new Color(185, 140, 50)));

        // Roti / Naan
        Color naanColor = new Color(190, 145, 65);
        m.put("rot-001.jpg", new FoodVisualMeta("Sada Roti", "Rice & Drinks", "Roti", naanColor));
        m.put("rot-002.jpg", new FoodVisualMeta("Naan", "Rice & Drinks", "Naan", naanColor));
        m.put("rot-003.jpg", new FoodVisualMeta("Roghani Naan", "Rice & Drinks", "Roghani Naan", naanColor));
        m.put("rot-004.jpg", new FoodVisualMeta("Tandoori Paratha", "Rice & Drinks", "Tandoori Paratha", naanColor));
        m.put("rot-005.jpg", new FoodVisualMeta("Kulcha", "Rice & Drinks", "Kulcha", naanColor));

        // Soft Drinks & Beverages
        Color drinkColor = new Color(45, 115, 195);
        m.put("bev-001.jpg", new FoodVisualMeta("Regular Drink", "Rice & Drinks", "Cold Drink", new Color(190, 40, 40)));
        m.put("bev-002.jpg", new FoodVisualMeta("1 Liter", "Rice & Drinks", "Cold Drink", new Color(190, 40, 40)));
        m.put("bev-003.jpg", new FoodVisualMeta("1.5 Liter", "Rice & Drinks", "Cold Drink", new Color(190, 40, 40)));
        m.put("bev-004.jpg", new FoodVisualMeta("Lassi", "Rice & Drinks", "Traditional Lassi", new Color(220, 215, 190)));
        m.put("bev-005.jpg", new FoodVisualMeta("Mineral Water", "Rice & Drinks", "Mineral Water", drinkColor));

        // Raita & Salad
        m.put("sld-001.jpg", new FoodVisualMeta("Raita", "Rice & Drinks", "Mint Raita", new Color(105, 160, 85)));
        m.put("sld-002.jpg", new FoodVisualMeta("Salad", "Rice & Drinks", "Fresh Salad", new Color(85, 155, 65)));

        // BBQ
        Color bbqColor = new Color(195, 55, 30);
        m.put("bbq-001.jpg", new FoodVisualMeta("Chest Piece", "BBQ & Fast Food", "Chicken Tikka", bbqColor));
        m.put("bbq-002.jpg", new FoodVisualMeta("Leg Piece", "BBQ & Fast Food", "Chicken Tikka", bbqColor));
        m.put("bbq-003.jpg", new FoodVisualMeta("Seekh Tikka", "BBQ & Fast Food", "Seekh Kabab", bbqColor));

        // Fast Food & Refreshment
        Color fastFoodColor = new Color(210, 135, 30);
        m.put("fst-001.jpg", new FoodVisualMeta("Fries (Small)", "BBQ & Fast Food", "French Fries", fastFoodColor));
        m.put("fst-002.jpg", new FoodVisualMeta("Fries (Medium)", "BBQ & Fast Food", "French Fries", fastFoodColor));
        m.put("fst-003.jpg", new FoodVisualMeta("Fries (Large)", "BBQ & Fast Food", "French Fries", fastFoodColor));
        m.put("fst-004.jpg", new FoodVisualMeta("Loaded Fries", "BBQ & Fast Food", "Loaded Fries", fastFoodColor));
        m.put("fst-005.jpg", new FoodVisualMeta("Chicken Shawarma (Small)", "BBQ & Fast Food", "Shawarma", new Color(185, 110, 45)));
        m.put("fst-006.jpg", new FoodVisualMeta("Chicken Shawarma (Large)", "BBQ & Fast Food", "Shawarma", new Color(185, 110, 45)));
        m.put("fst-007.jpg", new FoodVisualMeta("Zinger Shawarma (Large)", "BBQ & Fast Food", "Zinger Wrap", new Color(195, 95, 35)));
        m.put("fst-008.jpg", new FoodVisualMeta("Chicken Roll Paratha", "BBQ & Fast Food", "Roll Paratha", new Color(185, 120, 50)));
        m.put("fst-009.jpg", new FoodVisualMeta("Zinger Roll Paratha", "BBQ & Fast Food", "Roll Paratha", new Color(195, 110, 40)));
        m.put("pakura.jpg", new FoodVisualMeta("Pakura", "BBQ & Fast Food", "Crispy Pakura", fastFoodColor));
        m.put("sadha-paratha.jpg", new FoodVisualMeta("Sadha Paratha", "Chai & Nashta", "Paratha", new Color(195, 130, 45)));
        m.put("cake.jpg", new FoodVisualMeta("Cake", "Chai & Nashta", "Cake Slice", new Color(185, 105, 45)));

        // Burgers
        Color burgerColor = new Color(185, 80, 35);
        m.put("bur-001.jpg", new FoodVisualMeta("Zinger Burger", "BBQ & Fast Food", "Crispy Burger", burgerColor));
        m.put("bur-002.jpg", new FoodVisualMeta("Chicken Burger", "BBQ & Fast Food", "Burger", burgerColor));
        m.put("bur-003.jpg", new FoodVisualMeta("Anda Shami Burger", "BBQ & Fast Food", "Street Burger", burgerColor));
        m.put("bur-004.jpg", new FoodVisualMeta("Shahi Burger", "BBQ & Fast Food", "Shahi Burger", burgerColor));
        m.put("bur-005.jpg", new FoodVisualMeta("Shami Burger", "BBQ & Fast Food", "Shami Burger", burgerColor));

        // Soup
        Color soupColor = new Color(205, 140, 45);
        m.put("sup-001.jpg", new FoodVisualMeta("Chicken Corn Soup (Sada)", "BBQ & Fast Food", "Corn Soup", soupColor));
        m.put("sup-002.jpg", new FoodVisualMeta("Chicken Corn Soup (Anda)", "BBQ & Fast Food", "Egg Corn Soup", soupColor));

        // Sabzi Mix & Vegetables
        Color vegColor = new Color(145, 120, 45);
        m.put("veg-001.jpg", new FoodVisualMeta("Mix Sabzi (Half)", "Handi & Salan", "Mix Sabzi", new Color(125, 135, 50)));
        m.put("veg-002.jpg", new FoodVisualMeta("Mix Sabzi (Full)", "Handi & Salan", "Mix Sabzi", new Color(125, 135, 50)));
        m.put("veg-003.jpg", new FoodVisualMeta("Bhindi (Half)", "Handi & Salan", "Bhindi Masala", new Color(95, 130, 50)));
        m.put("veg-004.jpg", new FoodVisualMeta("Bhindi (Full)", "Handi & Salan", "Bhindi Masala", new Color(95, 130, 50)));
        m.put("veg-005.jpg", new FoodVisualMeta("Anda Tamatar (Half)", "Handi & Salan", "Anda Tamatar", new Color(190, 85, 40)));
        m.put("veg-006.jpg", new FoodVisualMeta("Anda Tamatar (Full)", "Handi & Salan", "Anda Tamatar", new Color(190, 85, 40)));
        m.put("veg-007.jpg", new FoodVisualMeta("Daal Mash (Half)", "Handi & Salan", "Daal Mash", new Color(195, 175, 130)));
        m.put("veg-008.jpg", new FoodVisualMeta("Daal Mash (Full)", "Handi & Salan", "Daal Mash", new Color(195, 175, 130)));
        m.put("veg-009.jpg", new FoodVisualMeta("Daal Chana (Half)", "Handi & Salan", "Daal Chana", new Color(205, 160, 45)));
        m.put("veg-010.jpg", new FoodVisualMeta("Daal Chana (Full)", "Handi & Salan", "Daal Chana", new Color(205, 160, 45)));
        m.put("veg-011.jpg", new FoodVisualMeta("Chany (Half)", "Handi & Salan", "Lahori Chany", new Color(190, 130, 50)));
        m.put("veg-012.jpg", new FoodVisualMeta("Chany (Full)", "Handi & Salan", "Lahori Chany", new Color(190, 130, 50)));
        m.put("veg-013.jpg", new FoodVisualMeta("Lobya (Half)", "Handi & Salan", "Red Lobya", new Color(165, 60, 45)));
        m.put("veg-014.jpg", new FoodVisualMeta("Lobya (Full)", "Handi & Salan", "Red Lobya", new Color(165, 60, 45)));

        // Chicken Dishes
        Color chkColor = new Color(185, 90, 40);
        m.put("chk-001.jpg", new FoodVisualMeta("Chicken Qorma (Half)", "Handi & Salan", "Chicken Qorma", chkColor));
        m.put("chk-002.jpg", new FoodVisualMeta("Chicken Qorma (Full)", "Handi & Salan", "Chicken Qorma", chkColor));
        m.put("chk-003.jpg", new FoodVisualMeta("Chicken Talfarizi (Half)", "Handi & Salan", "Chicken Jalfrezi", new Color(195, 75, 35)));
        m.put("chk-004.jpg", new FoodVisualMeta("Chicken Talfarizi (Full)", "Handi & Salan", "Chicken Jalfrezi", new Color(195, 75, 35)));
        m.put("chk-005.jpg", new FoodVisualMeta("Pota Kaliji (Half)", "Handi & Salan", "Pota Kaliji", new Color(150, 65, 45)));
        m.put("chk-006.jpg", new FoodVisualMeta("Pota Kaliji (Full)", "Handi & Salan", "Pota Kaliji", new Color(150, 65, 45)));
        m.put("chk-007.jpg", new FoodVisualMeta("Chicken Qeema (Half)", "Handi & Salan", "Chicken Qeema", chkColor));
        m.put("chk-008.jpg", new FoodVisualMeta("Chicken Qeema (Full)", "Handi & Salan", "Chicken Qeema", chkColor));

        // Beef Dishes
        Color befColor = new Color(160, 60, 40);
        m.put("bef-001.jpg", new FoodVisualMeta("Aloo Gosht (Half)", "Handi & Salan", "Aloo Gosht", befColor));
        m.put("bef-002.jpg", new FoodVisualMeta("Aloo Gosht (Full)", "Handi & Salan", "Aloo Gosht", befColor));
        m.put("bef-003.jpg", new FoodVisualMeta("Aloo Qeema (Half)", "Handi & Salan", "Aloo Qeema", befColor));
        m.put("bef-004.jpg", new FoodVisualMeta("Aloo Qeema (Full)", "Handi & Salan", "Aloo Qeema", befColor));
        m.put("bef-005.jpg", new FoodVisualMeta("Gosht Fry (Half)", "Handi & Salan", "Gosht Fry", befColor));
        m.put("bef-006.jpg", new FoodVisualMeta("Gosht Fry (Full)", "Handi & Salan", "Gosht Fry", befColor));

        // Karahi
        Color krhColor = new Color(185, 65, 35);
        m.put("krh-001.jpg", new FoodVisualMeta("Chicken Karahi (Half)", "Handi & Salan", "Chicken Karahi", krhColor));
        m.put("krh-002.jpg", new FoodVisualMeta("Chicken Karahi (Full)", "Handi & Salan", "Chicken Karahi", krhColor));
        m.put("krh-003.jpg", new FoodVisualMeta("Chicken White Karahi (Half)", "Handi & Salan", "White Karahi", new Color(205, 185, 145)));
        m.put("krh-004.jpg", new FoodVisualMeta("Chicken White Karahi (Full)", "Handi & Salan", "White Karahi", new Color(205, 185, 145)));

        // Signature Dishes
        Color sigColor = new Color(175, 45, 45);
        m.put("sig-001.jpg", new FoodVisualMeta("Parachinari Lobya Sada", "Signature Dishes", "Parachinari Lobya", sigColor));
        m.put("sig-002.jpg", new FoodVisualMeta("Parachinari Lobya Karahi (Quarter)", "Signature Dishes", "Lobya Karahi", sigColor));
        m.put("sig-003.jpg", new FoodVisualMeta("Parachinari Lobya Karahi (2 Quarter)", "Signature Dishes", "Lobya Karahi", sigColor));
        m.put("sig-004.jpg", new FoodVisualMeta("Parachinari Lobya Karahi (3 Quarter)", "Signature Dishes", "Lobya Karahi", sigColor));
        m.put("sig-005.jpg", new FoodVisualMeta("Parachinari Lobya Karahi (4 Quarter)", "Signature Dishes", "Lobya Karahi", sigColor));

        m.put("sig-006.jpg", new FoodVisualMeta("Parachinari Lobya Seekh Karahi (Quarter)", "Signature Dishes", "Lobya Seekh Karahi", sigColor));
        m.put("sig-007.jpg", new FoodVisualMeta("Parachinari Lobya Seekh Karahi (2 Quarter)", "Signature Dishes", "Lobya Seekh Karahi", sigColor));
        m.put("sig-008.jpg", new FoodVisualMeta("Parachinari Lobya Seekh Karahi (3 Quarter)", "Signature Dishes", "Lobya Seekh Karahi", sigColor));
        m.put("sig-009.jpg", new FoodVisualMeta("Parachinari Lobya Seekh Karahi (4 Quarter)", "Signature Dishes", "Lobya Seekh Karahi", sigColor));

        m.put("sig-010.jpg", new FoodVisualMeta("Cultural Tikka Seekh", "Signature Dishes", "Cultural Tikka", new Color(195, 60, 30)));
        m.put("sig-011.jpg", new FoodVisualMeta("Cultural Tikka Seekh Karahi", "Signature Dishes", "Tikka Seekh Karahi", new Color(190, 50, 35)));

        Color sundayColor = new Color(195, 115, 30);
        m.put("sig-012.jpg", new FoodVisualMeta("Kammiwali Wrejy (1 Person)", "Signature Dishes", "Sunday Special", sundayColor));
        m.put("sig-013.jpg", new FoodVisualMeta("Kammiwali Wrejy (2 Persons)", "Signature Dishes", "Sunday Special", sundayColor));
        m.put("sig-014.jpg", new FoodVisualMeta("Kammiwali Wrejy (3 Persons)", "Signature Dishes", "Sunday Special", sundayColor));
        m.put("sig-015.jpg", new FoodVisualMeta("Kammiwali Wrejy (4 Persons)", "Signature Dishes", "Sunday Special", sundayColor));

        // 4 New Categories and 7 New Menu Items
        Color lobyaColor = new Color(175, 45, 45);
        m.put("parachinari-lobya-sada.jpg", new FoodVisualMeta("Parachinari Lobya Sada", "Parachinari Lobya / Karahi", "Lobya Sada", lobyaColor));
        m.put("parachinari-lobya-karahi.jpg", new FoodVisualMeta("Parachinari Lobya Karahi", "Parachinari Lobya / Karahi", "Lobya Karahi", lobyaColor));
        m.put("parachinari-lobya-seekh-karahi.jpg", new FoodVisualMeta("Parachinari Lobya Seekh Karahi", "Parachinari Lobya Seekh Karahi", "Seekh Karahi", lobyaColor));
        m.put("cultural-tikka-seekh.jpg", new FoodVisualMeta("Cultural Tikka Seekh", "Cultural Tikka Seekh / Karahi", "Tikka Seekh", new Color(195, 60, 30)));
        m.put("cultural-tikka-seekh-karahi.jpg", new FoodVisualMeta("Cultural Tikka Seekh Karahi", "Cultural Tikka Seekh / Karahi", "Seekh Karahi", new Color(190, 50, 35)));
        m.put("parachinari-chawal-kurrmywaly-wrejy.jpg", new FoodVisualMeta("Parachinari Chawal", "Parachinari Chawal (Kurrmywaly Wrejy)", "Kurrmywaly Wrejy", sundayColor));
        m.put("kurrmy-waily-wrejy-gade-wady.jpg", new FoodVisualMeta("Gade Wady", "Parachinari Chawal (Kurrmywaly Wrejy)", "Gade Wady", sundayColor));

        m.put("cat_parachinari_lobya_karahi.jpg", new FoodVisualMeta("Parachinari Lobya / Karahi", "Categories", "Category", lobyaColor));
        m.put("cat_parachinari_lobya_seekh_karahi.jpg", new FoodVisualMeta("Parachinari Lobya Seekh Karahi", "Categories", "Category", lobyaColor));
        m.put("cat_cultural_tikka_seekh_karahi.jpg", new FoodVisualMeta("Cultural Tikka Seekh / Karahi", "Categories", "Category", new Color(190, 50, 35)));
        m.put("cat_parachinari_chawal.jpg", new FoodVisualMeta("Parachinari Chawal", "Categories", "Category", sundayColor));

        return m;
    }

    private static Map<String, String> getCuratedImageUrls() {
        Map<String, String> u = new HashMap<>();

        // Verified public domain & CC food photography sources
        u.put("tea-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/04/Masala_Chai.JPG");
        u.put("tea-007.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/04/Masala_Chai.JPG");
        u.put("tea-008.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/04/Masala_Chai.JPG");
        u.put("tea-010.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/4e/Noon_Chai_-_Kashmiri_Tea.jpg");

        u.put("egg-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/2/23/Fried_egg%2C_sunny_side_up.jpg");
        u.put("egg-002.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/48/Omelette_with_cheese_and_herbs.jpg");
        u.put("egg-004.jpg", "https://upload.wikimedia.org/wikipedia/commons/e/e0/Boiled_egg_with_toast.jpg");

        u.put("par-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/a/ae/Lachha_Paratha.JPG");
        u.put("par-004.jpg", "https://upload.wikimedia.org/wikipedia/commons/d/d7/Aloo_Paratha_also_known_as_Batata_Paratha.jpg");

        u.put("rce-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/5a/%22Hyderabadi_Dum_Biryani%22.jpg");
        u.put("rce-003.jpg", "https://upload.wikimedia.org/wikipedia/commons/3/35/Biryani_Home_Made.jpg");
        u.put("rce-004.jpg", "https://upload.wikimedia.org/wikipedia/commons/3/35/Biryani_Home_Made.jpg");
        u.put("plu-002.jpg", "https://upload.wikimedia.org/wikipedia/commons/e/ee/Chicken_Pulao.jpg");

        u.put("rot-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/0/0c/Roti_-_Indian_Flatbread.jpg");
        u.put("rot-002.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/4c/Naan_bread.jpg");
        u.put("rot-003.jpg", "https://upload.wikimedia.org/wikipedia/commons/6/60/Sesame_Naan.jpg");
        u.put("rot-005.jpg", "https://upload.wikimedia.org/wikipedia/commons/2/2e/Kulcha.jpg");

        u.put("bev-004.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/91/Sweet_Lassi.jpg");
        u.put("bev-005.jpg", "https://upload.wikimedia.org/wikipedia/commons/6/66/Mineral_water_bottle.jpg");

        u.put("sld-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/7b/Mint_Raita.jpg");
        u.put("sld-002.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/94/Salad_platter.jpg");

        u.put("bbq-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/6/60/Chicken_tikka_dry.jpg");
        u.put("bbq-003.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/9e/Seekh_kebab.jpg");

        u.put("fst-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/73/French_fries_in_a_basket.jpg");
        u.put("fst-005.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/52/Chicken_shawarma_wrap.jpg");
        u.put("bur-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/4/47/Hamburger_%28black_bg%29.jpg");
        u.put("sup-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/7/77/Chicken_corn_soup.jpg");

        u.put("veg-003.jpg", "https://upload.wikimedia.org/wikipedia/commons/9/96/Bhindi_masala.jpg");
        u.put("veg-009.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/5b/Tarka_Daal.jpg");
        u.put("veg-011.jpg", "https://upload.wikimedia.org/wikipedia/commons/b/b3/Chana_masala.jpg");
        u.put("veg-013.jpg", "https://upload.wikimedia.org/wikipedia/commons/1/1a/Rajma_curry.jpg");

        u.put("chk-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/5/52/Chicken_korma.jpg");
        u.put("krh-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/8/86/Chicken_karahi.jpg");
        u.put("sig-001.jpg", "https://upload.wikimedia.org/wikipedia/commons/1/1a/Rajma_curry.jpg");

        return u;
    }
}
