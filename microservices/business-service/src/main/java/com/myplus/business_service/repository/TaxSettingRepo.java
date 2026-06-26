package com.myplus.business_service.repository;

import com.myplus.business_service.entity.TaxSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Per-org tax policy lookup (G3 tax engine, slice 35). One setting per tenant. */
@Repository
public interface TaxSettingRepo extends JpaRepository<TaxSetting, Long> {
    Optional<TaxSetting> findByOrganizationId(Long organizationId);
}
