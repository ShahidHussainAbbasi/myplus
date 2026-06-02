package com.myplus.business_service.service;

import java.text.ParseException;

import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.dto.PurchaseDTO;

public interface IPurchaseService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Purchase, Long> {

	Purchase addPurchase(PurchaseDTO dto) throws ParseException, Exception;


}
