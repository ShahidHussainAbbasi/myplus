package com.myplus.business_service.repository;

import com.myplus.business_service.entity.CashierShift;
import com.myplus.business_service.entity.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Cashier shifts (POS day-close, slice 39). */
@Repository
public interface CashierShiftRepo extends JpaRepository<CashierShift, Long> {
    /** The cashier's currently open till session, if any. */
    Optional<CashierShift> findFirstByOrganizationIdAndUserIdAndStatusOrderByOpenedAtDesc(
            Long organizationId, Long userId, ShiftStatus status);
}
