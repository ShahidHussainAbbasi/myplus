package com.service.business;

import com.persistence.Repo.business.CustomerHistoryRepo;
import com.persistence.model.business.CustomerHistory;
import com.web.dto.business.CustomerHistoryDTO;

public interface ICustomerHistoryService   extends CustomerHistoryRepo{

    CustomerHistory saveUpdateCustomerHistory(CustomerHistoryDTO dto);

// Here DSL queries can be tried
//	void updateItemStock(PurchaseDTO dto);


}
