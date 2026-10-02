package com.myplus.expense.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.expense.entity.ExpenseOutbox;

/** The ledger-posting work queue (shared OutboxRelay drives it). */
public interface ExpenseOutboxRepo extends JpaRepository<ExpenseOutbox, Long> {

    List<ExpenseOutbox> findTop100ByStatusOrderByIdAsc(String status);
}
