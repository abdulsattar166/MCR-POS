package com.restaurantpos;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ManifestValidationTest {

    @Test
    void testManifestIntegrity() throws Exception {
        InputStream in = getClass().getResourceAsStream("/menu/menu_manifest.json");
        assertNotNull(in, "menu_manifest.json must be present in classpath");
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);

        // Extract categories block
        int catStart = content.indexOf("\"categories\":");
        int prodStart = content.indexOf("\"products\":");
        assertTrue(catStart >= 0 && prodStart > catStart);
        String catBlock = content.substring(catStart, prodStart);

        List<String> categories = new ArrayList<>();
        Pattern catPattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher catMatcher = catPattern.matcher(catBlock);
        while (catMatcher.find()) {
            categories.add(catMatcher.group(1));
        }
        assertEquals(9, categories.size(), "Must have exactly 9 categories");
        assertEquals(List.of("Chai & Nashta", "Rice & Drinks", "BBQ & Fast Food", "Handi & Salan", "Signature Dishes",
                "Parachinari Lobya / Karahi", "Parachinari Lobya Seekh Karahi", "Cultural Tikka Seekh / Karahi", "Parachinari Chawal (Kurrmywaly Wrejy)"), categories);

        // Extract products block
        String prodBlock = content.substring(prodStart);
        Pattern prodPattern = Pattern.compile("\"sku\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher prodMatcher = prodPattern.matcher(prodBlock);
        Set<String> skus = new HashSet<>();
        int count = 0;
        while (prodMatcher.find()) {
            String sku = prodMatcher.group(1);
            String name = prodMatcher.group(2);
            assertFalse(skus.contains(sku), "Duplicate SKU found: " + sku);
            skus.add(sku);
            count++;
        }
        assertEquals(125, count, "Must have exactly 125 products in manifest");
    }
}
