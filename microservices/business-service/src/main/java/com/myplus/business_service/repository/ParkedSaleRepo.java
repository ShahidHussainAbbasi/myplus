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
}
