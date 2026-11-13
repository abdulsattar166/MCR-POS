package com.restaurantpos.dao;

import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.model.Floor.*;

import java.time.LocalDateTime;
import java.util.List;

public final class FloorRepository {
    private final Database db = Database.get();

    private FloorPlan mapFloor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FloorPlan(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getInt("width"), rs.getInt("height"), Rows.bool(rs, "active"), rs.getInt("sort_order"));
    }

    private DiningTable mapTable(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DiningTable(rs.getLong("id"), rs.getLong("floor_id"), Rows.longOrNull(rs, "section_id"),
                rs.getString("table_no"), rs.getString("shape"), rs.getInt("capacity"), rs.getDouble("x"),
                rs.getDouble("y"), rs.getDouble("width"), rs.getDouble("height"), rs.getDouble("rotation"),
                TableStatus.valueOf(rs.getString("status")), Rows.bool(rs, "active"));
    }

    public List<FloorPlan> floors(boolean activeOnly) {
        String sql = "SELECT * FROM floors" + (activeOnly ? " WHERE active=1" : "") + " ORDER BY sort_order, name";
        return db.query(sql, this::mapFloor);
    }

    public FloorPlan floor(long id) {
        return db.queryOne("SELECT * FROM floors WHERE id=?", this::mapFloor, id);
    }

    public long saveFloor(FloorPlan f) {
        if (f.id() == 0) {
            return db.insert("INSERT INTO floors(name,description,width,height,active,sort_order) VALUES(?,?,?,?,?,?)",
                    f.name(), f.description(), f.width(), f.height(), f.active(), f.sortOrder());
        }
        db.update("UPDATE floors SET name=?, description=?, width=?, height=?, active=?, sort_order=? WHERE id=?",
                f.name(), f.description(), f.width(), f.height(), f.active(), f.sortOrder(), f.id());
        return f.id();
    }

    public void deleteFloor(long id) {
        long tables = db.scalarLong("SELECT COUNT(*) FROM dining_tables WHERE floor_id=?", id);
        if (tables > 0) throw new com.restaurantpos.exception.AppException("Move or delete tables before deleting this floor.");
        db.update("DELETE FROM sections WHERE floor_id=?", id);
        db.update("DELETE FROM floors WHERE id=?", id);
    }

    public List<Section> sections(long floorId) {
        return db.query("SELECT * FROM sections WHERE floor_id=? ORDER BY name",
                rs -> new Section(rs.getLong("id"), rs.getLong("floor_id"), rs.getString("name")), floorId);
    }

    public long saveSection(long floorId, String name, Long existingId) {
        if (existingId == null) {
            return db.insert("INSERT INTO sections(floor_id,name) VALUES(?,?)", floorId, name);
        }
        db.update("UPDATE sections SET name=? WHERE id=?", name, existingId);
        return existingId;
    }

    public void deleteSection(long id) {
        long tables = db.scalarLong("SELECT COUNT(*) FROM dining_tables WHERE section_id=?", id);
        if (tables > 0) throw new com.restaurantpos.exception.AppException("This section still contains tables.");
        db.update("DELETE FROM sections WHERE id=?", id);
    }

    public List<DiningTable> tables(Long floorId, boolean activeOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM dining_tables WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (floorId != null) { sql.append(" AND floor_id=?"); args.add(floorId); }
        if (activeOnly) sql.append(" AND active=1");
        sql.append(" ORDER BY table_no");
        return db.query(sql.toString(), this::mapTable, args.toArray());
    }

    public DiningTable table(long id) {
        return db.queryOne("SELECT * FROM dining_tables WHERE id=?", this::mapTable, id);
    }

    public DiningTable tableByNo(String tableNo) {
        return db.queryOne("SELECT * FROM dining_tables WHERE table_no=? COLLATE NOCASE", this::mapTable, tableNo);
    }

    public long saveTable(DiningTable t) {
        if (t.id() == 0) {
            return db.insert("""
                    INSERT INTO dining_tables(floor_id,section_id,table_no,shape,capacity,x,y,width,height,rotation,status,active)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, t.floorId(), t.sectionId(), t.tableNo(), t.shape(), t.capacity(), t.x(), t.y(), t.width(),
                    t.height(), t.rotation(), t.status().name(), t.active());
        }
        db.update("""
                UPDATE dining_tables SET floor_id=?, section_id=?, table_no=?, shape=?, capacity=?, x=?, y=?,
                width=?, height=?, rotation=?, status=?, active=? WHERE id=?
                """, t.floorId(), t.sectionId(), t.tableNo(), t.shape(), t.capacity(), t.x(), t.y(), t.width(),
                t.height(), t.rotation(), t.status().name(), t.active(), t.id());
        return t.id();
    }

    public void updatePosition(long id, double x, double y) {
        db.update("UPDATE dining_tables SET x=?, y=? WHERE id=?", x, y, id);
    }

    public void setStatus(long id, TableStatus status) {
        db.update("UPDATE dining_tables SET status=? WHERE id=?", status.name(), id);
    }

    public void setActive(long id, boolean active) {
        db.update("UPDATE dining_tables SET active=? WHERE id=?", active, id);
    }

    public void deleteTable(long id) {
        long openOrders = db.scalarLong("SELECT COUNT(*) FROM orders WHERE table_id=? AND status NOT IN ('COMPLETED','CANCELLED')", id);
        if (openOrders > 0) throw new com.restaurantpos.exception.AppException("This table has active orders.");
        long history = db.scalarLong("SELECT COUNT(*) FROM orders WHERE table_id=?", id);
        if (history > 0) {
            db.update("UPDATE dining_tables SET active=0, status='OUT_OF_SERVICE' WHERE id=?", id);
            return;
        }
        db.update("DELETE FROM reservations WHERE table_id=?", id);
        db.update("DELETE FROM dining_tables WHERE id=?", id);
    }

    public int occupiedCount(Long floorId) {
        if (floorId == null) return (int) db.scalarLong("SELECT COUNT(*) FROM dining_tables WHERE active=1 AND status='OCCUPIED'");
        return (int) db.scalarLong("SELECT COUNT(*) FROM dining_tables WHERE active=1 AND status='OCCUPIED' AND floor_id=?", floorId);
    }

    public int totalActiveTables(Long floorId) {
        if (floorId == null) return (int) db.scalarLong("SELECT COUNT(*) FROM dining_tables WHERE active=1");
        return (int) db.scalarLong("SELECT COUNT(*) FROM dining_tables WHERE active=1 AND floor_id=?", floorId);
    }

    // ---------- reservations ----------
    public List<Reservation> reservations(java.time.LocalDate date) {
        return db.query("""
                SELECT r.*, t.table_no FROM reservations r JOIN dining_tables t ON t.id=r.table_id
                WHERE date(r.reserved_at)=? ORDER BY r.reserved_at
                """, rs -> new Reservation(rs.getLong("id"), rs.getLong("table_id"), rs.getString("table_no"),
                rs.getString("customer_name"), rs.getString("phone"), rs.getInt("party_size"),
                Rows.dt(rs, "reserved_at"), rs.getString("notes"), rs.getString("status")), date.toString());
    }

    public long addReservation(long tableId, String customer, String phone, int party, LocalDateTime at, String notes) {
        long id = db.insert("""
                INSERT INTO reservations(table_id,customer_name,phone,party_size,reserved_at,notes,status,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, tableId, customer, phone, party, at, notes, "BOOKED", LocalDateTime.now());
        setStatus(tableId, TableStatus.RESERVED);
        return id;
    }

    public void setReservationStatus(long id, String status) {
        String tableId = db.queryOne("SELECT table_id FROM reservations WHERE id=?", rs -> rs.getString(1), id);
        db.update("UPDATE reservations SET status=? WHERE id=?", status, id);
        if ("CANCELLED".equals(status) || "SEATED".equals(status)) {
            if (tableId != null) setStatus(Long.parseLong(tableId), "SEATED".equals(status) ? TableStatus.OCCUPIED : TableStatus.AVAILABLE);
        }
    }
}
