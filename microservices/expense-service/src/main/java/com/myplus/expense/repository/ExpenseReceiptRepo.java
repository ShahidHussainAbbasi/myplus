package com.myplus.expense.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.expense.entity.ExpenseReceipt;

/** EX-5 — receipts, always read inside one tenant. */
public interface ExpenseReceiptRepo extends JpaRepository<ExpenseReceipt, Long> {

    Optional<ExpenseReceipt> findByIdAndOrganizationId(Long id, Long organizationId);

    List<ExpenseReceipt> findByVoucherIdAndRemovedAtIsNullOrderByIdAsc(Long voucherId);

    /** The same file already kept for this tenant (not removed) — the duplicate warning and the idempotent re-upload. */
    List<ExpenseReceipt> findByOrganizationIdAndSha256AndRemovedAtIsNull(Long organizationId, String sha256);

    /** Receipts per voucher for a page of the list (one query, not one per row). */
    @Query("SELECT r.voucherId, COUNT(r) FROM ExpenseReceipt r WHERE r.voucherId IN :ids AND r.removedAt IS NULL GROUP BY r.voucherId")
    List<Object[]> countByVoucherIds(@Param("ids") Collection<Long> ids);

    /** Uploaded but never attached to a saved expense, older than the cut-off — never evidence; swept. */
    List<ExpenseReceipt> findTop100ByVoucherIdIsNullAndUploadedAtBefore(LocalDateTime before);
}
