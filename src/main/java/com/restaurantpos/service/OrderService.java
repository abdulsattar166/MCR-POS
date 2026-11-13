package com.restaurantpos.service;

import com.restaurantpos.dao.*;
import com.restaurantpos.enums.*;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Sales.*;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class OrderService {
    private final OrderRepository orders;
    private final FloorRepository floors;
    private final InventoryRepository inventory;
    private final SettingsService settings;
    private final AuditService audit;
    private final Database db = Database.get();

    public OrderService(OrderRepository orders, FloorRepository floors, InventoryRepository inventory,
                        SettingsService settings, AuditService audit) {
        this.orders = orders;
        this.floors = floors;
        this.inventory = inventory;
        this.settings = settings;
        this.audit = audit;
    }

    public synchronized String nextOrderNo() {
        Long maxSeq = db.scalarLong("""
                SELECT MAX(CAST(SUBSTR(order_no, 5) AS INTEGER))
                FROM orders
                WHERE order_no LIKE 'MCR-%'
                """);
        long seq = (maxSeq == null || maxSeq < 1) ? 1 : (maxSeq + 1);
        while (true) {
            String candidate = String.format("MCR-%04d", seq);
            if (orders.findByNo(candidate) == null) return candidate;
            seq++;
        }
    }

    public Cart.Totals quote(Cart cart) {
        return cart.totals(settings.taxRate(), settings.serviceRate(),
                cart.type == OrderType.DELIVERY ? cart.deliveryCharge : Money.ZERO, cart.orderDiscount);
    }

    public long createOrder(Cart cart, boolean held, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        validate(cart, session);
        Cart.Totals totals = quote(cart);
        long cashierId = session.userId();
        String orderNo = nextOrderNo();
        String token = cart.type == OrderType.DINE_IN ? null
                : (orderNo.startsWith("MCR-") ? orderNo.substring(4) : orderNo.substring(orderNo.lastIndexOf('-') + 1));
        Long tableId = cart.tableId;

        return db.tx(c -> {
            if (tableId != null) {
                var table = floors.table(tableId);
                if (table == null || !table.active()) throw new AppException("The selected table is not active.");
                if (orders.hasOtherOpenOrderForTable(tableId, null)) {
                    throw new AppException("Table " + table.tableNo() + " already has open order.");
                }
            }
            if (cart.waiterId != null) {
                long count = db.scalarLong("SELECT COUNT(*) FROM users WHERE id=? AND active=1", cart.waiterId);
                if (count == 0) {
                    throw new AppException("The selected waiter is no longer active. Please choose another waiter or continue without a waiter.");
                }
            }
            long orderId = orders.create(new OrderRepository.NewOrder(orderNo, cart.type, tableId, cart.waiterId, cart.customerId,
                    cashierId, cart.shiftId, cart.guestCount, trim(cart.customerName), trim(cart.customerPhone),
                    trim(cart.customerAddress), trim(cart.notes), token, held, Money.nz(cart.deliveryCharge)));
            for (Cart.CartLine l : cart.lines) {
                orders.addItem(new OrderRepository.NewItem(orderId, l.productId == 0 ? null : l.productId,
                        l.variantId, l.name, l.sku, l.unitPrice, l.cost, l.qty, l.detail, l.instructions,
                        l.discount, l.net()));
            }
            OrderStatus status = OrderStatus.DRAFT;
            orders.updateTotals(orderId, totals.subtotal, totals.discount, totals.tax, totals.service, totals.delivery,
                    totals.total, status, PaymentStatus.UNPAID);
            if (cart.type == OrderType.DINE_IN && tableId != null) {
                floors.setStatus(tableId, TableStatus.OCCUPIED);
            }
            orders.addEvent(orderId, cashierId, held ? "HELD" : "CREATED",
                    "Order " + orderNo + " created with " + cart.lines.size() + " item(s), total " + Money.fmt(totals.total));
            audit.log(held ? "ORDER_HELD" : "ORDER_CREATED", "orders", orderId,
                    orderNo + " / " + cart.type.label() + " / " + Money.plain(totals.total));
            return orderId;
        });
    }

    public void validate(Cart cart, Session session) {
        if (cart.lines.isEmpty()) throw new AppException("Add at least one item to the order.");
        if (cart.tableId != null) {
            var table = floors.table(cart.tableId);
            if (table == null || !table.active()) throw new AppException("The selected table is not active.");
            Order existing = orders.openOrderForTable(cart.tableId);
            if (existing != null && (cart.resumeOrderId == null || existing.id() != cart.resumeOrderId)) {
                throw new AppException("Table " + table.tableNo() + " already has open order " + existing.orderNo() + ".");
            }
            if (table.status() == TableStatus.OUT_OF_SERVICE) throw new AppException("This table is out of service.");
        }
        if (cart.waiterId != null) {
            long count = db.scalarLong("SELECT COUNT(*) FROM users WHERE id=? AND active=1", cart.waiterId);
            if (count == 0) throw new AppException("The selected waiter is no longer active.");
        }
        if (cart.type == OrderType.DELIVERY) {
            Validators.required(cart.customerName, "Customer name");
            Validators.phone(cart.customerPhone, "Customer phone", false);
            Validators.required(cart.customerAddress, "Delivery address");
            if (cart.deliveryCharge.signum() < 0) throw new AppException("Delivery charge cannot be negative.");
        }
        for (Cart.CartLine l : cart.lines) {
            if (l.qty <= 0) throw new AppException("Item quantity must be greater than zero.");
            if (l.discount.signum() < 0 || l.discount.compareTo(l.gross()) > 0) {
                throw new AppException("Item discount is invalid for " + l.name + ".");
            }
        }
        if (cart.orderDiscount.signum() < 0) throw new AppException("Order discount cannot be negative.");
        Cart.Totals t = quote(cart);
        if (cart.orderDiscount.compareTo(t.subtotal) > 0) throw new AppException("Order discount exceeds the subtotal.");
        if (!session.canManage() && t.subtotal.signum() > 0) {
            BigDecimal max = Money.percent(t.subtotal, settings.discountMaxPercent());
            if (t.discount.compareTo(max) > 0) {
                throw new AppException("Discount exceeds the cashier limit of " + settings.discountMaxPercent() + "%. Ask a manager to approve.");
            }
        }
    }

    public Order find(long id) { return orders.find(id); }
    public Order findByNo(String no) { return orders.findByNo(no); }
    public Order openOrderForTable(long tableId) { return orders.openOrderForTable(tableId); }
    public List<Order> openOrders() { return orders.openOrders(); }
    public List<OrderItem> items(long id) { return orders.items(id); }
    public List<Order> list(java.time.LocalDate from, java.time.LocalDate to, OrderType type, OrderStatus status,
                            Long cashierId, String payment, String search) {
        return orders.list(from, to, type, status, cashierId, payment, search, 1000);
    }

    public List<Order> held(Long cashierId) { return orders.heldOrders(cashierId); }
    public List<Order> draftOrders(String search) { return orders.draftOrders(search); }
    public List<Order> completeOrders(java.time.LocalDate from, java.time.LocalDate to, String search) {
        return orders.completeOrders(from, to, search, 500);
    }
    public OrderRepository.OrderStats summaryStats(java.time.LocalDate from, java.time.LocalDate to) {
        return orders.summaryStats(from, to);
    }
    public java.util.Map<Long, String> itemSummaries(List<Long> orderIds) {
        return orders.itemSummaries(orderIds);
    }
    public java.util.Map<Long, String> paymentMethods(List<Long> orderIds) {
        return orders.paymentMethods(orderIds);
    }

    public Cart resume(long orderId, Session session) {
        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (!order.held()) throw new AppException("This order is not on hold.");
        Cart cart = Cart.fromOrder(order, orders.items(orderId));
        cart.shiftId = currentShiftId(session.userId());
        db.txRun(c -> {
            orders.close(orderId, OrderStatus.CANCELLED, "Resumed into a new order", order.paymentStatus());
            orders.addEvent(orderId, session.userId(), "RESUMED", "Held order resumed by " + session.username());
            if (order.tableId() != null && !orders.hasOtherOpenOrderForTable(order.tableId(), orderId)) {
                floors.setStatus(order.tableId(), TableStatus.AVAILABLE);
            }
            audit.log("ORDER_RESUMED", "orders", orderId, order.orderNo() + " resumed");
        });
        return cart;
    }

    public void cancel(long orderId, String reason, Session session) {
        if (reason == null || reason.isBlank()) throw new AppException("A cancellation reason is required.");
        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (order.status() == OrderStatus.CANCELLED) throw new AppException("Order is already cancelled.");
        if (order.status() == OrderStatus.COMPLETED && !session.canManage()) {
            throw new AppException("A completed order can only be cancelled by an owner or manager.");
        }
        db.txRun(c -> {
            inventory.reverseConsumption(orderId, session.username(), "Cancelled order: " + reason);
            orders.close(orderId, OrderStatus.CANCELLED, reason, order.paymentStatus());
            orders.addEvent(orderId, session.userId(), "CANCELLED", reason);
            if (order.tableId() != null && !orders.hasOtherOpenOrderForTable(order.tableId(), orderId)) {
                floors.setStatus(order.tableId(), TableStatus.AVAILABLE);
            }
            audit.log("ORDER_CANCELLED", "orders", orderId, order.orderNo() + " — " + reason);
            audit.notify("CANCELLATION", "Order cancelled", "Order " + order.orderNo() + " was cancelled: " + reason, "WARNING");
        });
    }

    public void complete(long orderId, Session session) {
        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (order.status() == OrderStatus.CANCELLED) throw new AppException("A cancelled order cannot be completed.");
        if (order.balance().signum() > 0) {
            throw new AppException("Order " + order.orderNo() + " still has an unpaid balance of "
                    + Money.fmt(order.balance()) + ". Collect payment or transfer it to a customer ledger first.");
        }
        orders.updateStatus(orderId, OrderStatus.COMPLETED, KitchenStatus.COMPLETED, order.paymentStatus());
        if (order.tableId() != null && !orders.hasOtherOpenOrderForTable(order.tableId(), orderId)) {
            floors.setStatus(order.tableId(), TableStatus.AVAILABLE);
        }
        orders.addEvent(orderId, session.userId(), "COMPLETED", "Order completed");
        audit.log("ORDER_COMPLETED", "orders", orderId, order.orderNo());
    }

    public void reopen(long orderId, Session session) {
        Access.requireManager(session);
        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (order.status() != OrderStatus.COMPLETED && order.status() != OrderStatus.SERVED) {
            throw new AppException("Only completed or served orders can be reopened.");
        }
        orders.updateStatus(orderId, OrderStatus.CONFIRMED, order.kitchenStatus(), order.paymentStatus());
        orders.addEvent(orderId, session.userId(), "REOPENED", "Order reopened by " + session.username());
        audit.log("ORDER_REOPENED", "orders", orderId, order.orderNo());
    }

    public void transferTable(long orderId, long newTableId, Session session) {
        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (order.status() == OrderStatus.COMPLETED || order.status() == OrderStatus.CANCELLED) {
            throw new AppException("Completed or cancelled orders cannot be transferred.");
        }
        if (order.tableId() == null) throw new AppException("Only dine-in orders can be transferred.");
        var target = floors.table(newTableId);
        if (target == null || !target.active()) throw new AppException("Target table is not available.");
        if (target.status() == TableStatus.OCCUPIED) throw new AppException("Target table is occupied.");
        db.txRun(c -> {
            Long oldTable = order.tableId();
            orders.setTable(orderId, newTableId);
            floors.setStatus(newTableId, TableStatus.OCCUPIED);
            if (oldTable != null && !orders.hasOtherOpenOrderForTable(oldTable, orderId)) {
                floors.setStatus(oldTable, TableStatus.AVAILABLE);
            }
            orders.addEvent(orderId, session.userId(), "TABLE_TRANSFER", "Moved to table " + target.tableNo());
            audit.log("TABLE_TRANSFER", "orders", orderId, order.orderNo() + " moved to " + target.tableNo());
        });
    }

    public void merge(long sourceOrderId, long targetOrderId, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        if (sourceOrderId == targetOrderId) throw new AppException("Select two different orders to merge.");
        Order source = orders.find(sourceOrderId);
        Order target = orders.find(targetOrderId);
        if (source == null || target == null) throw new AppException("Order not found.");
        if (!source.isOpen() || !target.isOpen()) throw new AppException("Only open orders can be merged.");
        if (Money.nz(source.paidTotal()).signum() > 0 || Money.nz(target.paidTotal()).signum() > 0) {
            throw new AppException("Orders with payments cannot be merged. Use table transfer or split payment.");
        }
        db.txRun(c -> {
            orders.moveItems(sourceOrderId, targetOrderId);
            List<OrderItem> items = orders.items(targetOrderId);
            BigDecimal gross = Money.ZERO, discount = Money.ZERO;
            for (OrderItem i : items) {
                gross = gross.add(i.gross());
                discount = discount.add(i.discount());
            }
            Cart merged = new Cart();
            merged.type = target.type();
            merged.deliveryCharge = Money.nz(target.deliveryCharge());
            merged.orderDiscount = Money.maxZero(Money.sub(Money.nz(target.discountTotal()), discount));
            for (OrderItem i : items) {
                Cart.CartLine l = new Cart.CartLine();
                l.productId = i.productId() == null ? 0 : i.productId();
                l.name = i.name(); l.sku = i.sku(); l.unitPrice = i.unitPrice(); l.cost = i.cost();
                l.qty = i.qty(); l.discount = i.discount(); l.detail = i.addons(); l.taxRate = BigDecimal.ZERO;
                merged.lines.add(l);
            }
            Cart.Totals t = quote(merged);
            orders.updateTotalsOnly(targetOrderId, t.subtotal, t.discount, t.tax, t.service, t.delivery, t.total);
            orders.close(sourceOrderId, OrderStatus.CANCELLED, "Merged into " + target.orderNo(), PaymentStatus.UNPAID);
            orders.addEvent(sourceOrderId, session.userId(), "MERGED", "Merged into " + target.orderNo());
            orders.addEvent(targetOrderId, session.userId(), "MERGED", "Merged from " + source.orderNo());
            if (source.tableId() != null && !orders.hasOtherOpenOrderForTable(source.tableId(), sourceOrderId)) {
                floors.setStatus(source.tableId(), TableStatus.AVAILABLE);
            }
            audit.log("ORDER_MERGED", "orders", targetOrderId, source.orderNo() + " merged into " + target.orderNo());
        });
    }

    public long duplicate(long orderId, Session session) {
        Order source = orders.find(orderId);
        if (source == null) throw new AppException("Order not found.");
        Cart cart = Cart.fromOrder(source, orders.items(orderId));
        cart.tableId = null;
        cart.tableNo = null;
        cart.type = source.type() == OrderType.DINE_IN ? OrderType.TAKEAWAY : source.type();
        cart.orderDiscount = Money.ZERO;
        cart.shiftId = currentShiftId(session.userId());
        long newId = createOrder(cart, true, session);
        orders.addEvent(newId, session.userId(), "DUPLICATED", "Duplicated from " + source.orderNo());
        audit.log("ORDER_DUPLICATED", "orders", newId, "Duplicated from " + source.orderNo());
        return newId;
    }

    public record DraftItemEdit(
            Long orderItemId,
            Long productId,
            Long variantId,
            String name,
            String sku,
            BigDecimal unitPrice,
            BigDecimal cost,
            int qty,
            String addons,
            String instructions,
            BigDecimal discount
    ) {
        public static DraftItemEdit fromOrderItem(OrderItem item) {
            return new DraftItemEdit(
                    item.id(), item.productId(), item.variantId(), item.name(), item.sku(),
                    Money.nz(item.unitPrice()), Money.nz(item.cost()), item.qty(),
                    item.addons(), item.instructions(), Money.nz(item.discount())
            );
        }

        public BigDecimal lineTotal() {
            BigDecimal gross = Money.mul(Money.nz(unitPrice), BigDecimal.valueOf(Math.max(0, qty)));
            return Money.maxZero(Money.sub(gross, Money.nz(discount)));
        }
    }

    public record DraftChangeLine(String changeType, String name, int deltaQty, String addons, String instructions) {}

    public record DraftSaveResult(Order updatedOrder, List<OrderItem> updatedItems, List<DraftChangeLine> kitchenChanges) {}

    public Cart.Totals quoteDraftPreview(Order order, List<DraftItemEdit> editedItems) {
        if (order == null || editedItems == null || editedItems.isEmpty()) {
            return new Cart.Totals();
        }
        List<OrderItem> existingItems = orders.items(order.id());
        BigDecimal existingLineDiscounts = existingItems.stream()
                .map(i -> Money.nz(i.discount()))
                .reduce(Money.ZERO, Money::add);
        BigDecimal orderLevelDiscount = Money.maxZero(Money.sub(Money.nz(order.discountTotal()), existingLineDiscounts));

        Cart temp = new Cart();
        temp.type = order.type();
        temp.deliveryCharge = order.type() == OrderType.DELIVERY ? Money.nz(order.deliveryCharge()) : Money.ZERO;
        BigDecimal newSubtotal = Money.ZERO;
        for (DraftItemEdit e : editedItems) {
            if (e.qty() <= 0) continue;
            Cart.CartLine l = new Cart.CartLine();
            l.productId = e.productId() == null ? 0L : e.productId();
            l.variantId = e.variantId();
            l.name = e.name();
            l.sku = e.sku();
            l.unitPrice = Money.nz(e.unitPrice());
            l.cost = Money.nz(e.cost());
            l.qty = e.qty();
            l.discount = Money.nz(e.discount());
            l.detail = e.addons();
            l.instructions = e.instructions() == null ? "" : e.instructions();
            l.taxRate = BigDecimal.ZERO;
            temp.lines.add(l);
            newSubtotal = newSubtotal.add(l.gross());
        }
        // Cap preserved order-level discount so it never exceeds new subtotal
        temp.orderDiscount = orderLevelDiscount.min(newSubtotal);
        return quote(temp);
    }

    public static String draftSnapshotToken(Order order, List<OrderItem> items) {
        if (order == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(order.id()).append('|')
          .append(order.status()).append('|')
          .append(order.paymentStatus()).append('|')
          .append(Money.plain(order.total())).append('|')
          .append(Money.plain(order.paidTotal())).append('|');
        if (items != null) {
            for (OrderItem i : items) {
                sb.append(i.id()).append(':')
                  .append(i.qty()).append(':')
                  .append(Money.plain(i.lineTotal())).append(';');
            }
        }
        return sb.toString();
    }

    public DraftSaveResult saveDraftEdits(long orderId, String expectedSnapshotToken,
                                          List<DraftItemEdit> editedItems, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        if (editedItems == null) throw new AppException("Draft item list cannot be null.");

        return db.tx(c -> {
            Order order = orders.find(orderId);
            if (order == null) throw new AppException("Order not found.");
            if (order.status() == OrderStatus.COMPLETED || order.status() == OrderStatus.CANCELLED
                    || order.paymentStatus() == PaymentStatus.PAID) {
                throw new AppException("Completed, paid, or cancelled orders cannot be edited as drafts.");
            }
            List<OrderItem> existing = orders.items(orderId);
            String currentToken = draftSnapshotToken(order, existing);
            if (expectedSnapshotToken != null && !expectedSnapshotToken.equals(currentToken)) {
                throw new AppException("This draft order was updated elsewhere. Refresh to load the latest items before saving.");
            }
            java.util.Map<Long, OrderItem> existingById = new java.util.LinkedHashMap<>();
            for (OrderItem item : existing) {
                existingById.put(item.id(), item);
            }

            java.util.Set<Long> keptIds = new java.util.HashSet<>();
            List<DraftChangeLine> changes = new ArrayList<>();

            // 1. Process kept/updated existing items and newly added items
            for (DraftItemEdit edit : editedItems) {
                if (edit.orderItemId() != null) {
                    OrderItem old = existingById.get(edit.orderItemId());
                    if (old == null) {
                        throw new AppException("Order item #" + edit.orderItemId() + " no longer exists. Refresh and try again.");
                    }
                    keptIds.add(old.id());
                    if (edit.qty() <= 0) {
                        orders.voidItem(old.id());
                        changes.add(new DraftChangeLine("REMOVED", old.name(), old.qty(), old.addons(), old.instructions()));
                    } else if (edit.qty() != old.qty()) {
                        BigDecimal unitPrice = Money.nz(old.unitPrice());
                        BigDecimal gross = Money.mul(unitPrice, BigDecimal.valueOf(edit.qty()));
                        BigDecimal itemDisc = Money.nz(old.discount()).min(gross);
                        BigDecimal lineTotal = Money.maxZero(Money.sub(gross, itemDisc));
                        orders.updateItem(old.id(), edit.qty(), itemDisc, lineTotal);
                        int delta = edit.qty() - old.qty();
                        changes.add(new DraftChangeLine(delta > 0 ? "INCREASED" : "DECREASED",
                                old.name(), Math.abs(delta), old.addons(), old.instructions()));
                    }
                } else if (edit.qty() > 0) {
                    BigDecimal unitPrice = Money.nz(edit.unitPrice());
                    BigDecimal gross = Money.mul(unitPrice, BigDecimal.valueOf(edit.qty()));
                    BigDecimal itemDisc = Money.nz(edit.discount()).min(gross);
                    BigDecimal lineTotal = Money.maxZero(Money.sub(gross, itemDisc));
                    orders.addItem(new OrderRepository.NewItem(
                            orderId, edit.productId(), edit.variantId(), edit.name(),
                            edit.sku() == null ? "" : edit.sku(), unitPrice, Money.nz(edit.cost()),
                            edit.qty(), edit.addons() == null ? "" : edit.addons(),
                            edit.instructions() == null ? "" : edit.instructions(), itemDisc, lineTotal));
                    changes.add(new DraftChangeLine("ADDED", edit.name(), edit.qty(), edit.addons(), edit.instructions()));
                }
            }

            // 2. Void any existing items removed from editedItems
            for (OrderItem old : existing) {
                if (!keptIds.contains(old.id())) {
                    orders.voidItem(old.id());
                    changes.add(new DraftChangeLine("REMOVED", old.name(), old.qty(), old.addons(), old.instructions()));
                }
            }

            // 3. Recalculate totals strictly from the newly saved DB order_items
            List<OrderItem> savedItems = orders.items(orderId);
            Cart.Totals t;
            if (savedItems.isEmpty()) {
                t = new Cart.Totals();
            } else {
                BigDecimal existingLineDiscounts = existing.stream()
                        .map(i -> Money.nz(i.discount()))
                        .reduce(Money.ZERO, Money::add);
                BigDecimal orderLevelDiscount = Money.maxZero(Money.sub(Money.nz(order.discountTotal()), existingLineDiscounts));

                Cart recalc = new Cart();
                recalc.type = order.type();
                recalc.deliveryCharge = order.type() == OrderType.DELIVERY ? Money.nz(order.deliveryCharge()) : Money.ZERO;
                BigDecimal sub = Money.ZERO;
                for (OrderItem si : savedItems) {
                    Cart.CartLine l = new Cart.CartLine();
                    l.productId = si.productId() == null ? 0L : si.productId();
                    l.variantId = si.variantId();
                    l.name = si.name();
                    l.sku = si.sku();
                    l.unitPrice = Money.nz(si.unitPrice());
                    l.cost = Money.nz(si.cost());
                    l.qty = si.qty();
                    l.discount = Money.nz(si.discount());
                    l.detail = si.addons();
                    l.instructions = si.instructions() == null ? "" : si.instructions();
                    l.taxRate = BigDecimal.ZERO;
                    recalc.lines.add(l);
                    sub = sub.add(l.gross());
                }
                recalc.orderDiscount = orderLevelDiscount.min(sub);
                t = quote(recalc);
            }

            orders.updateTotalsOnly(orderId, t.subtotal, t.discount, t.tax, t.service, t.delivery, t.total);

            // 4. Preserve existing payments; recompute payment_status safely
            BigDecimal totalPaid = orders.totalPaid(orderId);
            BigDecimal credited = Money.nz(order.creditAmount());
            PaymentStatus nextPs;
            if (savedItems.isEmpty()) {
                nextPs = totalPaid.signum() > 0 ? PaymentStatus.PARTIAL : PaymentStatus.UNPAID;
            } else if (order.paymentStatus() == PaymentStatus.ON_ACCOUNT && totalPaid.add(credited).compareTo(t.total) >= 0) {
                nextPs = PaymentStatus.ON_ACCOUNT;
            } else if (totalPaid.compareTo(t.total) >= 0 && t.total.signum() > 0) {
                nextPs = PaymentStatus.PAID;
            } else if (totalPaid.signum() > 0) {
                nextPs = PaymentStatus.PARTIAL;
            } else {
                nextPs = PaymentStatus.UNPAID;
            }
            orders.updatePaymentStatus(orderId, nextPs);

            orders.addEvent(orderId, session.userId(), "DRAFT_EDITED",
                    changes.size() + " item change(s); new total " + Money.fmt(t.total));
            audit.log("DRAFT_ORDER_EDITED", "orders", orderId,
                    order.orderNo() + " edited (" + changes.size() + " change(s), total " + Money.plain(t.total) + ")");

            return new DraftSaveResult(orders.find(orderId), savedItems, changes);
        });
    }

    public void updateOrderNotes(long orderId, String notes, Session session) {
        db.update("UPDATE orders SET notes=?, updated_at=? WHERE id=?", notes, LocalDateTime.now(), orderId);
        orders.addEvent(orderId, session.userId(), "NOTE_UPDATED", notes);
    }

    public Long currentShiftId(long userId) {
        long id = db.scalarLong("SELECT id FROM shifts WHERE user_id=? AND status='OPEN' ORDER BY id DESC LIMIT 1", userId);
        return id == 0 ? null : id;
    }

    private String trim(String s) { return s == null ? null : s.trim(); }
}
