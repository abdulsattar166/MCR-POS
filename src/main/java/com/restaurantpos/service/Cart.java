package com.restaurantpos.service;

import com.restaurantpos.enums.OrderType;
import com.restaurantpos.model.Catalog.AddOn;
import com.restaurantpos.model.Catalog.Product;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.OrderItem;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class Cart {
    public OrderType type = OrderType.DINE_IN;
    public Long tableId;
    public String tableNo;
    public Long waiterId;
    public String waiterName;
    public Long customerId;
    public String customerName = "";
    public String customerPhone = "";
    public String customerAddress = "";
    public int guestCount = 1;
    public String notes = "";
    public BigDecimal orderDiscount = Money.ZERO;
    public BigDecimal deliveryCharge = Money.ZERO;
    public Long shiftId;
    public Long resumeOrderId;
    public final List<CartLine> lines = new ArrayList<>();

    public static final class CartLine {
        public long productId;
        public Long variantId;
        public String name;
        public String sku;
        public String detail;
        public String instructions = "";
        public BigDecimal unitPrice;
        public BigDecimal cost;
        public BigDecimal taxRate;
        public String station;
        public int qty;
        public BigDecimal discount = Money.ZERO;

        public BigDecimal gross() { return Money.mul(unitPrice, BigDecimal.valueOf(qty)); }
        public BigDecimal net() { return Money.maxZero(Money.sub(gross(), discount)); }
        public String description() { return detail == null || detail.isBlank() ? name : name + " (" + detail + ")"; }
    }

    public static final class Totals {
        public final BigDecimal gross = Money.ZERO;
        public BigDecimal subtotal = Money.ZERO;
        public BigDecimal lineDiscount = Money.ZERO;
        public BigDecimal discount = Money.ZERO;
        public BigDecimal tax = Money.ZERO;
        public BigDecimal service = Money.ZERO;
        public BigDecimal delivery = Money.ZERO;
        public BigDecimal total = Money.ZERO;
    }

    public void add(Product p, com.restaurantpos.model.Catalog.Size size, List<AddOn> addons, int qty, String instructions) {
        CartLine l = new CartLine();
        l.productId = p.id();
        l.variantId = size == null ? null : size.id();
        l.name = p.name();
        l.sku = p.sku();
        l.cost = p.cost();
        l.taxRate = p.taxRate();
        l.station = p.station();
        l.qty = Math.max(1, qty);
        String sizeName = size == null ? null : size.name();
        String addonText = addons == null ? "" : addons.stream().map(AddOn::name).collect(Collectors.joining(", "));
        l.detail = "";
        if (sizeName != null && !sizeName.isBlank()) l.detail = sizeName;
        if (!addonText.isBlank()) l.detail = l.detail.isBlank() ? addonText : l.detail + " + " + addonText;
        if (size != null) {
            BigDecimal eff = size.effectivePrice(p.effectivePrice());
            if (eff == null) {
                throw new com.restaurantpos.exception.AppException("Price not configured for " + p.name() + " (" + size.name() + "). Please configure price in Menu Management.");
            }
            l.unitPrice = Money.nz(eff);
        } else {
            l.unitPrice = Money.nz(p.effectivePrice());
        }
        if (addons != null) {
            for (AddOn a : addons) l.unitPrice = l.unitPrice.add(Money.nz(a.price()));
        }
        l.instructions = instructions == null ? "" : instructions.trim();
        lines.add(l);
    }

    public void add(Product p, String sizeName, BigDecimal sizeExtra, List<AddOn> addons, int qty, String instructions) {
        CartLine l = new CartLine();
        l.productId = p.id();
        l.name = p.name();
        l.sku = p.sku();
        l.cost = p.cost();
        l.taxRate = p.taxRate();
        l.station = p.station();
        l.qty = Math.max(1, qty);
        String addonText = addons == null ? "" : addons.stream().map(AddOn::name).collect(Collectors.joining(", "));
        l.detail = "";
        if (sizeName != null && !sizeName.isBlank()) l.detail = sizeName;
        if (!addonText.isBlank()) l.detail = l.detail.isBlank() ? addonText : l.detail + " + " + addonText;
        l.unitPrice = Money.nz(p.effectivePrice().add(sizeExtra == null ? Money.ZERO : sizeExtra));
        if (addons != null) {
            for (AddOn a : addons) l.unitPrice = l.unitPrice.add(Money.nz(a.price()));
        }
        l.instructions = instructions == null ? "" : instructions.trim();
        lines.add(l);
    }

    public boolean remove(CartLine line) { return lines.remove(line); }
    public void clear() { lines.clear(); }

    public Totals totals(BigDecimal taxRate, BigDecimal serviceRate, BigDecimal deliveryCharge, BigDecimal orderDiscount) {
        Totals t = new Totals();
        BigDecimal gross = Money.ZERO;
        BigDecimal lineDiscount = Money.ZERO;
        for (CartLine l : lines) {
            gross = gross.add(l.gross());
            lineDiscount = lineDiscount.add(l.discount);
        }
        t.subtotal = gross;
        t.lineDiscount = lineDiscount;
        t.discount = Money.maxZero(Money.add(lineDiscount, orderDiscount));
        BigDecimal discountedBase = Money.maxZero(Money.sub(gross, t.discount));
        BigDecimal weightedTax = Money.ZERO;
        for (CartLine l : lines) {
            BigDecimal net = l.net();
            BigDecimal rate = l.taxRate != null && l.taxRate.signum() > 0 ? l.taxRate : taxRate;
            weightedTax = weightedTax.add(Money.percent(net, rate));
        }
        if (t.discount.signum() > 0 && gross.signum() > 0) {
            weightedTax = Money.percent(weightedTax, BigDecimal.valueOf(100).subtract(
                    Money.div(t.discount, gross).multiply(BigDecimal.valueOf(100))));
        }
        t.tax = weightedTax;
        t.service = Money.percent(discountedBase, serviceRate);
        t.delivery = Money.nz(deliveryCharge);
        t.total = Money.add(Money.add(Money.add(discountedBase, t.tax), t.service), t.delivery);
        return t;
    }

    public static Cart fromOrder(Order order, List<OrderItem> items) {
        Cart c = new Cart();
        c.type = order.type();
        c.tableId = order.tableId();
        c.tableNo = order.tableNo();
        c.waiterId = order.waiterId();
        c.waiterName = order.waiterName() == null ? "" : order.waiterName();
        c.customerName = order.customerName() == null ? "" : order.customerName();
        c.customerPhone = order.customerPhone() == null ? "" : order.customerPhone();
        c.customerAddress = order.deliveryAddress() == null ? "" : order.deliveryAddress();
        c.guestCount = order.guestCount();
        c.notes = order.notes() == null ? "" : order.notes();
        c.orderDiscount = Money.nz(order.discountTotal());
        c.deliveryCharge = Money.nz(order.deliveryCharge());
        for (OrderItem i : items) {
            CartLine l = new CartLine();
            l.productId = i.productId() == null ? 0 : i.productId();
            l.variantId = i.variantId();
            l.name = i.name();
            l.sku = i.sku();
            l.detail = i.addons() == null ? "" : i.addons();
            l.instructions = i.instructions() == null ? "" : i.instructions();
            l.unitPrice = i.unitPrice();
            l.cost = i.cost();
            l.taxRate = BigDecimal.ZERO;
            l.station = "Kitchen";
            l.qty = i.qty();
            l.discount = Money.nz(i.discount());
            c.lines.add(l);
        }
        return c;
    }
}
