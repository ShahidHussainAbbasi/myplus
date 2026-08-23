package com.myplus.business_service.service;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.dto.CustomerHistoryDTO;

public interface ICustomerService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Customer, Long> {

    Customer saveUpdateCustomer(CustomerHistoryDTO customerObj) throws Exception;

    /**
     * Recompute a customer's running balance owed from their invoice headers (the single source of
     * truth). Call AFTER the sale's CustomerHistory is saved, so add / edit / re-edit stay correct.
     */
    void recomputeDue(Customer customer);

    /**
     * Receive Payment (AR subledger): FIFO-allocate a receipt across a customer's open invoices, recompute their
     * due, and record the receipt in the shared finance ledger. Returns {success, receiptNo, allocated,
     * onAccountCredit, newDue}.
     */
    java.util.Map<String, Object> receivePayment(Long customerId, java.math.BigDecimal amount, String method,
            java.time.LocalDate paidOn, String reference, String idempotencyKey);

    /** Tenant-scoped customers (own org + caller's pre-migration org-NULL rows). */
    List<Customer> findScoped(Long orgId, Long userId);

    /**
     * ONE customer, org-scoped — the anti-IDOR read for an id that arrived off the wire.
     *
     * <p>The rule D2 established the hard way: whether a read needs scoping depends on <b>where the id came
     * from</b>, not on which method reads it. An id followed from a row the caller could already see is safe;
     * an id off a query string, a path or a service-to-service body is not. Another tenant's customer reads as
     * empty — identically to a genuinely missing one, so the caller cannot probe which ids exist.
     */
    java.util.Optional<Customer> findByIdScoped(Long customerId, Long orgId, Long userId);

    /** Paged tenant-scoped customers (slice 24). */
    List<Customer> findScoped(Long orgId, Long userId, Pageable pageable);

    /** OWN customers only (role-aware) — a non-SUPER caller sees just the customers they created. */
    List<Customer> findOwnScoped(Long orgId, Long userId);

    /**
     * OMS O7 D2d — a field rep's TERRITORY: outlets assigned to them, plus every unassigned outlet.
     *
     * <p>Deliberately not {@link #findOwnScoped}, which keys on the audit field {@code userId} ("who created
     * this row") and therefore returns nothing to a rep, since the company creates the outlets.
     */
    List<Customer> findOutletsForRep(Long orgId, Long repUserId);

    /** Every outlet in the org — for a whole-org viewer (owner/admin). */
    List<Customer> findOutletsForOrg(Long orgId);

	/** O7 D6a — set (or clear, with a null rep) the rep covering these outlets. Returns how many were ours. */
	public int assignOutlets(java.util.List<Long> customerIds, Long repUserId, Long orgId, Long actingUserId);


}
