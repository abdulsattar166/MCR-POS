package com.restaurantpos.model;

import com.restaurantpos.enums.TableStatus;

import java.time.LocalDateTime;

public final class Floor {
    private Floor() {}

    public record FloorPlan(long id, String name, String description, int width, int height, boolean active, int sortOrder) {
        @Override public String toString() { return name; }
    }

    public record Section(long id, long floorId, String name) {
        @Override public String toString() { return name; }
    }

    public record DiningTable(long id, long floorId, Long sectionId, String tableNo, String shape, int capacity,
                              double x, double y, double width, double height, double rotation,
                              TableStatus status, boolean active) {
        public String sectionName() { return sectionId == null ? "—" : String.valueOf(sectionId); }
        @Override public String toString() { return "Table " + tableNo; }
    }

    public record Reservation(long id, long tableId, String tableNo, String customerName, String phone, int partySize,
                              LocalDateTime reservedAt, String notes, String status) {
    }
}
