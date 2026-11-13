package com.restaurantpos.service;

import com.restaurantpos.dao.CustomerLedgerRepository;
import com.restaurantpos.dao.Database;
import com.restaurantpos.dao.FloorRepository;
import com.restaurantpos.dao.MenuRepository;
import com.restaurantpos.dao.OrderRepository;
import com.restaurantpos.enums.OrderStatus;
import com.restaurantpos.enums.PaymentMethod;
import com.restaurantpos.enums.PaymentStatus;
import com.restaurantpos.enums.Role;
import com.restaurantpos.enums.TableStatus;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.Inventory.Customer;
import com.restaurantpos.model.Inventory.CustomerLedgerEntry;
import com.restaurantpos.model.Inventory.CustomerLedgerSummary;
import com.restaurantpos.model.Sales.Order;
import com.restaurantpos.model.Sales.Shift;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Money;
import com.restaurantpos.util.Validators;

import java.math.BigDecimal;
import java.util.List;

/**
 * Transactional service for Customer Ledger / Credit accounting:
 * - Customer search & duplicate-safe creation
 * - Recording credit sales against Draft/Unpaid orders without inflating cash sales
 * - Collecting payments against customer outstanding balances
 */
public final class CustomerLedgerService {
    private final CustomerLedgerRepository ledger;
    private final MenuRepository menu;
    private final OrderRepository orders;
    private final FloorRepository floors;
    private final ShiftService shifts;
    private final AuditService audit;
    private final Database db = Database.get();

    public CustomerLedgerService(CustomerLedgerRepository ledger, MenuRepository menu, OrderRepository orders,
                                 FloorRepository floors, ShiftService shifts, AuditService audit) {
        this.ledger = ledger;
        this.menu = menu;
        this.orders = orders;
        this.floors = floors;
        this.shifts = shifts;
        this.audit = audit;
    }

    public List<Customer> searchCustomers(String query) {
        return ledger.searchCustomers(query);
    }

    public List<CustomerLedgerSummary> summaries(String search) {
        return ledger.listSummaries(search);
    }

    public CustomerLedgerSummary summary(long customerId) {
        return ledger.summary(customerId);
    }

    public List<CustomerLedgerEntry> entries(long customerId) {
        return ledger.entries(customerId);
    }

    public BigDecimal balance(long customerId) {
        return ledger.balance(customerId);
    }

    public Customer findCustomer(long id) {
        return ledger.findCustomer(id);
    }

    public long createCustomer(String name, String phone, String address, String email, String notes, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        Validators.required(name, "Customer name");
        Validators.phone(phone, "Customer phone", true);
        String trimmedName = name.trim();
        String trimmedPhone = phone == null ? "" : phone.trim();

        if (!trimmedPhone.isBlank()) {
            Customer byPhone = ledger.findCustomerByPhone(trimmedPhone);
            if (byPhone != null) {
                throw new AppException("A customer with phone number " + trimmedPhone + " already exists: " + byPhone.name());
            }
        } else {
            Customer byName = ledger.findCustomerByName(trimmedName);
            if (byName != null) {
                throw new AppException("A customer named \"" + trimmedName + "\" already exists. Provide a phone number or select the existing customer.");
            }
        }

        long id = menu.saveCustomer(new Customer(0, trimmedName, trimmedPhone,
                address == null ? "" : address.trim(),
                email == null ? "" : email.trim(),
                notes == null ? "" : notes.trim(), null));
        audit.log("CUSTOMER_CREATED", "customers", id, trimmedName + (trimmedPhone.isBlank() ? "" : " / " + trimmedPhone));
        return id;
    }

