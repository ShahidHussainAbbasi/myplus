package com.myplus.business_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.util.AppUtil;

/**
 * M4e.d (slice 106): the legacy local-Stock endpoints (getUserStock(s)/getStock(itemId)/getAllStock/addStock/
 * deleteStock) are retired with the Item entity — stock lives in inventory-service, priced by the catalog master.
 * Only the two productId-native pre-fills the pickers use remain: {@code productStock} (sell/dispense) and
 * {@code getStockByBatch} (purchase). Both source on-hand/FEFO from inventory and price/description from catalog.
 */
@RestController
public class StockController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());

	@Autowired
	com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

	@Autowired
	com.myplus.commerce.contracts.client.CatalogClient catalogClient;

	@Autowired
	private AppUtil appUtil;

	@Autowired
	private com.myplus.common.settings.CapabilityService capabilityService;   // #22: skip the FEFO call off-vertical

	@Autowired
	private com.myplus.common.settings.SettingsService settingsService;

	@Autowired
	private com.myplus.business_service.service.StockSalesReportService stockSalesReport;   // SSR-1

	/**
	 * SSR-1 — the Stock & Sales Report (doc slices/110): per product for a period, grouped by main supplier. Owner and
	 * admin only, like every other report of the business's position. Dates ISO (yyyy-MM-dd); supplierId or companyId
	 * narrows it. A refusal (bad period) answers success:false with the reason.
	 */
	@org.springframework.security.access.prepost.PreAuthorize(
			"hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
	@RequestMapping(value = "/stockSalesReport", method = RequestMethod.GET)
	@ResponseBody
	public java.util.Map<String, Object> stockSalesReport(
			@org.springframework.web.bind.annotation.RequestParam("from") String from,
			@org.springframework.web.bind.annotation.RequestParam("to") String to,
			@org.springframework.web.bind.annotation.RequestParam(value = "supplierId", required = false) Long supplierId,
			@org.springframework.web.bind.annotation.RequestParam(value = "companyId", required = false) Long companyId) {
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
		try {
			out.putAll(stockSalesReport.report(java.time.LocalDate.parse(from), java.time.LocalDate.parse(to), supplierId, companyId));
			out.put("success", true);
		} catch (com.myplus.business_service.service.BusinessRuleException | java.time.format.DateTimeParseException bad) {
			out.put("success", false);
			out.put("message", bad instanceof com.myplus.business_service.service.BusinessRuleException ? bad.getMessage()
					: "Choose a valid period.");
		}
		return out;
	}   // STK-ALERT: the business's cap and window

	/**
	 * STK-ALERT — the header badge: what an owner should act on (low / out of stock against the business cap or the
	 * product's own minimum; expired and soon-expiring batches where the business tracks expiry), with names for the
	 * first few. Owner and admin only — the people who reorder and write off; a cashier is never shown it.
	 */
	@org.springframework.security.access.prepost.PreAuthorize(
			"hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
	@RequestMapping(value = "/stockAlertSummary", method = RequestMethod.GET)
	@ResponseBody
	@SuppressWarnings("unchecked")
	public java.util.Map<String, Object> stockAlertSummary() {
		int lowAt = Math.max(0, settingsService.getInt("pos.stock.lowStockAt", 0));
		boolean trackExpiry = capabilityService.isEnabled(com.myplus.common.settings.Capability.EXPIRY_TRACKING);
		int nearDays = trackExpiry ? Math.max(0, settingsService.getInt("pos.stock.nearExpiryDays", 30)) : -1;
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(inventoryClient.getAlertSummary(lowAt, nearDays));
		out.put("lowAt", lowAt);
		out.put("nearDays", nearDays);
		out.put("trackExpiry", trackExpiry);
		// Names for the listed items, in ONE catalog call (the cached read is right for a screen painting names).
		java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
		for (String k : new String[] { "lowItems", "expiredItems", "expiringItems" }) {
			Object v = out.get(k);
			if (v instanceof java.util.List<?> l) for (Object o : l)
				if (o instanceof java.util.Map<?, ?> m && m.get("productId") instanceof Number n) ids.add(n.longValue());
		}
		if (!ids.isEmpty()) {
			try {
				java.util.Map<Long, String> names = new java.util.HashMap<>();
				for (com.myplus.commerce.contracts.dto.ProductRef r : catalogClient.getProducts(new java.util.ArrayList<>(ids)))
					if (r != null && r.getId() != null) names.put(r.getId(), r.getName());
				for (String k : new String[] { "lowItems", "expiredItems", "expiringItems" }) {
					Object v = out.get(k);
					if (v instanceof java.util.List<?> l) for (Object o : l)
						if (o instanceof java.util.Map<?, ?> m && m.get("productId") instanceof Number n)
							((java.util.Map<String, Object>) m).put("name", names.getOrDefault(n.longValue(), "#" + n));
				}
			} catch (Exception e) {
				LOGGER.warn("STK-ALERT: product names unavailable; ids shown", e);   // the counts still stand
			}
		}
		return out;
	}

	@Autowired
	private com.myplus.business_service.service.PurchaseService purchaseService;   // PR-3b: per-batch pricing needs the batches

	/**
	 * The sell/dispense screen pre-fill, keyed by the catalog productId: on-hand + FEFO batches from inventory,
	 * sell price + description from the catalog Product master. Same StockDTO shape the sell handler reuses.
	 */
	@RequestMapping(value = "/productStock", method = RequestMethod.GET)
	@ResponseBody
	public StockDTO productStock(@RequestParam final Long productId) {
		StockDTO dto = new StockDTO();
		dto.setBpurchaseDiscountType("%");
		dto.setBpurchaseDiscount(java.math.BigDecimal.ZERO);
		// Sell discount defaults: amount type ("0") + zero, so the sell form's pre-fill matches the "0" (amount)
		// option and the type is never left as the ambiguous "%" that made a flat discount resolve as a percent.
		dto.setBsellDiscountType("0");
		dto.setBsellDiscount(java.math.BigDecimal.ZERO);
		dto.setStock(java.math.BigDecimal.ZERO);
		if (appUtil.isEmptyOrNull(productId)) return dto;
		try {
			Float invStock = inventoryClient.getStockLevel(productId);
			if (invStock != null) dto.setStock(new java.math.BigDecimal(Float.toString(invStock)));   // NUM-1: inventory's read API is still Float (NUM-1b); its shortest decimal form is exact                          // on-hand
			com.myplus.commerce.contracts.dto.ProductRef p = catalogClient.getProduct(productId);
			if (p != null) {
				if (p.getSellingPrice() != null) dto.setBsellRate(p.getSellingPrice());   // sell price
				dto.setIDesc(p.getDescription() != null ? p.getDescription() : "");        // description
			}
			/*
			 * #22 — the FEFO batch read is SKIPPED for a tenant that tracks neither batches nor expiry.
			 *
			 * This is a per-item-selection call on the till's hot path: a cashier ringing ten lines pays it
			 * ten times, and it was a THIRD cross-service round trip after the stock level and the catalog
			 * read. A mobile shop or a general POS has no batches and no expiry dates, so every one of those
			 * trips returned data the screen could not use.
			 *
			 * Gated on the CAPABILITY rather than on a flag the client sends, so it is decided by the tenant's
			 * configuration and cannot be turned on by a caller. The pharmacy and distribution verticals, which
			 * genuinely dispense by batch, are unaffected.
			 *
			 * Correctness is untouched either way: FEFO allocation happens server-side at submit. This is the
			 * screen's PRE-FILL hint, not the rule that decides which batch leaves the shelf.
			 */
			// PR-3b: a business selling PER BATCH needs the batches whatever it tracks — each carries its own price.
			boolean tracksBatches =
					capabilityService.isEnabled(com.myplus.common.settings.Capability.BATCH_TRACKING)
					|| capabilityService.isEnabled(com.myplus.common.settings.Capability.EXPIRY_TRACKING)
					|| com.myplus.business_service.service.PurchaseService.PRICE_MODE_PER_BATCH.equals(purchaseService.purchasePriceMode());
			if (tracksBatches) {
				java.util.List<com.myplus.commerce.contracts.dto.StockBatch> batches = inventoryClient.getBatches(productId);
				// PB-OLD — in Per batch, a batch with no price of its own shows (and the till fills) its OWN bill's sale
				// rate: the same rule the sale prices it by (SagaSellService#fillBillPrices), so screen and charge agree.
				if (batches != null && !batches.isEmpty()
						&& com.myplus.business_service.service.PurchaseService.PRICE_MODE_PER_BATCH.equals(purchaseService.purchasePriceMode())) {
					java.util.Set<Long> ids = new java.util.HashSet<>();
					for (var b : batches) if (b.getSellPrice() == null && b.getStockEntryId() != null) ids.add(b.getStockEntryId());
					if (!ids.isEmpty()) {
						java.util.Map<Long, java.math.BigDecimal> rates = purchaseService.billSellRates(ids);
						for (var b : batches)
							if (b.getSellPrice() == null && b.getStockEntryId() != null && rates.containsKey(b.getStockEntryId()))
								b.setSellPrice(rates.get(b.getStockEntryId()));
					}
				}
				dto.setBatches(batches);
				if (batches != null && !batches.isEmpty()) {
					com.myplus.commerce.contracts.dto.StockBatch first = batches.get(0);
					dto.setBatchNo(first.getBatchNo());
					dto.setBexpDate(first.getExpiryDate() != null ? first.getExpiryDate().toString() : null);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("productStock lookup failed for product {}", productId, e);
		}
		return dto;
	}

	/**
	 * The purchase screen's "pick an existing batch" pre-fill, keyed by productId: on-hand + expiry + last purchase
	 * rate for the batch from inventory, sell rate from the catalog Product master. (No local Stock, no itemId.)
	 */
	@RequestMapping(value = "/getStockByBatch", method = RequestMethod.GET)
	@ResponseBody
	public StockDTO getStockByBatch(@RequestParam final String batchNo, @RequestParam(required = false) final Long productId) {
		StockDTO dto = new StockDTO();
		dto.setBatchNo(batchNo);
		if (appUtil.isEmptyOrNull(batchNo) || appUtil.isEmptyOrNull(productId)) return dto;
		try {
			for (com.myplus.commerce.contracts.dto.StockBatch b : inventoryClient.getBatches(productId)) {
				if (batchNo.equals(b.getBatchNo())) {
					if (b.getAvailable() != null) dto.setStock(b.getAvailable());   // NUM-1: exact (was floatValue)
					if (b.getExpiryDate() != null) dto.setBexpDate(b.getExpiryDate().toString());
					if (b.getPurchasePrice() != null) dto.setBpurchaseRate(b.getPurchasePrice());   // last purchase rate
					break;
				}
			}
			com.myplus.commerce.contracts.dto.ProductRef p = catalogClient.getProduct(productId);
			if (p != null && p.getSellingPrice() != null) dto.setBsellRate(p.getSellingPrice());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getStockByBatch " + e.getCause(), e);
		}
		return dto;
	}

}
