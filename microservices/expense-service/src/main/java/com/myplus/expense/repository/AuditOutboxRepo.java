package com.myplus.expense.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.expense.entity.AuditOutbox;

public interface AuditOutboxRepo extends JpaRepository<AuditOutbox, Long> {

    List<AuditOutbox> findTop100ByStatusOrderByIdAsc(String status);
}
