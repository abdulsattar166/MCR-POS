package com.restaurantpos.model;

import com.restaurantpos.util.Money;

import java.math.BigDecimal;

public final class Catalog {
    private Catalog() {}

    public record Category(long id, String name, String description, String imagePath, int sortOrder, boolean active) {
        @Override public String toString() { return name; }
    }

    public record Size(long id, long productId, String name, BigDecimal extraPrice,
                       BigDecimal minPrice, BigDecimal maxPrice, BigDecimal price, boolean active) {
        public Size(long id, long productId, String name, BigDecimal extraPrice, boolean active) {
            this(id, productId, name, extraPrice, null, null, null, active);
        }
        public boolean isRanged() { return minPrice != null && maxPrice != null; }
        public boolean isConfigured() { return price != null && price.signum() > 0; }
        public BigDecimal effectivePrice(BigDecimal productPrice) {
            if (price != null) return price;
            if (isRanged()) return null; // Price not configured
            return (productPrice == null ? Money.ZERO : productPrice).add(extraPrice == null ? Money.ZERO : extraPrice);
        }
    }

    public record AddOn(long id, String name, BigDecimal price, BigDecimal cost, boolean active) {
        @Override public String toString() { return name + " (+" + Money.fmt(price) + ")"; }
    }

    public record Product(long id, long categoryId, String categoryName, String sku, String name, String description,
                          String imagePath, BigDecimal cost, BigDecimal price, BigDecimal promoPrice, int prepMinutes,
                          String station, boolean available, BigDecimal taxRate, boolean trackStock, boolean active) {
        public BigDecimal effectivePrice() {
            return promoPrice != null && promoPrice.signum() > 0 ? promoPrice : price;
        }
        @Override public String toString() { return name; }
    }

}
