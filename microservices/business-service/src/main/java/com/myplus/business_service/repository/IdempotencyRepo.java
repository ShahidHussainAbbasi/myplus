package com.myplus.business_service.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.IdempotencyRecord;

/** Audit #5: lookup + dedup for the shared idempotency guard. Uniqueness is enforced by the DB index. */
public interface IdempotencyRepo extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOrganizationIdAndOperationAndIdemKey(Long organizationId, String operation, String idemKey);
}
