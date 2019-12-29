package com.service.business;

import com.persistence.Repo.business.StockRepo;
import com.persistence.model.business.Stock;
import com.web.dto.business.PurchaseDTO;
import com.web.dto.business.SellDTO;

public interface IStockService extends StockRepo {

	Stock updateStock(SellDTO dto);
	
	Stock updateStock(PurchaseDTO dto);

//	Set<Item> getItemBatch(Long id, Long itemId);

}
