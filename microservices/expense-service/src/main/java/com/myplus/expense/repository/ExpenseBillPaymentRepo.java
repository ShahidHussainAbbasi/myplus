package com.myplus.expense.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.expense.entity.ExpenseBillPayment;

/** FP-3 — payments against bills (V4: uq_expense_bill_payment_key, idx _voucher, idx _status). */
public interface ExpenseBillPaymentRepo extends JpaRepository<ExpenseBillPayment, Long> {

    Optional<ExpenseBillPayment> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);

    List<ExpenseBillPayment> findByVoucherIdOrderByIdAsc(Long voucherId);

    /** Σ reserved-but-unconfirmed payments on a bill — they count against what may still be paid. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM ExpenseBillPayment p WHERE p.voucherId = :voucherId AND p.status = 'PENDING'")
    BigDecimal sumPending(@Param("voucherId") Long voucherId);

    /** Reservations whose outcome was never learned (the caller gave up mid-call) — the reconciler settles them. */
    List<ExpenseBillPayment> findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(String status, LocalDateTime before);
}
