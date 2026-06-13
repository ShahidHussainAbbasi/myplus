package com.myplus.business_service.service;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.myplus.business_service.entity.Item;
import com.myplus.business_service.repository.ItemRepo;

public interface IItemService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Item, Long> {

// Here DSL queries can be tried
//	void updateItemStock(PurchaseDTO dto);

    /** Tenant-scoped items (own org + caller's pre-migration org-NULL rows). */
    List<Item> findScoped(Long orgId, Long userId);

    /** Paged tenant-scoped items (slice 24). */
    List<Item> findScoped(Long orgId, Long userId, Pageable pageable);

}
