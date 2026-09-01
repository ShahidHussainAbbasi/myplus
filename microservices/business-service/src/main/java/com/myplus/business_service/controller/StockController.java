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
		dto.setStock(0.0F);
		if (appUtil.isEmptyOrNull(productId)) return dto;
		try {
			Float invStock = inventoryClient.getStockLevel(productId);
			if (invStock != null) dto.setStock(invStock);                          // on-hand
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
			boolean tracksBatches =
					capabilityService.isEnabled(com.myplus.common.settings.Capability.BATCH_TRACKING)
					|| capabilityService.isEnabled(com.myplus.common.settings.Capability.EXPIRY_TRACKING);
			if (tracksBatches) {
				java.util.List<com.myplus.commerce.contracts.dto.StockBatch> batches = inventoryClient.getBatches(productId);
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
					if (b.getAvailable() != null) dto.setStock(b.getAvailable().floatValue());
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
