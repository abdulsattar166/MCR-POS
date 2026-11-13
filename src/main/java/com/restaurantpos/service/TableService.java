package com.restaurantpos.service;

import com.restaurantpos.dao.FloorRepository;
import com.restaurantpos.dao.OrderRepository;
import com.restaurantpos.enums.Role;
import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Floor.*;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Validators;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TableService {
    private final FloorRepository floors;
    private final OrderRepository orders;
    private final AuditService audit;

    public TableService(FloorRepository floors, OrderRepository orders, AuditService audit) {
        this.floors = floors;
        this.orders = orders;
        this.audit = audit;
    }

    public List<FloorPlan> floors(boolean activeOnly) { return floors.floors(activeOnly); }
    public List<Section> sections(long floorId) { return floors.sections(floorId); }
    public List<DiningTable> tables(Long floorId, boolean activeOnly) { return floors.tables(floorId, activeOnly); }
    public DiningTable table(long id) { return floors.table(id); }
    public FloorPlan floor(long id) { return floors.floor(id); }

    public long saveFloor(FloorPlan f, Session session) {
        Access.requireManager(session);
        Validators.required(f.name(), "Floor name");
        if (f.width() < 400 || f.height() < 300) throw new AppException("Floor dimensions are too small.");
        long id = floors.saveFloor(f);
        audit.log("FLOOR_SAVED", "floors", id, f.name());
        return id;
    }

    public void deleteFloor(long id, Session session) {
        Access.requireManager(session);
        floors.deleteFloor(id);
        audit.log("FLOOR_DELETED", "floors", id, "");
    }

    public long saveSection(long floorId, String name, Long id, Session session) {
        Access.requireManager(session);
        Validators.required(name, "Section name");
        long saved = floors.saveSection(floorId, name.trim(), id);
        audit.log("SECTION_SAVED", "sections", saved, name);
        return saved;
    }

    public long saveTable(DiningTable t, Session session) {
        Access.requireManager(session);
        Validators.required(t.tableNo(), "Table number");
        if (t.capacity() <= 0) throw new AppException("Table capacity must be greater than zero.");
        if (t.x() < 0 || t.y() < 0) throw new AppException("Table position cannot be negative.");
        long id = floors.saveTable(t);
        audit.log("TABLE_SAVED", "dining_tables", id, t.tableNo());
        return id;
    }

    public void updatePosition(long tableId, double x, double y) { floors.updatePosition(tableId, Math.max(0, x), Math.max(0, y)); }

    public void setStatus(long tableId, TableStatus status, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        floors.setStatus(tableId, status);
        audit.log("TABLE_STATUS", "dining_tables", tableId, status.label());
    }

    public void deleteTable(long tableId, Session session) {
        Access.requireManager(session);
        floors.deleteTable(tableId);
        audit.log("TABLE_DELETED", "dining_tables", tableId, "");
    }

    public void assignTable(long tableId, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        DiningTable t = floors.table(tableId);
        if (t == null || !t.active()) throw new AppException("Table is not available.");
        if (t.status() != TableStatus.AVAILABLE && t.status() != TableStatus.RESERVED && t.status() != TableStatus.CLEANING)
            throw new AppException("Table " + t.tableNo() + " is already occupied.");
        floors.setStatus(tableId, TableStatus.OCCUPIED);
        audit.log("TABLE_ASSIGNED", "dining_tables", tableId, "Occupied");
    }

    public void clearTable(long tableId, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        var open = orders.openOrderForTable(tableId);
        if (open != null) throw new AppException("Table still has open order " + open.orderNo() + ". Complete or cancel it first.");
        floors.setStatus(tableId, TableStatus.AVAILABLE);
        audit.log("TABLE_CLEARED", "dining_tables", tableId, "Marked available");
    }

    public int occupied(Long floorId) { return floors.occupiedCount(floorId); }
    public int totalTables(Long floorId) { return floors.totalActiveTables(floorId); }

    public List<Reservation> reservations(LocalDate date) { return floors.reservations(date); }

    public long reserve(long tableId, String customer, String phone, int party, LocalDateTime at, String notes, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        Validators.required(customer, "Customer name");
        if (party <= 0) throw new AppException("Party size must be greater than zero.");
        if (at.isBefore(LocalDateTime.now().minusMinutes(5))) throw new AppException("Reservation time must be in the future.");
        DiningTable t = floors.table(tableId);
        if (t == null || !t.active()) throw new AppException("Select a valid table.");
        if (t.status() == TableStatus.OCCUPIED) throw new AppException("This table is currently occupied.");
        if (party > t.capacity()) throw new AppException("Party size exceeds the table capacity of " + t.capacity() + ".");
        long id = floors.addReservation(tableId, customer.trim(), phone, party, at, notes);
        audit.log("RESERVATION_CREATED", "reservations", id, t.tableNo() + " — " + customer);
        return id;
    }

    public void updateReservation(long id, String status, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        floors.setReservationStatus(id, status);
        audit.log("RESERVATION_" + status, "reservations", id, "");
    }
}
