package com.myplus.business_service.repository;

import com.myplus.business_service.entity.ParkedSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Held/parked sales (POS R10, slice 40). Org + cashier scoped. */
@Repository
public interface ParkedSaleRepo extends JpaRepository<ParkedSale, Long> {
    List<ParkedSale> findByOrganizationIdAndUserIdOrderByParkedAtDesc(Long organizationId, Long userId);
    Optional<ParkedSale> findByIdAndOrganizationIdAndUserId(Long id, Long organizationId, Long userId);

    /**
     * PARK-CLAIM-1 — the scoped delete whose AFFECTED-ROW COUNT decides who resumed a parked sale.
     * Two claims of one id: the first DELETE takes the row lock, the second waits, then deletes 0 rows.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ParkedSale p WHERE p.id = :id "
            + "AND p.organizationId = :orgId AND p.userId = :userId")
    int deleteScoped(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("orgId") Long orgId,
                     @org.springframework.data.repository.query.Param("userId") Long userId);
}
