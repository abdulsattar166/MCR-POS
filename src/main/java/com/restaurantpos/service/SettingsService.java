package com.restaurantpos.service;

import com.restaurantpos.app.AppPaths;
import com.restaurantpos.dao.SystemRepository;

import java.math.BigDecimal;

public final class SettingsService {
    private final SystemRepository repo;

    public SettingsService(SystemRepository repo) { this.repo = repo; }

    public String get(String key, String fallback) { return repo.setting(key, fallback); }
    public int getInt(String key, int fallback) { return repo.intSetting(key, fallback); }
    public BigDecimal getDecimal(String key, BigDecimal fallback) { return repo.decimalSetting(key, fallback); }
    public boolean getBool(String key, boolean fallback) { return "true".equalsIgnoreCase(repo.setting(key, String.valueOf(fallback))); }

    public String restaurantName()      { return get("restaurant.name", "Mubarak Cafe & Restaurant"); }
    public String restaurantAddress()   { return get("restaurant.address", "Rawalpindi 6th Road, Satellite Town, D-Block"); }
    public String restaurantPhone()     { return get("restaurant.phone", ""); }
    public String restaurantLogo()      { return get("restaurant.logo", "restaurant/logo.png"); }
    public String receiptHeader()       { return get("receipt.header", restaurantName() + "\n" + restaurantAddress() + (restaurantPhone().isBlank() ? "" : "\n" + restaurantPhone())); }
    public String receiptFooter()       { return get("receipt.footer", "Powered by ZS Sattar"); }
    public String orderPrefix()         { return get("order.prefix", "EG"); }
    public BigDecimal taxRate()         { return getBool("tax.enabled", true) ? getDecimal("tax.rate", BigDecimal.ZERO) : BigDecimal.ZERO; }
    public BigDecimal serviceRate()     { return getDecimal("service.charge.rate", BigDecimal.ZERO); }
    public BigDecimal deliveryDefault() { return getDecimal("delivery.charge.default", BigDecimal.ZERO); }
    public int receiptDelaySeconds()    { return Math.max(0, getInt("receipt.delay.seconds", 3)); }
    public int sessionTimeoutMinutes()  { return Math.max(5, getInt("session.timeout.minutes", 30)); }
    public String kitchenAutoSend()     { return get("kitchen.auto.send", "AFTER_PAYMENT"); }
    public int kitchenDelayMinutes()    { return Math.max(5, getInt("kitchen.delay.minutes", 20)); }
    public BigDecimal discountMaxPercent() { return getDecimal("discount.max.percent", BigDecimal.valueOf(20)); }
    public BigDecimal refundCashierLimit() { return getDecimal("refund.cashier.limit", BigDecimal.ZERO); }
    public boolean allowPartialPayment() { return getBool("allow.partial.payment", false); }
    public boolean showReceiptQr()      { return getBool("receipt.show_qr", true); }
    public String appVersion()          { return get("app.version", "1.0.0"); }
    public String businessHours()       { return get("business.hours", ""); }
    public String driveFolder()         { return get("google.drive.folder", ""); }
    public String googleCredentialsPath() { return get("google.credentials.path", AppPaths.config().resolve("client_secret.json").toString()); }
    public String googleTokenPath()     { return get("google.token.path", ""); }

    public void save(String key, String value) { repo.setSetting(key, value); }
}
