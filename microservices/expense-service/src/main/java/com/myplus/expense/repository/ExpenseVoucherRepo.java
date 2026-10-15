package com.myplus.expense.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.expense.entity.ExpenseVoucher;

/**
 * Vouchers, always read inside one tenant. {@code userId} narrows to the caller's own vouchers for the USER tier
 * (data-visibility rule: USER own, ADMIN/OWNER all) — null means "every user in the org".
 *
 * <p>Served by idx_expense_voucher_org_date / _org_user_date / _org_status (V1, STANDARDS D3b).
 */
public interface ExpenseVoucherRepo extends JpaRepository<ExpenseVoucher, Long> {

    Optional<ExpenseVoucher> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<ExpenseVoucher> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    /**
     * FP-3 — the bill, row-locked for the rest of the transaction. Two people paying the same bill at once are
     * serialised here, so the second sees the first's reservation and cannot pay past what is owed.
     */
    /**
     * FP-5b — one supplier's bills that Pay Supplier may settle: posted, IN THE BOOKS, still owed, oldest first. The
     * whole tenant's (not one user's): a supplier is paid for every bill the business owes, whoever recorded it.
     */
    @Query("SELECT v FROM ExpenseVoucher v WHERE v.organizationId = :org AND v.supplierId = :supplierId "
         + "AND v.paidFrom = 'AP' AND v.status = 'POSTED' AND v.postingStatus = 'POSTED_GL' AND v.total > v.paidAmount "
         + "ORDER BY v.voucherDate ASC, v.id ASC")
    java.util.List<ExpenseVoucher> findOpenBills(@Param("org") Long org, @Param("supplierId") Long supplierId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ExpenseVoucher v WHERE v.id = :id AND v.organizationId = :org")
    Optional<ExpenseVoucher> lockForPayment(@Param("id") Long id, @Param("org") Long org);

    /** EX-3 — the voucher already made for an originating record (the idempotent receiver's replay). */
    Optional<ExpenseVoucher> findByOrganizationIdAndSourceAndSourceRef(Long organizationId, String source, String sourceRef);

    /**
     * The list, newest first. {@code toExclusive} is the day AFTER the last day shown, never "<= to": MySQL 8.0.46
     * answers {@code voucher_date >= X AND voucher_date <= X ... ORDER BY voucher_date DESC, id DESC LIMIT n} (one day,
     * the list's default) with a backward index scan that returns the OLDEST n rows in ascending order. A day with more
     * than a page of expenses then hid its newest ones, and page 2 repeated page 1's. Measured 2026-10-09 on 56 rows of
     * one day: half-open [X, X+1) returns them right; counts and sums were never affected.
     */
    @Query("SELECT v FROM ExpenseVoucher v WHERE v.organizationId = :org "
         + "AND (:userId IS NULL OR v.userId = :userId) "
         + "AND (:status IS NULL OR v.status = :status) "
         + "AND (:from IS NULL OR v.voucherDate >= :from) "
         + "AND (:toExclusive IS NULL OR v.voucherDate < :toExclusive) "
         + "AND (:claim IS NULL OR v.claimStatus = :claim) "
         + "ORDER BY v.voucherDate DESC, v.id DESC")
    Page<ExpenseVoucher> search(@Param("org") Long org, @Param("userId") Long userId, @Param("status") String status,
                                @Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive, @Param("claim") String claim,
                                Pageable pageable);

    /**
     * E11 — what this tenant's bills owe IN THE BOOKS: posted, journal landed, net of payments. Exactly the documents
     * finance's subledger should hold open (EXPENSE_BILL), so the daily check can compare the two.
     */
    @Query("SELECT COUNT(v) AS count, COALESCE(SUM(v.total - v.paidAmount), 0) AS total FROM ExpenseVoucher v "
         + "WHERE v.organizationId = :org AND v.paidFrom = 'AP' AND v.status = 'POSTED' AND v.postingStatus = 'POSTED_GL'")
    Totals billsInBooks(@Param("org") Long org);

    /** E11 — every numbered bill of a tenant (posted or voided), for a re-send to the subledger. */
    @Query("SELECT v FROM ExpenseVoucher v WHERE v.organizationId = :org AND v.paidFrom = 'AP' AND v.voucherNo IS NOT NULL ORDER BY v.id")
    List<ExpenseVoucher> findBillsOfOrg(@Param("org") Long org);

    /**
     * EX-8a — expenses IN THE BOOKS dated in the period (a later void does not take them out of their own day: its
     * reversal counts on its day, {@link #voidsInRange}). The P&L's rule, so the report reconciles with it.
     */
    @Query("SELECT DISTINCT v FROM ExpenseVoucher v LEFT JOIN FETCH v.lines WHERE v.organizationId = :org "
         + "AND (:userId IS NULL OR v.userId = :userId) AND v.postingStatus = 'POSTED_GL' AND v.status IN ('POSTED','VOIDED') "
         + "AND v.voucherDate >= :from AND v.voucherDate <= :to")
    List<ExpenseVoucher> postedInRange(@Param("org") Long org, @Param("userId") Long userId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** EX-8a — voids whose reversal reached the books, by the reversal's own date. */
    @Query("SELECT DISTINCT v FROM ExpenseVoucher v LEFT JOIN FETCH v.lines WHERE v.organizationId = :org "
         + "AND (:userId IS NULL OR v.userId = :userId) AND v.status = 'VOIDED' AND v.postingStatus = 'POSTED_GL' "
         + "AND v.postingError IS NULL AND v.voidPostedOn >= :from AND v.voidPostedOn <= :to")
    List<ExpenseVoucher> voidsInRange(@Param("org") Long org, @Param("userId") Long userId,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * EX-8b — the same payee, date and amount already recorded in this business (whoever recorded it): not voided, and
     * not a claim that was turned down or taken back. The payee is compared trimmed and case-blind.
     */
    @Query("SELECT v FROM ExpenseVoucher v WHERE v.organizationId = :org AND v.voucherDate = :date AND v.total = :total "
         + "AND LOWER(TRIM(v.payeeName)) = :payee AND v.status <> 'VOIDED' "
         + "AND (v.claimStatus IS NULL OR v.claimStatus IN ('SUBMITTED','APPROVED')) ORDER BY v.id")
    List<ExpenseVoucher> sameExpense(@Param("org") Long org, @Param("date") LocalDate date,
                                     @Param("total") java.math.BigDecimal total, @Param("payee") String payee);

    /**
     * EX-9a — an expense this business already has on the same day for the same amount (any payee): a past pay-out that
     * matches one is flagged before it is imported. Not voided, not a claim that was turned down or taken back.
     */
    @Query("SELECT v FROM ExpenseVoucher v WHERE v.organizationId = :org AND v.voucherDate = :date AND v.total = :total "
         + "AND v.status <> 'VOIDED' AND (v.claimStatus IS NULL OR v.claimStatus IN ('SUBMITTED','APPROVED')) ORDER BY v.id")
    List<ExpenseVoucher> sameDayAmount(@Param("org") Long org, @Param("date") LocalDate date, @Param("total") java.math.BigDecimal total);

    /** EX-2d / E4 — what the list's filter adds up to: posted expenses only (a void or a draft spent nothing). */
    interface Totals { long getCount(); java.math.BigDecimal getTotal(); }

    @Query("SELECT COUNT(v) AS count, COALESCE(SUM(v.total), 0) AS total FROM ExpenseVoucher v "
         + "WHERE v.organizationId = :org AND v.status = 'POSTED' "
         + "AND (:userId IS NULL OR v.userId = :userId) "
         + "AND (:from IS NULL OR v.voucherDate >= :from) "
         + "AND (:to IS NULL OR v.voucherDate <= :to)")
    Totals totals(@Param("org") Long org, @Param("userId") Long userId,
                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Stamp what the ledger answered — a targeted UPDATE, not a load-and-save, so the relay (which runs outside
     * the request) can never overwrite a void the owner made in between. Version bumped so a stale screen that
     * then tries to void gets a conflict rather than a silent overwrite.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ExpenseVoucher v SET v.postingStatus = :ps, v.postingError = :err, v.version = v.version + 1, "
         + "v.updatedAt = CURRENT_TIMESTAMP WHERE v.id = :id")
    int stampPosting(@Param("id") Long id, @Param("ps") String postingStatus, @Param("err") String error);
}
