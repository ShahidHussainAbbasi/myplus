package com.myplus.finance.repository;

import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Tenant-scoped ledger reads (org + user NULL-fallback), mirroring the platform multi-tenancy standard. */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    @Query("SELECT p FROM Payment p WHERE p.partyType = :partyType AND p.partyId = :partyId AND " + SCOPE
            + " ORDER BY p.id DESC")
    List<Payment> findByPartyScoped(@Param("partyType") PartyType partyType, @Param("partyId") Long partyId,
                                    @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.partyType = :partyType AND p.partyId = :partyId AND " + SCOPE)
    BigDecimal sumByPartyScoped(@Param("partyType") PartyType partyType, @Param("partyId") Long partyId,
                               @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Per-org receipt count → the next RCPT-###### sequence. */
    @Query("SELECT COUNT(p) FROM Payment p WHERE (p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))")
    long countScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
