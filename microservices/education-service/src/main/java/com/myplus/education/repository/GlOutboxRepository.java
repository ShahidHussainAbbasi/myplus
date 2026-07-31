package com.myplus.education.repository;

import com.myplus.education.entity.GlOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Slice 0.1: the education GL outbox.
 *
 * Deliberately NOT org-scoped. The relay drains work for every tenant from a background thread that has no
 * caller identity — a scoped query would return nothing and the books would silently stop posting. Tenant
 * isolation is preserved at delivery instead: each row carries its own organizationId, and the publisher
 * impersonates exactly that tenant via GatewayIdentityForwarding.runAs. This is infrastructure, not a
 * user-facing read, so the multi-tenancy standard's findScoped rule does not apply.
 */
@Repository
public interface GlOutboxRepository extends JpaRepository<GlOutbox, Long> {

    /** Undelivered rows, oldest first — matches the (status, id) index. */
    List<GlOutbox> findTop100ByStatusOrderByIdAsc(String status);
}
