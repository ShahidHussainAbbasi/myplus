package com.myplus.finance.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.finance.entity.ProcessedEvent;

/** Audit #5: dedup lookup for GL event posting. Uniqueness is enforced by the DB index. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByOrganizationIdAndEventKey(Long organizationId, String eventKey);
}
