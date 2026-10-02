package com.myplus.expense.repository;

import java.time.LocalDate;
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

    /** EX-3 — the voucher already made for an originating record (the idempotent receiver's replay). */
    Optional<ExpenseVoucher> findByOrganizationIdAndSourceAndSourceRef(Long organizationId, String source, String sourceRef);

    @Query("SELECT v FROM ExpenseVoucher v WHERE v.organizationId = :org "
         + "AND (:userId IS NULL OR v.userId = :userId) "
         + "AND (:status IS NULL OR v.status = :status) "
         + "AND (:from IS NULL OR v.voucherDate >= :from) "
         + "AND (:to IS NULL OR v.voucherDate <= :to) "
         + "ORDER BY v.voucherDate DESC, v.id DESC")
    Page<ExpenseVoucher> search(@Param("org") Long org, @Param("userId") Long userId, @Param("status") String status,
                                @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

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
