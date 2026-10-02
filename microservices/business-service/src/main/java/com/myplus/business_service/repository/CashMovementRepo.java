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

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM CashMovement m WHERE m.shiftId = :shiftId AND m.type = :type")
    BigDecimal sumByShiftAndType(@Param("shiftId") Long shiftId, @Param("type") MovementType type);
}
