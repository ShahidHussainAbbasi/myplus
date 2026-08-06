package com.myplus.education.repository;

import com.myplus.education.entity.NotifyOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Slice N1 — the notification outbox. Same shape as {@code AuditOutboxRepository}. */
@Repository
public interface NotifyOutboxRepository extends JpaRepository<NotifyOutbox, Long> {

    /** The relay's work queue — bounded so one flush cannot load an unbounded backlog. */
    List<NotifyOutbox> findTop100ByStatusOrderByIdAsc(String status);
}
