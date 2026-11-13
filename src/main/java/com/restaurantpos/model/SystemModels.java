package com.restaurantpos.model;

import com.restaurantpos.enums.Role;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class SystemModels {
    private SystemModels() {}

    public record User(long id, String username, String fullName, Role role, boolean active, boolean mustChangePassword,
                       String photoPath, LocalDateTime createdAt, LocalDateTime lastLogin, Integer waiterNo) {
        public User(long id, String username, String fullName, Role role, boolean active, boolean mustChangePassword,
                    String photoPath, LocalDateTime createdAt, LocalDateTime lastLogin) {
            this(id, username, fullName, role, active, mustChangePassword, photoPath, createdAt, lastLogin, null);
        }
        public String waiterDisplayName() {
            return fullName;
        }
        @Override public String toString() {
            return fullName + " (" + role.label() + ")";
        }
    }

    public record AuditEvent(long id, Long userId, String username, String action, String entity, Long entityId,
                             String details, LocalDateTime createdAt) {}

    public record Notification(long id, String type, String title, String message, String severity,
                               boolean read, LocalDateTime createdAt) {}

    public record PrintJob(long id, String sequenceId, long orderId, String orderNo, String kind, String printer,
                           String status, String error, boolean reprint, String userName,
                           LocalDateTime createdAt, LocalDateTime printedAt) {}

    public record BackupRecord(long id, String path, long size, String checksum, String status, String driveStatus,
                               String details, LocalDateTime createdAt) {}

    public record DashboardStats(BigDecimal grossSales, BigDecimal netSales, int orders, BigDecimal averageOrder,
                                 BigDecimal grossProfit, BigDecimal operatingProfit, int lowStock, int outOfStock,
                                 int activeKitchen, int delayedKitchen, int activeShifts, int pendingRefunds,
                                 BigDecimal cashDiscrepancy) {}

    public static DashboardStats emptyStats() {
        return new DashboardStats(Money.ZERO, Money.ZERO, 0, Money.ZERO, Money.ZERO, Money.ZERO, 0, 0, 0, 0, 0, 0, Money.ZERO);
    }
}
