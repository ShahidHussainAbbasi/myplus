package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Tenders per sale invoice (G5 payments, slice 37). */
@Repository
public interface PaymentRepo extends JpaRepository<Payment, Long> {
    List<Payment> findByCustomerHistoryId(Long customerHistoryId);

    // POS day-close (slice 39): tender totals for a shift, grouped by method — [method, Σ amount].
    @Query("SELECT p.method, COALESCE(SUM(p.amount),0) FROM Payment p WHERE p.customerHistoryId IN "
            + "(SELECT ch.customer_history_id FROM CustomerHistory ch WHERE ch.shiftId = :shiftId) GROUP BY p.method")
    List<Object[]> sumByMethodForShift(@Param("shiftId") Long shiftId);
}
