package com.myplus.finance.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.finance.entity.PeriodLock;

/** One period-lock row per org (unique organization_id). */
public interface PeriodLockRepository extends JpaRepository<PeriodLock, Long> {
    Optional<PeriodLock> findByOrganizationId(Long organizationId);
}
