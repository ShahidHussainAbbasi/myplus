package com.myplus.business_service.repository;

import com.myplus.business_service.entity.CashMovement;
import com.myplus.business_service.entity.MovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Cash-drawer movements within a shift (POS day-close, slice 39). */
@Repository
public interface CashMovementRepo extends JpaRepository<CashMovement, Long> {

    /** EX-3 — the movement a replayed submission already made. Served by uq_cash_movement_org_idem (V70). */
    java.util.Optional<CashMovement> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    /** EX-3 — stamp the expense number once delivered: a targeted UPDATE, not a load-and-save of the movement. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE CashMovement m SET m.expenseVoucherNo = :no WHERE m.id = :id")
    int stampExpenseVoucher(@Param("id") Long id, @Param("no") String voucherNo);

    List<CashMovement> findByShiftIdOrderByDatedAsc(Long shiftId);

    /**
     * EX-9a — the business's pay-outs that carry no expense number: made while Expense management was off (or not yet
     * delivered). Oldest first, at most {@code page.size}. expense-service decides which are really unbooked by its own
     * key (DRAWER + movement id), so an in-flight EX-3 delivery is never booked twice.
     */
    @Query("SELECT m FROM CashMovement m WHERE m.organizationId = :org AND m.type = com.myplus.business_service.entity.MovementType.PAY_OUT "
         + "AND m.expenseVoucherNo IS NULL ORDER BY m.dated ASC, m.id ASC")
    List<CashMovement> payoutsWithoutExpense(@Param("org") Long org, org.springframework.data.domain.Pageable page);

    /** EX-9a — stamp an imported pay-out: only a PAY_OUT of this business that has no number yet. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE CashMovement m SET m.expenseVoucherNo = :no WHERE m.id = :id AND m.organizationId = :org "
         + "AND m.type = com.myplus.business_service.entity.MovementType.PAY_OUT AND m.expenseVoucherNo IS NULL")
    int stampImported(@Param("org") Long org, @Param("id") Long id, @Param("no") String voucherNo);

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM CashMovement m WHERE m.shiftId = :shiftId AND m.type = :type")
    BigDecimal sumByShiftAndType(@Param("shiftId") Long shiftId, @Param("type") MovementType type);
}