    /**
     * Records an unpaid order balance (or selected credit portion) into a customer's credit ledger.
     * NEVER records a cash payment row or increases cash sales.
     */
    public long addOrderToLedger(long customerId, long orderId, BigDecimal creditAmount, String notes, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        if (customerId <= 0) throw new AppException("Select a customer before adding to the ledger.");
        Customer customer = ledger.findCustomer(customerId);
        if (customer == null) throw new AppException("Selected customer does not exist.");

        Order order = orders.find(orderId);
        if (order == null) throw new AppException("Order not found.");
        if (order.status() == OrderStatus.CANCELLED) throw new AppException("Cancelled orders cannot be added to a customer ledger.");
        if (order.paymentStatus() == PaymentStatus.PAID) throw new AppException("Order " + order.orderNo() + " is already paid in full.");

        BigDecimal remaining = Money.maxZero(order.balance());
        if (remaining.signum() <= 0) {
            throw new AppException("Order " + order.orderNo() + " has no remaining balance to add to the ledger.");
        }
        BigDecimal amt = creditAmount == null ? remaining : Money.nz(creditAmount);
        if (amt.signum() <= 0) {
            throw new AppException("Credit amount must be greater than zero.");
        }
        if (amt.compareTo(remaining) > 0) {
            throw new AppException("Credit amount (" + Money.fmt(amt) + ") exceeds order remaining balance (" + Money.fmt(remaining) + ").");
        }

        Shift currentShift = shifts.current(session.userId());
        if (currentShift == null && session.role() == Role.CASHIER) {
            currentShift = shifts.ensureActiveShiftForCashier(session.user(), session);
        }
        final Long shiftId = currentShift == null ? null : currentShift.id();

        return db.tx(c -> {
            // Double-check duplicate credit inside transaction lock
            BigDecimal alreadyCredited = ledger.creditedForOrder(orderId);
            BigDecimal currentPaid = orders.totalPaid(orderId);
            BigDecimal maxAllowedCredit = Money.maxZero(Money.sub(order.total(), Money.add(currentPaid, alreadyCredited)));
            if (amt.compareTo(maxAllowedCredit) > 0) {
                throw new AppException("Order " + order.orderNo() + " already has credit recorded on a customer ledger.");
            }

            BigDecimal prevBal = ledger.balance(customerId);
            BigDecimal nextBal = Money.add(prevBal, amt);
            String desc = "Credit sale — Order " + order.orderNo() + (notes != null && !notes.isBlank() ? " (" + notes.trim() + ")" : "");

            long entryId = ledger.insertEntry(customerId, orderId, "CREDIT_SALE", amt, amt, nextBal,
                    PaymentMethod.CREDIT.name(), order.orderNo(), desc, session.userId(), shiftId);

            orders.setCustomer(orderId, customer.id(), customer.name(), customer.phone(),
                    order.deliveryAddress() != null && !order.deliveryAddress().isBlank() ? order.deliveryAddress() : customer.address());
            BigDecimal newCreditTotal = Money.add(alreadyCredited, amt);
            orders.setCreditAmount(orderId, newCreditTotal);

            // Update order status cleanly to ON_ACCOUNT without marking cash-paid
            BigDecimal allocated = Money.add(currentPaid, newCreditTotal);
            boolean fullyAllocated = allocated.compareTo(order.total()) >= 0;
            OrderStatus nextOrderStatus = fullyAllocated
                    ? (order.status() == OrderStatus.DRAFT || order.status() == OrderStatus.PENDING_PAYMENT ? OrderStatus.CONFIRMED : order.status())
                    : order.status();
            orders.updateStatus(orderId, nextOrderStatus, order.kitchenStatus(), PaymentStatus.ON_ACCOUNT);

            if (fullyAllocated && order.tableId() != null && !orders.hasOtherOpenOrderForTable(order.tableId(), orderId)) {
                floors.setStatus(order.tableId(), TableStatus.AVAILABLE);
            }

            orders.addEvent(orderId, session.userId(), "LEDGER_CREDIT",
                    Money.fmt(amt) + " added to ledger for " + customer.name());
            audit.log("CUSTOMER_LEDGER_CREDIT", "customer_ledger", entryId,
                    customer.name() + " / " + order.orderNo() + " / +" + Money.plain(amt));
            return entryId;
        });
    }

    /**
     * Collects a payment against an existing customer ledger balance.
     */
    public long collectLedgerPayment(long customerId, BigDecimal amount, PaymentMethod method,
                                     String reference, String notes, Session session) {
        Access.require(session, Role.OWNER, Role.MANAGER, Role.CASHIER);
        if (customerId <= 0) throw new AppException("Select a customer.");
        Customer customer = ledger.findCustomer(customerId);
        if (customer == null) throw new AppException("Customer not found.");
        if (amount == null || amount.signum() <= 0) throw new AppException("Payment amount must be greater than zero.");
        if (method == null || method == PaymentMethod.CREDIT) {
            throw new AppException("Select a valid payment method (Cash, Card, Easypaisa, JazzCash, or Bank Transfer).");
        }
        String ref = reference == null ? "" : reference.trim();
        if ((method == PaymentMethod.EASYPAISA || method == PaymentMethod.JAZZCASH || method == PaymentMethod.BANK_TRANSFER) && ref.isBlank()) {
            throw new AppException("Enter the " + method.label() + " transaction reference.");
        }

        Shift currentShift = shifts.current(session.userId());
        if (currentShift == null && session.role() == Role.CASHIER) {
            currentShift = shifts.ensureActiveShiftForCashier(session.user(), session);
        }
        final Long shiftId = currentShift == null ? null : currentShift.id();

        return db.tx(c -> {
            BigDecimal currentBal = ledger.balance(customerId);
            if (currentBal.signum() <= 0) {
                throw new AppException(customer.name() + " has no outstanding balance to pay.");
            }
            if (amount.compareTo(currentBal) > 0) {
                throw new AppException("Payment (" + Money.fmt(amount) + ") exceeds customer's outstanding balance of " + Money.fmt(currentBal) + ".");
            }
            BigDecimal nextBal = Money.sub(currentBal, amount);
            String desc = "Ledger payment received via " + method.label()
                    + (notes != null && !notes.isBlank() ? " — " + notes.trim() : "");

            long entryId = ledger.insertEntry(customerId, null, "PAYMENT", amount, amount.negate(), nextBal,
                    method.name(), ref, desc, session.userId(), shiftId);

            audit.log("CUSTOMER_LEDGER_PAYMENT", "customer_ledger", entryId,
                    customer.name() + " / " + method.label() + " / -" + Money.plain(amount) + " (Bal: " + Money.plain(nextBal) + ")");
            return entryId;
        });
    }
}
