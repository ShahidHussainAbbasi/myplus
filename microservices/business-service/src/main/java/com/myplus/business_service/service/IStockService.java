package com.myplus.business_service.service;

import com.myplus.business_service.repository.StockRepo;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.dto.PurchaseDTO;

public interface IStockService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Stock, Long> {

	Stock updateStock(Sell dto);

	Stock updateStock(PurchaseDTO dto) throws Exception;

	java.util.Set<String> getItemBatch(Long userId, Long itemId);

	/** Tenant-scoped batch list / single-batch lookup (own org + caller's pre-migration org-NULL rows). */
	java.util.Set<String> getItemBatchScoped(Long orgId, Long userId, Long itemId);

	java.util.Optional<Stock> findByBatchScoped(String batchNo, Long orgId, Long userId);

	java.util.Optional<Stock> findByItemId(Long itemId);

}
