package com.restaurantpos.service;

import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.InventoryRepository;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Inventory.*;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class PurchaseService {
    private final InventoryRepository inventory;
    private final AuditService audit;
    private final Database db = Database.get();

    public PurchaseService(InventoryRepository inventory, AuditService audit) {
        this.inventory = inventory;
        this.audit = audit;
    }

    public List<Supplier> suppliers(boolean activeOnly) { return inventory.suppliers(activeOnly); }
    public List<PurchaseOrder> orders() { return inventory.purchaseOrders(); }
    public List<PurchaseLine> lines(long poId) { return inventory.purchaseLines(poId); }
    public BigDecimal outstanding(long supplierId) { return inventory.supplierOutstanding(supplierId); }
    public List<Object[]> payments(Long supplierId) { return inventory.supplierPayments(supplierId); }

    public long saveSupplier(Supplier s, Session session) {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER, com.restaurantpos.enums.Role.INVENTORY);
        Validators.required(s.name(), "Supplier name");
        Validators.phone(s.phone(), "Supplier phone", true);
        Validators.email(s.email(), "Supplier email");
        long id = inventory.saveSupplier(s);
        audit.log("SUPPLIER_CREATED", "suppliers", id, s.name());
        return id;
    }

    public long createPurchaseOrder(long supplierId, LocalDate expectedDate, BigDecimal tax,
                                    String notes, Map<Long, BigDecimal[]> lines, Session session) {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER, com.restaurantpos.enums.Role.INVENTORY);
        if (lines == null || lines.isEmpty()) throw new AppException("Add at least one line to the purchase order.");
        BigDecimal subtotal = Money.ZERO;
        for (Map.Entry<Long, BigDecimal[]> e : lines.entrySet()) {
            BigDecimal qty = e.getValue()[0];
            BigDecimal cost = e.getValue()[1];
            if (qty == null || qty.signum() <= 0) throw new AppException("Purchase quantity must be greater than zero.");
            if (cost == null || cost.signum() < 0) throw new AppException("Unit cost cannot be negative.");
            subtotal = subtotal.add(Money.mul(qty, cost));
        }
        tax = tax == null ? Money.ZERO : tax;
        BigDecimal total = subtotal.add(tax);
        String poNo = nextPoNo();
        long id = inventory.createPurchaseOrder(poNo, supplierId, "DRAFT", LocalDate.now(), expectedDate,
                subtotal, tax, total, notes == null ? "" : notes, session.username(), lines);
        audit.log("PO_CREATED", "purchase_orders", id, poNo + " created (" + Money.fmt(total) + ")");
        return id;
    }

    public void updatePurchaseOrder(long poId, long supplierId, LocalDate expectedDate, BigDecimal tax,
                                    String notes, Map<Long, BigDecimal[]> lines, Session session) {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER, com.restaurantpos.enums.Role.INVENTORY);
        PurchaseOrder po = inventory.purchaseOrders().stream().filter(p -> p.id() == poId).findFirst()
                .orElseThrow(() -> new AppException("Purchase order not found."));
        if (!"DRAFT".equals(po.status())) throw new AppException("Only draft purchase orders can be modified.");
        if (lines == null || lines.isEmpty()) throw new AppException("Add at least one line to the purchase order.");
        BigDecimal subtotal = Money.ZERO;
        for (Map.Entry<Long, BigDecimal[]> e : lines.entrySet()) {
            BigDecimal qty = e.getValue()[0];
            BigDecimal cost = e.getValue()[1];
            if (qty == null || qty.signum() <= 0) throw new AppException("Purchase quantity must be greater than zero.");
            if (cost == null || cost.signum() < 0) throw new AppException("Unit cost cannot be negative.");
            subtotal = subtotal.add(Money.mul(qty, cost));
        }
        tax = tax == null ? Money.ZERO : tax;
        BigDecimal total = subtotal.add(tax);
        inventory.updatePurchaseOrder(poId, supplierId, expectedDate, subtotal, tax, total, notes == null ? "" : notes, lines);
        audit.log("PO_UPDATED", "purchase_orders", poId, po.poNo() + " updated (" + Money.fmt(total) + ")");
    }

    public void savePurchaseOrder(PurchaseOrder po, List<PurchaseLine> lines, Session session) {
        Access.requireManager(session);
        if (po.id() == 0) {
            long id = inventory.savePurchaseOrder(po);
            for (PurchaseLine l : lines) {
                inventory.savePurchaseLine(id, l);
            }
            audit.log("PO_CREATED", "purchase_orders", id, po.poNo() + " created");
        }
    }

    public void approve(long poId, Session session) {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER);
        inventory.approvePurchase(poId);
        audit.log("PURCHASE_APPROVED", "purchase_orders", poId, "");
    }

    public void receive(long poId, String notes, Session session) {
        Access.require(session, com.restaurantpos.enums.Role.OWNER, com.restaurantpos.enums.Role.MANAGER, com.restaurantpos.enums.Role.INVENTORY);
        PurchaseOrder po = inventory.purchaseOrders().stream().filter(p -> p.id() == poId).findFirst()
                .orElseThrow(() -> new AppException("Purchase order not found."));
        if ("DRAFT".equals(po.status())) throw new AppException("Approve this purchase order before receiving stock.");
        if ("RECEIVED".equals(po.status())) throw new AppException("This purchase order has already been received.");
        inventory.receivePurchase(poId, session.username(), notes == null ? "" : notes);
        audit.log("PURCHASE_RECEIVED", "purchase_orders", poId, po.poNo());
        audit.notify("PURCHASE_RECEIVED", "Stock received", "Purchase " + po.poNo() + " received into inventory.", "SUCCESS");
    }

    public void addPayment(long supplierId, Long poId, BigDecimal amount, String method, String reference, Session session) {
        Access.requireManager(session);
        if (amount.signum() <= 0) throw new AppException("Supplier payment must be greater than zero.");
        inventory.addSupplierPayment(supplierId, poId, amount, method, reference, "");
        audit.log("SUPPLIER_PAYMENT", "suppliers", supplierId, Money.plain(amount) + " via " + method);
    }

    public boolean hasActiveOrders(long supplierId) {
        return inventory.hasActiveOrders(supplierId);
    }

    public int activeOrderCount(long supplierId) {
        return inventory.activeOrderCount(supplierId);
    }

    public boolean hasPayments(long supplierId) {
        return inventory.hasPayments(supplierId);
    }

    public void deleteSupplier(long supplierId, Session session) {
        Access.requireManager(session);
        if (inventory.hasActiveOrders(supplierId)) {
            throw new AppException("Cannot delete supplier with active purchase orders. Cancel or complete all orders first.");
        }
        inventory.deleteSupplier(supplierId);
        audit.log("SUPPLIER_DELETED", "suppliers", supplierId, "Supplier deleted");
    }

    public void deleteOrder(long poId, Session session) {
        Access.requireManager(session);
        PurchaseOrder po = inventory.purchaseOrders().stream().filter(p -> p.id() == poId).findFirst()
                .orElseThrow(() -> new AppException("Purchase order not found."));
        if (!"DRAFT".equals(po.status())) {
            throw new AppException("Only draft purchase orders can be deleted. Cancel this order instead.");
        }
        inventory.deletePurchaseOrder(poId);
        audit.log("PURCHASE_DELETED", "purchase_orders", poId, po.poNo() + " deleted");
    }

    public void deletePayment(long paymentId, Session session) {
        Access.requireManager(session);
        inventory.deleteSupplierPayment(paymentId);
        audit.log("SUPPLIER_PAYMENT_DELETED", "suppliers", paymentId, "Payment deleted from record");
    }

    private String nextPoNo() {
        int seq = db.queryOne("SELECT COALESCE(MAX(CAST(SUBSTR(po_no, 5) AS INTEGER)), 0) FROM purchase_orders", rs -> rs.getInt(1));
        while (true) {
            String candidate = String.format("PO-%05d", ++seq);
            int exists = db.queryOne("SELECT COUNT(*) FROM purchase_orders WHERE po_no=?", rs -> rs.getInt(1), candidate);
            if (exists == 0) return candidate;
            seq++;
        }
    }
}
