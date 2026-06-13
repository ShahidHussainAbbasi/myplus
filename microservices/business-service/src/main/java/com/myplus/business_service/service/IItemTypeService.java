package com.myplus.business_service.service;

import java.util.List;

import com.myplus.business_service.entity.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IItemTypeService extends JpaRepository<ItemType, Long> {

    /** Tenant-scoped item types (own org + caller's pre-migration org-NULL rows). */
    List<ItemType> findScoped(Long orgId, Long userId);

}
