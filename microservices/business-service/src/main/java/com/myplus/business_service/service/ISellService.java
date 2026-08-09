package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.List;

import com.myplus.business_service.entity.Sell;

public interface ISellService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Sell, Long> {

	void addSell(List<Sell> dtos) throws Exception;

	/** Tenant-scoped sells (own org + caller's pre-migration org-NULL rows), newest first. */
	List<Sell> findScoped(Long orgId, Long userId);

	/** Paged tenant-scoped sells (slice 24), newest first. */
	List<Sell> findScoped(Long orgId, Long userId, org.springframework.data.domain.Pageable pageable);

	/** All line items of one invoice (customer_history), tenant-scoped — for loading a sale to edit. */
	List<Sell> findByInvoiceScoped(Long chId, Long orgId, Long userId);

	/** OWN sells only (role-aware visibility) — a non-SUPER caller sees just what they created. */
	List<Sell> findOwnScoped(Long orgId, Long userId);

	/** Multi-location (P2b): store-aware reads — used when the caller has store grants (non-empty set). */
	List<Sell> findScopedByStores(Long orgId, java.util.Collection<Long> storeIds);

	List<Sell> findOwnScopedByStores(Long orgId, Long userId, java.util.Collection<Long> storeIds);

	List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long orgId, Long userId);

	List<Sell> findSellByStartDate(LocalDateTime sd, Long orgId, Long userId);

	List<Sell> findSellByEndDate(LocalDateTime ed, Long orgId, Long userId);

	/**
	 * UI/UX P3 — this SHOP's best-selling products by units over the last {@code days}, highest first.
	 * Feeds the POS quick-pick tiles, so goods with no barcode (produce, bakery, services) can be rung
	 * up in one keystroke instead of through the full item form.
	 *
	 * <p>Org-scoped and store-aware, NOT per-user: a shared till must show every cashier the same tiles.
	 *
	 * @return productId -> units sold, ordered by units descending, at most {@code limit} entries
	 */
	java.util.List<java.util.Map<String, Object>> topProducts(int days, int limit);

}
