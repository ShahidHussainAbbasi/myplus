package com.service.business;

import com.persistence.Repo.business.BatchRepo;
import com.web.dto.business.PurchaseDTO;

public interface IBatchService extends BatchRepo {

	void updateBatch(PurchaseDTO dto);

}
