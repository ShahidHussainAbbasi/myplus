package com.myplus.expense.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.expense.entity.ExpenseOutbox;

/** The ledger-posting work queue (shared OutboxRelay drives it). */
public interface ExpenseOutboxRepo extends JpaRepository<ExpenseOutbox, Long> {

    List<ExpenseOutbox> findTop100ByStatusOrderByIdAsc(String status);

    /** EX-1b — one voucher's rows in a state (a "Post again" re-queues its FAILED ones). */
    List<ExpenseOutbox> findByVoucherIdAndStatus(Long voucherId, String status);
}
