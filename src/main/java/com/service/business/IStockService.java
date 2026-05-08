package com.service.business;

import com.persistence.Repo.business.StockRepo;
import com.persistence.model.business.Stock;

public interface IStockService extends StockRepo {

	Stock updateStock(Stock dto);

//	Set<Item> getItemBatch(Long id, Long itemId);

}
