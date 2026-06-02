package com.myplus.business_service.service;

import com.myplus.business_service.repository.ItemRepo;

public interface IItemService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Item, Long> {

// Here DSL queries can be tried
//	void updateItemStock(PurchaseDTO dto);


}
