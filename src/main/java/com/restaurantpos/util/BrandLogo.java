package com.restaurantpos.util;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized loader, circular cropper, and cache for the official restaurant logo.
 * All UI screens, receipts, reports, and thermal printers resolve branding through this class.
 */
public final class BrandLogo {
    private static final Logger log = LoggerFactory.getLogger(BrandLogo.class);

    public static final String OFFICIAL_RESOURCE = "/images/logo/restaurant-logo.png";
    public static final String LEGACY_RESOURCE = "/images/restaurant/logo.png";
    public static final String BRANDING_RESOURCE = "/branding/logo.png";
    public static final String OFFICIAL_RELATIVE_PATH = "logo/restaurant-logo.png";

    /** Known file size (bytes) of the superseded programmatic "M" placeholder logo. */
    private static final long LEGACY_PLACEHOLDER_SIZE = 30363L;
    private static final long OFFICIAL_LOGO_SIZE = 534186L;

    private static final Map<String, javafx.scene.image.Image> FX_CACHE = new ConcurrentHashMap<>();
    private static volatile BufferedImage cachedOfficialBuffered;

    private BrandLogo() {}

    /**
     * Ensures the official logo file exists in the local runtime images directory and
     * replaces any legacy/superseded logo if present.
     */
    public static void ensureOfficialLogoFile() {
        try {
            Path target = AppPaths.brandImages().resolve("logo.png");
            boolean shouldCopy = !Files.isRegularFile(target) || Files.size(target) != OFFICIAL_LOGO_SIZE;
            if (shouldCopy) {
                try (InputStream in = openOfficialResourceStream()) {
                    if (in != null) {
                        Files.createDirectories(target.getParent());
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Path canonicalTarget = AppPaths.root().resolve("images").resolve(OFFICIAL_RELATIVE_PATH);
            if (!Files.isRegularFile(canonicalTarget) || Files.size(canonicalTarget) != OFFICIAL_LOGO_SIZE) {
                try (InputStream in = openOfficialResourceStream()) {
                    if (in != null) {
                        Files.createDirectories(canonicalTarget.getParent());
                        Files.copy(in, canonicalTarget, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not synchronize official logo file to runtime directory: {}", e.getMessage());
        }
    }

    /** Clears in-memory JavaFX and AWT logo caches (e.g., after uploading a custom brand image). */
    public static void clearCache() {
        FX_CACHE.clear();
        cachedOfficialBuffered = null;
    }

    /**
     * Resolves a custom uploaded logo path if the owner uploaded one; otherwise returns null
     * so callers load directly from the bundled official classpath resource.
     */
    public static Path resolveCustomLogoFile(SettingsService settings) {
        if (settings == null) return null;
        String configured = settings.restaurantLogo();
        if (configured == null || configured.isBlank()) return null;
        String norm = configured.replace('\\', '/').trim();
        if ("restaurant/logo.png".equalsIgnoreCase(norm)
                || "logo/restaurant-logo.png".equalsIgnoreCase(norm)
                || OFFICIAL_RESOURCE.equalsIgnoreCase(norm)) {
            return null;
        }
        try {
            Path resolved = Images.resolve(configured);
            if (resolved != null && Files.isRegularFile(resolved) && Files.size(resolved) != LEGACY_PLACEHOLDER_SIZE) {
                return resolved;
            }
        } catch (Exception e) {
            log.warn("Could not resolve configured logo '{}': {}", configured, e.getMessage());
        }
        return null;
    }

    /**
     * Loads the official restaurant picture (or custom uploaded logo if configured) as a BufferedImage.
     */
    public static BufferedImage loadBuffered(SettingsService settings) {
        Path custom = resolveCustomLogoFile(settings);
        if (custom != null) {
            try {
                BufferedImage img = ImageIO.read(custom.toFile());
                if (img != null) return img;
            } catch (Exception e) {
                log.warn("Failed reading custom logo from {}: {}", custom, e.getMessage());
            }
        }

        BufferedImage cached = cachedOfficialBuffered;
        if (cached != null) return cached;

        try (InputStream in = openOfficialResourceStream()) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    cachedOfficialBuffered = img;
                    return img;
                }
            }
        } catch (Exception e) {
            log.error("Failed loading official bundled restaurant logo resource", e);
        }

        throw new IllegalStateException("Official restaurant logo resource is missing from classpath: " + OFFICIAL_RESOURCE);
    }

    /**
     * Renders a clean, crisp, circular-cropped BufferedImage preserving exact 1:1 proportions
     * without stretching or distortion, suitable for thermal receipts, PDFs, and UI previews.
     */
    public static BufferedImage circularBuffered(SettingsService settings, int diameterPx) {
        BufferedImage source = loadBuffered(settings);
        int size = Math.max(48, diameterPx);
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int inset = 2;
        int circleDia = size - (inset * 2);
        Shape circle = new Ellipse2D.Float(inset, inset, circleDia, circleDia);
        g.setClip(circle);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

        int pad = Math.max(4, (int) Math.round(circleDia * 0.08));
        int inner = Math.max(16, circleDia - pad * 2);
        double scale = Math.min((double) inner / source.getWidth(), (double) inner / source.getHeight());
        int drawW = (int) Math.round(source.getWidth() * scale);
        int drawH = (int) Math.round(source.getHeight() * scale);
        int drawX = inset + (circleDia - drawW) / 2;
        int drawY = inset + (circleDia - drawH) / 2;
        g.drawImage(source, drawX, drawY, drawW, drawH, null);

        g.setClip(null);
        g.setColor(new Color(220, 224, 230));
        g.setStroke(new BasicStroke(Math.max(1.5f, size * 0.012f)));
        g.draw(circle);
        g.dispose();
        return canvas;
    }

    /**
     * Renders a softly rounded square BufferedImage of the official logo preserving full proportions.
     */
    public static BufferedImage roundedBuffered(SettingsService settings, int sizePx, int cornerRadius) {
        BufferedImage source = loadBuffered(settings);
        int size = Math.max(32, sizePx);
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Shape clip = new RoundRectangle2D.Float(0, 0, size, size, cornerRadius, cornerRadius);
        g.setClip(clip);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        int pad = Math.max(2, (int) Math.round(size * 0.04));
        int inner = Math.max(16, size - pad * 2);
        double scale = Math.min((double) inner / source.getWidth(), (double) inner / source.getHeight());
        int drawW = (int) Math.round(source.getWidth() * scale);
        int drawH = (int) Math.round(source.getHeight() * scale);
        g.drawImage(source, (size - drawW) / 2, (size - drawH) / 2, drawW, drawH, null);
        g.dispose();
        return canvas;
    }

    /** Returns a cached full-aspect JavaFX Image of the official logo (or custom override). */
    public static javafx.scene.image.Image fxLogo(SettingsService settings, double sizePx) {
        String key = cacheKey("full", settings, (int) Math.round(sizePx));
        return FX_CACHE.computeIfAbsent(key, k -> {
            Path custom = resolveCustomLogoFile(settings);
            if (custom != null) {
                return new javafx.scene.image.Image(custom.toUri().toString(), sizePx, sizePx, true, true);
            }
            java.net.URL url = officialResourceUrl();
            if (url != null) {
                return new javafx.scene.image.Image(url.toExternalForm(), sizePx, sizePx, true, true);
            }
            return toFxImage(loadBuffered(settings), sizePx);
        });
    }

    /** Returns a cached rounded-square JavaFX Image preserving full proportions of the official picture. */
    public static javafx.scene.image.Image fxRoundedLogo(SettingsService settings, double sizePx, double radiusPx) {
        String key = cacheKey("rounded-" + (int) radiusPx, settings, (int) Math.round(sizePx));
        return FX_CACHE.computeIfAbsent(key, k -> {
            int renderPx = Math.max(128, (int) Math.round(sizePx * 2));
            int renderRadius = (int) Math.round(radiusPx * (renderPx / Math.max(1.0, sizePx)));
            return toFxImage(roundedBuffered(settings, renderPx, renderRadius), sizePx);
        });
    }

    /** Returns a cached circular-cropped JavaFX Image for receipts and badges. */
    public static javafx.scene.image.Image fxCircularLogo(SettingsService settings, double sizePx) {
        String key = cacheKey("circle", settings, (int) Math.round(sizePx));
        return FX_CACHE.computeIfAbsent(key, k -> toFxImage(circularBuffered(settings, 280), sizePx));
    }

    private static javafx.scene.image.Image toFxImage(BufferedImage img, double sizePx) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return new javafx.scene.image.Image(new ByteArrayInputStream(baos.toByteArray()), sizePx, sizePx, true, true);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode restaurant logo image for JavaFX display", e);
        }
    }

    private static String cacheKey(String kind, SettingsService settings, int size) {
        String custom = settings == null ? "" : String.valueOf(settings.restaurantLogo());
        return kind + ":" + custom + ":" + size;
    }

    public static java.net.URL officialResourceUrl() {
        for (String res : new String[]{OFFICIAL_RESOURCE, LEGACY_RESOURCE, BRANDING_RESOURCE}) {
            java.net.URL url = BrandLogo.class.getResource(res);
            if (url != null) return url;
        }
        return null;
    }

    private static InputStream openOfficialResourceStream() {
        for (String res : new String[]{OFFICIAL_RESOURCE, LEGACY_RESOURCE, BRANDING_RESOURCE}) {
            InputStream in = BrandLogo.class.getResourceAsStream(res);
            if (in != null) return in;
        }
        return null;
    }
}
