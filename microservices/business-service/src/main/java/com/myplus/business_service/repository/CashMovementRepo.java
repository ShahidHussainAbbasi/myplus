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

    List<CashMovement> findByShiftIdOrderByDatedAsc(Long shiftId);

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM CashMovement m WHERE m.shiftId = :shiftId AND m.type = :type")
    BigDecimal sumByShiftAndType(@Param("shiftId") Long shiftId, @Param("type") MovementType type);
}
