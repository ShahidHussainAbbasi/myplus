package com.service.business;

import com.persistence.Repo.business.StockRepo;
import com.persistence.model.business.Sell;
import com.persistence.model.business.Stock;
import com.web.dto.business.PurchaseDTO;

public interface IStockService extends StockRepo {

	Stock updateStock(Stock dto);

	Stock updateStock(Sell dto);
	
	Stock updateStock(PurchaseDTO dto);

//	Set<Item> getItemBatch(Long id, Long itemId);

}
