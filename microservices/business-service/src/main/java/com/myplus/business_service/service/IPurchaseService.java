package com.myplus.business_service.service;

import java.text.ParseException;
import java.util.List;

import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.dto.PurchaseDTO;

public interface IPurchaseService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Purchase, Long> {

	Purchase addPurchase(PurchaseDTO dto) throws ParseException, Exception;

	/** Tenant-scoped purchases (own org + caller's pre-migration org-NULL rows). */
	List<Purchase> findScoped(Long orgId, Long userId);

	/** OWN purchases only (role-aware) — a non-SUPER caller sees just the purchases they recorded. */
	List<Purchase> findOwnScoped(Long orgId, Long userId);

}
