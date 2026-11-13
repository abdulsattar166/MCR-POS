package com.restaurantpos.service;

import com.restaurantpos.dao.FinanceRepository;
import com.restaurantpos.enums.CashMoveType;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Sales.Shift;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ShiftService {
    private final FinanceRepository finance;
    private final AuditService audit;

    public ShiftService(FinanceRepository finance, AuditService audit) {
        this.finance = finance;
        this.audit = audit;
    }

    public Shift current(long userId) { return finance.findOpenByUser(userId); }

    public synchronized Shift ensureActiveShiftForCashier(com.restaurantpos.model.SystemModels.User user, Session session) {
        if (user == null || user.role() != com.restaurantpos.enums.Role.CASHIER) {
            return user != null ? current(user.id()) : null;
        }
        Shift existing = current(user.id());
        if (existing != null) {
            return existing;
        }

        com.restaurantpos.model.Sales.ShiftSchedule matched = findActiveSchedule(java.time.LocalTime.now());
        String sName = matched != null ? matched.name() : "General Shift";
        String sStart = matched != null ? matched.startTime() : null;
        String sEnd = matched != null ? matched.endTime() : null;

        Shift shift = finance.openShift(user.id(), BigDecimal.ZERO, sName, sStart, sEnd);
        audit.log("SHIFT_AUTO_STARTED", "shifts", shift.id(),
                "Cashier " + user.fullName() + " automatically started shift: " + sName);
        return shift;
    }

    public synchronized void autoCloseCashierShift(com.restaurantpos.model.SystemModels.User user, Session session, String reason) {
        if (user == null || user.role() != com.restaurantpos.enums.Role.CASHIER) return;
        Shift shift = current(user.id());
        if (shift == null || !shift.isOpen()) return;

        BigDecimal expected = finance.expectedCash(shift);
        String note = (reason == null || reason.isBlank()) ? "Auto-closed on cashier logout" : reason;
        finance.closeShift(shift.id(), expected, note, "SYSTEM");
        audit.log("SHIFT_AUTO_CLOSED", "shifts", shift.id(),
                "Shift #" + shift.id() + " auto-closed for cashier " + user.fullName() + ". Expected: " + Money.plain(expected));
    }

    public Shift requireCurrent(Session session) {
        Shift s = current(session.userId());
        if (s == null) throw new AppException("Open a shift before taking payments or recording cash movements.");
        return s;
    }

    public Shift open(BigDecimal openingCash, Session session) {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER, com.restaurantpos.enums.Role.CASHIER);
        if (openingCash.signum() < 0) throw new AppException("Opening cash cannot be negative.");
        Shift existing = current(session.userId());
        if (existing != null) return existing;

        com.restaurantpos.model.Sales.ShiftSchedule matched = findActiveSchedule(java.time.LocalTime.now());
        String sName = matched != null ? matched.name() : "General Shift";
        String sStart = matched != null ? matched.startTime() : null;
        String sEnd = matched != null ? matched.endTime() : null;

        Shift shift = finance.openShift(session.userId(), openingCash, sName, sStart, sEnd);
        audit.log("SHIFT_OPENED", "shifts", shift.id(), "Shift: " + sName + ", Opening cash " + Money.plain(openingCash));
        return shift;
    }

    public com.restaurantpos.model.Sales.ShiftSchedule findActiveSchedule(java.time.LocalTime time) {
        List<com.restaurantpos.model.Sales.ShiftSchedule> schedules = finance.shiftSchedules(true);
        for (com.restaurantpos.model.Sales.ShiftSchedule s : schedules) {
            if (matchesSchedule(time, s)) return s;
        }
        return null;
    }

    public static boolean matchesSchedule(java.time.LocalTime now, com.restaurantpos.model.Sales.ShiftSchedule schedule) {
        try {
            java.time.LocalTime start = java.time.LocalTime.parse(schedule.startTime());
            java.time.LocalTime end = java.time.LocalTime.parse(schedule.endTime());
            int grace = Math.max(0, schedule.graceMinutes());
            java.time.LocalTime effStart = start.minusMinutes(grace);
            java.time.LocalTime effEnd = end.plusMinutes(grace);

            if (!effStart.isAfter(effEnd)) {
                return !now.isBefore(effStart) && !now.isAfter(effEnd);
            } else {
                return !now.isBefore(effStart) || !now.isAfter(effEnd);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public List<com.restaurantpos.model.Sales.ShiftSchedule> schedules() { return finance.shiftSchedules(false); }

    public long saveSchedule(com.restaurantpos.model.Sales.ShiftSchedule schedule, Session session) {
        Access.requireManager(session);
        validateSchedule(schedule);
        long id = finance.saveShiftSchedule(schedule);
        audit.log("SHIFT_SCHEDULE_SAVED", "shift_schedules", id, schedule.name() + " (" + schedule.startTime() + " - " + schedule.endTime() + ")");
        return id;
    }

    public void deleteSchedule(long id, Session session) {
        Access.requireManager(session);
        finance.deleteShiftSchedule(id);
        audit.log("SHIFT_SCHEDULE_DELETED", "shift_schedules", id, "Deleted schedule #" + id);
    }

    private void validateSchedule(com.restaurantpos.model.Sales.ShiftSchedule s) {
        if (s.name() == null || s.name().isBlank()) throw new AppException("Shift name is required.");
        if (s.startTime() == null || s.startTime().isBlank()) throw new AppException("Start time is required.");
        if (s.endTime() == null || s.endTime().isBlank()) throw new AppException("End time is required.");
        try {
            java.time.LocalTime.parse(s.startTime());
            java.time.LocalTime.parse(s.endTime());
        } catch (Exception e) {
            throw new AppException("Invalid time format. Please use HH:mm (24-hour format, e.g. 08:00, 23:00).");
        }
    }

    public void close(long shiftId, BigDecimal countedCash, String notes, Session session) {
        Shift shift = finance.find(shiftId);
        if (shift == null) throw new AppException("Shift not found.");
        if (shift.userId() != session.userId() && !session.canManage()) {
            throw new AppException("You can only close your own shift.");
        }
        if (countedCash.signum() < 0) throw new AppException("Counted cash cannot be negative.");
        BigDecimal expected = finance.expectedCash(shift);
        BigDecimal difference = Money.sub(countedCash, expected);
        String approval = session.canManage() ? session.username() : "";
        finance.closeShift(shiftId, countedCash, notes, approval);
        audit.log("SHIFT_CLOSED", "shifts", shiftId,
                "Expected " + Money.plain(expected) + ", counted " + Money.plain(countedCash) + ", difference " + Money.plain(difference));
        if (difference.signum() != 0) {
            audit.notify("CASH_DISCREPANCY", "Cash discrepancy",
                    "Shift #" + shiftId + " closed with a difference of " + Money.fmt(difference) + ".", "WARNING");
        }
    }

    public BigDecimal expected(long shiftId) {
        return finance.expectedCash(finance.find(shiftId));
    }

    public void cashMovement(CashMoveType type, BigDecimal amount, String reason, Session session) {
        Shift shift = requireCurrent(session);
        if (amount.signum() <= 0) throw new AppException("Amount must be greater than zero.");
        if (reason == null || reason.isBlank()) throw new AppException("A reason is required for cash movements.");
        finance.addCashMovement(shift.id(), type, amount, reason.trim(), session.userId());
        audit.log("CASH_" + type.name(), "shifts", shift.id(), Money.plain(amount) + " — " + reason);
    }

    public List<Shift> list(LocalDate from, LocalDate to, Long userId) { return finance.shifts(from, to, userId); }
    public List<com.restaurantpos.model.Sales.CashMovement> movements(long shiftId) { return finance.cashMovements(shiftId); }
    public BigDecimal sales(long shiftId) { return finance.shiftSales(shiftId); }
    public int orderCount(long shiftId) { return finance.shiftOrderCount(shiftId); }
    public BigDecimal cashSales(long shiftId) { return finance.cashSales(shiftId); }
}
