package com.myplus.business_service.service;

import java.util.List;

import com.myplus.business_service.entity.ItemUnit;
import com.myplus.business_service.repository.ItemUnitRepo;

public interface IItemUnitService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.ItemUnit, Long> {

    /** Tenant-scoped item units (own org + caller's pre-migration org-NULL rows). */
    List<ItemUnit> findScoped(Long orgId, Long userId);

}
