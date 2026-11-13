package com.restaurantpos.ui.views;

import com.restaurantpos.app.AppContext;
import com.restaurantpos.app.AppPaths;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.SchemaManager;
import com.restaurantpos.ui.Navigator;
import com.restaurantpos.ui.components.Ui;
import com.restaurantpos.util.BrandLogo;
import com.restaurantpos.util.Images;
import com.restaurantpos.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SettingsView extends AppView {
    private final TextField restaurantName = Ui.textField("Restaurant name");
    private final TextField phone = Ui.textField("Phone");
    private final TextField address = Ui.textField("Address");
    private final TextField tagline = Ui.textField("Tagline");
    private final TextField hours = Ui.textField("Business hours");
    private final TextField logoPath = Ui.textField("Logo path");
    private final ImageView logoPreview = new ImageView();

    private final CheckBox taxEnabled = new CheckBox("Apply tax");
    private final TextField taxRate = Ui.textField("Tax %");
    private final TextField serviceRate = Ui.textField("Service charge %");
    private final TextField delivery = Ui.textField("Default delivery charge");
    private final TextField discount = Ui.textField("Cashier discount limit %");
    private final TextField refundLimit = Ui.textField("Cashier refund limit");
    private final CheckBox partial = new CheckBox("Allow partial payments without Credit");

    private final TextField orderPrefix = Ui.textField("Order prefix");
    private final ComboBox<String> consumption = new ComboBox<>();
    private final TextField sideTypes = Ui.textField("Enabled side-order types");

    private final Spinner<Integer> sessionTimeout = new Spinner<>(5, 240, 30);
    private final Label securityInfo = Ui.muted("");
    private final VBox diagnostics = new VBox(6);

    public SettingsView(AppContext ctx, Navigator nav) {
        super(ctx, nav);
        getChildren().add(Ui.pageHeader("System Settings", "Owner-controlled configuration. Important changes are written to the audit log.",
                Ui.button("Refresh", "fas-sync-alt", "secondary-button", this::refresh)));
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Restaurant", Ui.scroll(generalTab())), new Tab("Financial", Ui.scroll(financialTab())),
                new Tab("Operations", Ui.scroll(operationsTab())), new Tab("Security", Ui.scroll(securityTab())),
                new Tab("Diagnostics", Ui.scroll(diagnosticsTab())));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
        refresh();
    }

    @Override
    public void refresh() {
        restaurantName.setText(ctx.settings().restaurantName());
        phone.setText(ctx.settings().restaurantPhone());
        address.setText(ctx.settings().restaurantAddress());
        tagline.setText(ctx.settings().get("restaurant.tagline", ""));
        hours.setText(ctx.settings().businessHours());
        logoPath.setText(ctx.settings().restaurantLogo());
        logoPreview.setImage(BrandLogo.fxRoundedLogo(ctx.settings(), 84, 14));
        taxEnabled.setSelected(ctx.settings().getBool("tax.enabled", true));
        taxRate.setText(ctx.settings().get("tax.rate", "0"));
        serviceRate.setText(ctx.settings().get("service.charge.rate", "0"));
        delivery.setText(Money.plain(ctx.settings().deliveryDefault()));
        discount.setText(ctx.settings().get("discount.max.percent", "20"));
        refundLimit.setText(Money.plain(ctx.settings().refundCashierLimit()));
        partial.setSelected(ctx.settings().allowPartialPayment());
        orderPrefix.setText(ctx.settings().orderPrefix());
        consumption.getItems().setAll("ON_ORDER_CREATE");
        consumption.setValue("ON_ORDER_CREATE");
        sideTypes.setText(ctx.settings().get("sideorder.types", "TAKEAWAY,SIDE_ORDER,PICKUP,DELIVERY"));
        sessionTimeout.getValueFactory().setValue(ctx.settings().sessionTimeoutMinutes());
        securityInfo.setText("Failed sign-in attempts are logged. Accounts lock for 10 minutes after 5 failures. "
                + "Initial passwords must be changed on first owner sign-in.");
        diagnostics.getChildren().clear();
        Map<String, String> all = ctx.systemRepo().allSettings();
        diagnostics.getChildren().addAll(
                infoLine("Application version", ctx.settings().appVersion()),
                infoLine("Database schema", "v" + new SchemaManager().version()),
                infoLine("Database file", AppPaths.database().toString()),
                infoLine("Data directory", AppPaths.root().toString()),
                infoLine("Database size", formatSize(fileSize(AppPaths.database()))),
                infoLine("Integrity check", Database.get().integrityOk() ? "OK" : "FAILED"),
                infoLine("Settings keys", String.valueOf(all.size())),
                new HBox(8, Ui.secondary("Open data folder", "fas-folder-open", () -> openPath(AppPaths.root())),
                        Ui.secondary("Open logs", "fas-file-alt", () -> openPath(AppPaths.logs())),
                        Ui.secondary("Run integrity check", "fas-heartbeat", () -> {
                            boolean ok = Database.get().integrityOk();
                            if (ok) toast("Database integrity check passed.");
                            else Ui.error(nav.window(), "Integrity check failed", "Restore a verified backup immediately.");
                            refresh();
                        })));
    }

    private VBox generalTab() {
        GridPane form = Ui.form(2);
        form.add(Ui.field("Restaurant name", restaurantName), 0, 0);
        form.add(Ui.field("Business hours", hours), 1, 0);
        form.add(Ui.field("Address", address), 0, 1, 2, 1);
        form.add(Ui.field("Telephone", phone), 0, 2);
        form.add(Ui.field("Tagline", tagline), 1, 2);
        logoPreview.setFitWidth(84);
        logoPreview.setFitHeight(84);
        logoPreview.setPreserveRatio(true);
        HBox logoBox = new HBox(14, logoPreview, Ui.field("Logo resource / path", logoPath));
        logoBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(logoBox.getChildren().get(1), Priority.ALWAYS);
        form.add(logoBox, 0, 3, 2, 1);

        Button upload = Ui.secondary("Upload new logo", "fas-image", () -> {
            File f = Ui.chooseImage(nav.window());
            if (f == null) return;
            try {
                String path = Images.importBrand(f.toPath(), "logo");
                logoPath.setText(path);
                ctx.settings().save("restaurant.logo", path);
                BrandLogo.clearCache();
                logoPreview.setImage(BrandLogo.fxRoundedLogo(ctx.settings(), 84, 14));
                ctx.audit().log("LOGO_UPDATED", "settings", null, path);
                toast("Logo updated.");
            } catch (Exception e) { Ui.error(nav.window(), e); }
        });
        Button restoreDefault = Ui.ghost("Restore official logo", "fas-undo", () -> {
            logoPath.setText(BrandLogo.OFFICIAL_RELATIVE_PATH);
            ctx.settings().save("restaurant.logo", BrandLogo.OFFICIAL_RELATIVE_PATH);
            BrandLogo.clearCache();
            BrandLogo.ensureOfficialLogoFile();
            logoPreview.setImage(BrandLogo.fxRoundedLogo(ctx.settings(), 84, 14));
            toast("Official restaurant logo restored.");
        });
        VBox card = Ui.card("Business profile", "fas-store", form, new HBox(8, upload, restoreDefault,
                Ui.primary("Save restaurant settings", "fas-save", this::saveGeneral)));
        card.setPadding(new Insets(18));
        VBox box = new VBox(14, card, Ui.muted("Currency is PKR. Amounts are stored with decimal precision and formatted consistently across receipts and reports."));
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox financialTab() {
        GridPane form = Ui.form(2);
        form.add(taxEnabled, 0, 0);
        form.add(Ui.field("Default tax rate %", taxRate), 1, 0);
        form.add(Ui.field("Service charge %", serviceRate), 0, 1);
        form.add(Ui.field("Default delivery charge", delivery), 1, 1);
        form.add(Ui.field("Cashier discount limit %", discount), 0, 2);
        form.add(Ui.field("Cashier refund limit", refundLimit), 1, 2);
        form.add(partial, 0, 3);
        VBox card = Ui.card("Pricing, tax & controls", "fas-calculator", form,
                Ui.muted("Product-specific tax rates override the default. Discounts above the cashier limit require an owner or manager. "
                        + "Refunds always preserve the original payment and require approval."),
                Ui.primary("Save financial settings", "fas-save", this::saveFinancial));
        card.setPadding(new Insets(18));
        VBox box = new VBox(14, card);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox operationsTab() {
        GridPane form = Ui.form(2);
        form.add(Ui.field("Order number prefix", orderPrefix), 0, 0);
        form.add(Ui.field("Recipe stock consumption", consumption), 1, 0);
        form.add(Ui.field("Enabled side-order types", sideTypes), 0, 1, 2, 1);
        VBox card = Ui.card("Service workflow", "fas-cogs", form,
                Ui.muted("Orders are confirmed as Draft (UNPAID) with one unpaid customer bill receipt, and completed upon full payment."),
                Ui.primary("Save operations settings", "fas-save", this::saveOperations));
        card.setPadding(new Insets(18));
        VBox box = new VBox(14, card);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox securityTab() {
        GridPane form = Ui.form(2);
        form.add(Ui.field("Session timeout (minutes)", sessionTimeout), 0, 0);
        form.add(securityInfo, 0, 1, 2, 1);
        VBox card = Ui.card("Session & access", "fas-shield-alt", form,
                Ui.muted("Permissions are enforced by backend services, not only by the interface. "
                        + "Cashiers cannot access owner modules, approvals or settings."),
                Ui.primary("Save security settings", "fas-save", this::saveSecurity));
        card.setPadding(new Insets(18));
        VBox box = new VBox(14, card);
        box.setPadding(new Insets(14));
        return box;
    }

    private VBox diagnosticsTab() {
        VBox card = Ui.card("System diagnostics", "fas-heartbeat", diagnostics,
                Ui.muted("Diagnostics are read-only. Sensitive values such as passwords and OAuth tokens are never displayed."));
        card.setPadding(new Insets(18));
        VBox box = new VBox(14);
        box.getChildren().add(card);
        box.setPadding(new Insets(14));
        return box;
    }

    private void saveGeneral() {
        try {
            save("restaurant.name", restaurantName.getText());
            save("restaurant.address", address.getText());
            save("restaurant.phone", phone.getText());
            save("restaurant.tagline", tagline.getText());
            save("business.hours", hours.getText());
            toast("Restaurant settings saved.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void saveFinancial() {
        try {
            Money.of(taxRate.getText()); Money.of(serviceRate.getText()); Money.of(delivery.getText());
            Money.of(discount.getText()); Money.of(refundLimit.getText());
            save("tax.enabled", String.valueOf(taxEnabled.isSelected()));
            save("tax.rate", Money.of(taxRate.getText()).toPlainString());
            save("service.charge.rate", Money.of(serviceRate.getText()).toPlainString());
            save("delivery.charge.default", Money.of(delivery.getText()).toPlainString());
            save("discount.max.percent", Money.of(discount.getText()).toPlainString());
            save("refund.cashier.limit", Money.of(refundLimit.getText()).toPlainString());
            save("allow.partial.payment", String.valueOf(partial.isSelected()));
            toast("Financial settings saved.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void saveOperations() {
        try {
            if (orderPrefix.getText() == null || orderPrefix.getText().isBlank()) throw new IllegalArgumentException("Order prefix is required.");
            save("order.prefix", orderPrefix.getText().trim().toUpperCase());
            save("inventory.consumption", consumption.getValue());
            save("sideorder.types", sideTypes.getText());
            toast("Operations settings saved.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void saveSecurity() {
        try {
            save("session.timeout.minutes", String.valueOf(sessionTimeout.getValue()));
            toast("Security settings saved.");
        } catch (Exception e) { Ui.error(nav.window(), e); }
    }

    private void save(String key, String value) {
        ctx.settings().save(key, value == null ? "" : value.trim());
        ctx.audit().log("SETTING_CHANGED", "settings", null, key + " = " + value);
    }

    private HBox infoLine(String label, String value) {
        Label l = Ui.muted(label);
        l.setMinWidth(160);
        Label v = Ui.label(value, "h3");
        v.setWrapText(true);
        HBox h = new HBox(10, l, v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private long fileSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0; }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1_048_576.0);
    }

    private void openPath(Path path) {
        try { java.awt.Desktop.getDesktop().open(path.toFile()); }
        catch (Exception e) { Ui.error(nav.window(), "Could not open folder", e.getMessage()); }
    }
}
