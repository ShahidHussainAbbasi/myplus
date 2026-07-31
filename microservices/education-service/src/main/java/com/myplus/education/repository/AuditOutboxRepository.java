package com.myplus.education.repository;

import com.myplus.education.entity.AuditOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, Long> {

    /** The relay's work queue — bounded so one flush cannot load an unbounded backlog. */
    List<AuditOutbox> findTop100ByStatusOrderByIdAsc(String status);
}
