package com.service.business;

import java.text.ParseException;

import com.persistence.Repo.business.PurchaseRepo;
import com.persistence.model.business.Purchase;
import com.web.dto.business.PurchaseDTO;

public interface IPurchaseService extends PurchaseRepo{

	Purchase addPurchase(PurchaseDTO dto) throws ParseException;


}
