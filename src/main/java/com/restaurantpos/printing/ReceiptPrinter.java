package com.restaurantpos.printing;

import com.restaurantpos.dao.OrderRepository;
import com.restaurantpos.dao.SystemRepository;
import com.restaurantpos.service.AuditService;
import com.restaurantpos.service.SettingsService;

/**
 * Backward-compatible subclass extending the centralized ReceiptPrintingService.
 */
public final class ReceiptPrinter extends ReceiptPrintingService {
    public ReceiptPrinter(SystemRepository system, OrderRepository orders, SettingsService settings, AuditService audit) {
        super(system, orders, settings, audit);
    }
}
