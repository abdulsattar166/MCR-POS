package com.restaurantpos.printing;

import com.restaurantpos.service.SettingsService;
import com.restaurantpos.util.BrandLogo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prepares the circular official restaurant logo for 80 mm PDFs, JavaFX receipt previews,
 * and 1-bit ESC/POS thermal raster output.
 */
public final class ReceiptLogo {
    private static final Logger log = LoggerFactory.getLogger(ReceiptLogo.class);
    private static final int PRINT_WIDTH = 576;
    private static final int LOGO_SIZE = 240;
    private static final int CANVAS_HEIGHT = 272;

    private static final int[][] BAYER_4X4 = {
            { 15, 135,  45, 165},
            {195,  75, 225, 105},
            { 60, 180,  30, 150},
            {240, 120, 210,  90}
    };

    private static String cachedKey;
    private static long cachedModified;
    private static byte[] cachedRaster;

    private ReceiptLogo() {}

    public static byte[] escPosRaster(SettingsService settings) {
        Path custom = BrandLogo.resolveCustomLogoFile(settings);
        try {
            String key = custom != null ? custom.toAbsolutePath().normalize().toString() : BrandLogo.OFFICIAL_RESOURCE;
            long modified = custom != null && Files.exists(custom) ? Files.getLastModifiedTime(custom).toMillis() : 1L;
            synchronized (ReceiptLogo.class) {
                if (key.equals(cachedKey) && modified == cachedModified && cachedRaster != null) {
                    return cachedRaster;
                }
                BufferedImage source = BrandLogo.loadBuffered(settings);
                cachedRaster = rasterize(circleForThermal(source));
                cachedKey = key;
                cachedModified = modified;
                return cachedRaster;
            }
        } catch (Exception e) {
            log.error("Could not prepare ESC/POS receipt logo: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to prepare official circular receipt logo for printing.", e);
        }
    }

    static byte[] rasterize(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int bytesPerRow = (width + 7) / 8;
        byte[] data = new byte[bytesPerRow * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                // Keep paper margins outside the circle pure white; dither textured emblem details cleanly
                if (luminance >= 245) continue;
                int threshold = BAYER_4X4[y & 3][x & 3];
                if (luminance < threshold) {
                    data[y * bytesPerRow + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B); out.write(0x61); out.write(1); // center align
        out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0); // GS v 0
        out.write(bytesPerRow & 0xFF); out.write((bytesPerRow >> 8) & 0xFF);
        out.write(height & 0xFF); out.write((height >> 8) & 0xFF);
        out.writeBytes(data);
        out.write('\n');
        out.write(0x1B); out.write(0x61); out.write(0); // left align
        return out.toByteArray();
    }

    private static BufferedImage circleForThermal(BufferedImage source) {
        BufferedImage canvas = new BufferedImage(PRINT_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PRINT_WIDTH, CANVAS_HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double scale = Math.min((double) LOGO_SIZE / source.getWidth(), (double) LOGO_SIZE / source.getHeight());
        int width = (int) Math.round(source.getWidth() * scale);
        int height = (int) Math.round(source.getHeight() * scale);
        g.drawImage(source, (PRINT_WIDTH - width) / 2, (CANVAS_HEIGHT - height) / 2, width, height, null);
        g.dispose();
        return canvas;
    }

    /** Returns the pre-cropped circular official logo BufferedImage (no stretching). */
    public static BufferedImage circleSquareLogo(SettingsService settings) {
        return BrandLogo.circularBuffered(settings, LOGO_SIZE);
    }

    /** Returns a cached JavaFX circular official logo ready for UI display and printing. */
    public static javafx.scene.image.Image fxCircularLogo(SettingsService settings, double size) {
        return BrandLogo.fxCircularLogo(settings, size);
    }
}
