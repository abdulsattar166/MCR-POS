package com.restaurantpos;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParathaImageGeneratorTest {

    private static final int W = 640;
    private static final int H = 420;

    @Test
    public void generateDistinctParathaImages() throws Exception {
        Path resDir = Path.of("src", "main", "resources", "images", "food");
        Path runDir = Path.of("C:", "RestaurantPOS", "images", "food");

        Files.createDirectories(resDir);
        Files.createDirectories(runDir);

        Path geminiAloo = resDir.resolve("gemini").resolve("aloo_paratha.jpg");
        Path geminiChicken = resDir.resolve("gemini").resolve("chicken_tikka.jpg");
        Path geminiLachadar = resDir.resolve("gemini").resolve("lachadar_paratha.jpg");

        // 1. Aloo Paratha (Pure authentic potato paratha)
        BufferedImage alooImg;
        if (Files.exists(geminiAloo)) {
            BufferedImage raw = ImageIO.read(geminiAloo.toFile());
            alooImg = scaleAndCrop(raw, W, H);
            // Add subtle culinary warmth & vignette
            applyCulinaryGrading(alooImg, new Color(245, 175, 55, 30));
        } else {
            alooImg = createProceduralParatha("Aloo Paratha", new Color(195, 130, 45));
        }

        // 2. Aloo Chicken Paratha (Crisp paratha with succulent spiced chicken chunks & herb garnish)
        BufferedImage chickenImg = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = chickenImg.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (Files.exists(geminiLachadar) && Files.exists(geminiChicken)) {
            BufferedImage parathaBase = scaleAndCrop(ImageIO.read(geminiLachadar.toFile()), W, H);
            BufferedImage chickenSrc = scaleAndCrop(ImageIO.read(geminiChicken.toFile()), W, H);

            // Draw paratha base
            g2.drawImage(parathaBase, 0, 0, null);

            // Blend succulent chicken chunks onto the center/cut section
            Graphics2D gBlend = (Graphics2D) g2.create();
            gBlend.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.65f));
            // Oval cut reveal showing succulent chicken filling
            Shape fillingArea = new Ellipse2D.Double(120, 70, 400, 280);
            gBlend.setClip(fillingArea);
            gBlend.drawImage(chickenSrc, 0, 0, null);
            gBlend.dispose();

            // Searing highlights & spice garnish
            g2.setPaint(new RadialGradientPaint(320, 210, 260,
                    new float[]{0.0f, 0.7f, 1.0f},
                    new Color[]{new Color(255, 120, 40, 45), new Color(180, 60, 20, 30), new Color(0, 0, 0, 60)}));
            g2.fillRect(0, 0, W, H);
        } else {
            chickenImg = createProceduralParatha("Aloo Chicken Paratha", new Color(210, 85, 30));
        }
        g2.dispose();
        applyCulinaryGrading(chickenImg, new Color(230, 80, 25, 35));

        // 3. Aloo Cheese Paratha (Golden toasted paratha with molten stretchy cheese melt)
        BufferedImage cheeseImg = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g3 = cheeseImg.createGraphics();
        g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g3.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (Files.exists(geminiAloo)) {
            BufferedImage alooBase = scaleAndCrop(ImageIO.read(geminiAloo.toFile()), W, H);
            g3.drawImage(alooBase, 0, 0, null);

            // Molten golden cheese overlay
            Graphics2D gCheese = (Graphics2D) g3.create();
            gCheese.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Golden cheese melt layer across center
            gCheese.setPaint(new RadialGradientPaint(320, 200, 240,
                    new float[]{0.0f, 0.45f, 0.85f, 1.0f},
                    new Color[]{new Color(255, 235, 150, 110), new Color(245, 195, 65, 80), new Color(220, 140, 30, 40), new Color(0, 0, 0, 0)}));
            gCheese.fill(new Ellipse2D.Double(140, 80, 360, 260));

            // Bubbling baked mozzarella blisters
            gCheese.setColor(new Color(165, 80, 20, 90));
            gCheese.fillOval(260, 150, 35, 22);
            gCheese.fillOval(340, 180, 42, 28);
            gCheese.fillOval(290, 230, 30, 20);
            gCheese.fillOval(390, 210, 25, 18);

            // Herb flecks (oregano/coriander on cheese)
            gCheese.setColor(new Color(40, 95, 35, 160));
            int[][] flecks = {{270, 160}, {310, 175}, {355, 195}, {295, 220}, {330, 240}, {380, 180}, {250, 190}};
            for (int[] f : flecks) {
                gCheese.fillOval(f[0], f[1], 4, 3);
            }
            gCheese.dispose();
        } else {
            cheeseImg = createProceduralParatha("Aloo Cheese Paratha", new Color(235, 175, 45));
        }
        g3.dispose();
        applyCulinaryGrading(cheeseImg, new Color(255, 210, 70, 35));

        // Save distinct files
        saveJpeg(alooImg, resDir.resolve("aloo-paratha.jpg"));
        saveJpeg(alooImg, runDir.resolve("aloo-paratha.jpg"));
        saveJpeg(alooImg, resDir.resolve("par-004.jpg"));
        saveJpeg(alooImg, runDir.resolve("par-004.jpg"));

        saveJpeg(chickenImg, resDir.resolve("aloo-chicken-paratha.jpg"));
        saveJpeg(chickenImg, runDir.resolve("aloo-chicken-paratha.jpg"));
        saveJpeg(chickenImg, resDir.resolve("par-005.jpg"));
        saveJpeg(chickenImg, runDir.resolve("par-005.jpg"));

        saveJpeg(cheeseImg, resDir.resolve("aloo-cheese-paratha.jpg"));
        saveJpeg(cheeseImg, runDir.resolve("aloo-cheese-paratha.jpg"));
        saveJpeg(cheeseImg, resDir.resolve("par-006.jpg"));
        saveJpeg(cheeseImg, runDir.resolve("par-006.jpg"));

        assertTrue(Files.exists(resDir.resolve("aloo-paratha.jpg")));
        assertTrue(Files.exists(resDir.resolve("aloo-chicken-paratha.jpg")));
        assertTrue(Files.exists(resDir.resolve("aloo-cheese-paratha.jpg")));

        // Also update C:\RestaurantPOS\data\restaurant.db if it exists
        Path mainDb = Path.of("C:", "RestaurantPOS", "data", "restaurant.db");
        if (Files.exists(mainDb)) {
            com.restaurantpos.dao.Database.use(mainDb);
            new com.restaurantpos.dao.MenuMigrator().migrate();
            System.out.println("Main restaurant.db successfully updated with new image paths!");
        }

        System.out.println("Distinct paratha images generated successfully!");
    }

    private static BufferedImage scaleAndCrop(BufferedImage src, int targetW, int targetH) {
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double srcAspect = (double) src.getWidth() / src.getHeight();
        double targetAspect = (double) targetW / targetH;

        int sx = 0, sy = 0, sw = src.getWidth(), sh = src.getHeight();
        if (srcAspect > targetAspect) {
            sw = (int) (src.getHeight() * targetAspect);
            sx = (src.getWidth() - sw) / 2;
        } else {
            sh = (int) (src.getWidth() / targetAspect);
            sy = (src.getHeight() - sh) / 2;
        }

        g.drawImage(src, 0, 0, targetW, targetH, sx, sy, sx + sw, sy + sh, null);
        g.dispose();
        return target;
    }

    private static void applyCulinaryGrading(BufferedImage img, Color tint) {
        Graphics2D g = img.createGraphics();
        g.setPaint(new RadialGradientPaint(W / 2.0f, H / 2.0f, W / 1.6f,
                new float[]{0.0f, 0.7f, 1.0f},
                new Color[]{tint, new Color(0, 0, 0, 15), new Color(0, 0, 0, 85)}));
        g.fillRect(0, 0, W, H);
        g.dispose();
    }

    private static BufferedImage createProceduralParatha(String name, Color theme) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, theme.darker(), W, H, theme));
        g.fillRect(0, 0, W, H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 28));
        g.drawString(name, 40, H / 2);
        g.dispose();
        return img;
    }

    private static void saveJpeg(BufferedImage img, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        ImageIO.write(img, "jpg", target.toFile());
    }
}
