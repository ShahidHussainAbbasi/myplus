package com.myplus.business_service.service;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.Vender;

public interface IVenderService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Vender, Long> {

	/** Tenant-scoped venders (own org + caller's pre-migration org-NULL rows). */
	List<Vender> findScoped(Long orgId, Long userId);

	/** F1 (AP): recompute the vendor's running payable from its open purchase bills = −Σ(purchase due), floored 0. */
	void recomputePayable(Long venderId);

	/** F1 (AP): pay a vendor — FIFO-allocate across open purchase bills (oldest first), recompute the payable, and
	 *  record a DISBURSEMENT in the shared finance ledger (best-effort). Returns
	 *  {success, voucherNo, allocated, onAccountAdvance, newDue}. */
	java.util.Map<String, Object> payVendor(Long venderId, java.math.BigDecimal amount, String method,
			java.time.LocalDate paidOn, String reference, String idempotencyKey);

}
