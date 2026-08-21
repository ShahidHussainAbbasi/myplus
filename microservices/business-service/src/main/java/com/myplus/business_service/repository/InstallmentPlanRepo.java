package com.myplus.business_service.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.InstallmentPlan;

/**
 * INST-1 — plans, always org-scoped.
 *
 * <p>Every read here carries the tenant predicate. The NULL-fallback the older repositories use
 * ({@code organizationId IS NULL AND userId = :userId}) is deliberately ABSENT: there are no
 * pre-migration installment rows to be lenient about, because the feature is new. Adding a fallback for a
 * case that cannot exist would only widen what a query can return.
 */
public interface InstallmentPlanRepo extends JpaRepository<InstallmentPlan, Long> {

    /**
     * Next per-org plan number. {@code COALESCE(...,0)} so the first plan in a new org starts at 1 rather
     * than tripping over a null — the same shape {@code maxQuoteSeqForOrg} uses.
     */
    @Query("SELECT COALESCE(MAX(p.planSeq), 0) FROM InstallmentPlan p WHERE p.organizationId = :orgId")
    long maxPlanSeqForOrg(@Param("orgId") Long orgId);

    @Query("SELECT p FROM InstallmentPlan p WHERE p.id = :id AND p.organizationId = :orgId")
    Optional<InstallmentPlan> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId);

    @Query("SELECT p FROM InstallmentPlan p WHERE p.organizationId = :orgId "
         + "AND p.customerId = :customerId ORDER BY p.id DESC")
    List<InstallmentPlan> findByCustomerScoped(@Param("orgId") Long orgId,
                                               @Param("customerId") Long customerId);

    /**
     * The plans a receipt may be applied to for this customer — live or in collections, never cancelled,
     * completed or written off.
     *
     * <p>This is the supplier behind the composed open-doc stream (design D2). {@code isCollectable()} on the
     * entity states the same rule for a single plan; both read from the same two constants.
     */
    @Query("SELECT p FROM InstallmentPlan p WHERE p.organizationId = :orgId AND p.customerId = :customerId "
         + "AND p.status IN ('ACTIVE','DEFAULTED') ORDER BY p.firstDueDate ASC, p.id ASC")
    List<InstallmentPlan> findCollectableByCustomer(@Param("orgId") Long orgId,
                                                    @Param("customerId") Long customerId);

    /**
     * Every plan in the tenant that still owes something — the Installments screen and the collections
     * worklist. Paged by the caller; the ORDER BY is what makes "most overdue first" cheap.
     */
    @Query("SELECT p FROM InstallmentPlan p WHERE p.organizationId = :orgId "
         + "AND p.status IN ('ACTIVE','DEFAULTED') ORDER BY p.firstDueDate ASC, p.id ASC")
    List<InstallmentPlan> findOpenScoped(@Param("orgId") Long orgId);

    /**
     * Plans carrying a given invoice — how the sale path finds the plan to cancel when an invoice is voided.
     * A list, not an Optional: one invoice should carry one plan, and returning a list means a data problem
     * surfaces as two rows rather than as an exception in the void path.
     */
    @Query("SELECT p FROM InstallmentPlan p WHERE p.organizationId = :orgId AND p.invoiceNo = :invoiceNo")
    List<InstallmentPlan> findByInvoiceNo(@Param("orgId") Long orgId, @Param("invoiceNo") String invoiceNo);

    /**
     * Does this customer already hold an open plan? Backs
     * {@code pos.installment.maxOpenPlansPerCustomer}.
     */
    @Query("SELECT COUNT(p) FROM InstallmentPlan p WHERE p.organizationId = :orgId "
         + "AND p.customerId = :customerId AND p.status IN ('ACTIVE','DEFAULTED')")
    long countOpenForCustomer(@Param("orgId") Long orgId, @Param("customerId") Long customerId);

    /**
     * The reminder scanner's window (INST-3): plans with an installment falling due in a date range.
     *
     * <p>Kept as a plan-level read so the scanner can apply plan-level rules — stop reminding a
     * {@code WRITTEN_OFF} plan — without a second query per installment.
     */
    @Query("SELECT DISTINCT p FROM InstallmentPlan p JOIN p.installments i "
         + "WHERE p.organizationId = :orgId AND p.status IN ('ACTIVE','DEFAULTED') "
         + "AND i.status IN ('SCHEDULED','PARTIAL') AND i.dueDate BETWEEN :from AND :to")
    List<InstallmentPlan> findWithInstallmentsDueBetween(@Param("orgId") Long orgId,
                                                          @Param("from") LocalDate from,
                                                          @Param("to") LocalDate to);
}
