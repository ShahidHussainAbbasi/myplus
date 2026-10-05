package com.myplus.business_service.controller;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.service.PurchaseService;
import com.myplus.business_service.service.pricing.MarkupPolicy;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;

import lombok.RequiredArgsConstructor;

/**
 * PR-2 — what the markup rule suggests for one product at one purchase cost, for the purchase form.
 *
 * <p>The form never computes a price itself: it asks here, so its suggestion and what Auto sets on save come from the
 * same {@link MarkupPolicy}. The product is read through catalog's scoped lookup, so another tenant's product id is
 * not found rather than priced. Whoever records purchases may ask — they already see the cost they typed.
 */
@RestController
@RequiredArgsConstructor
public class PurchasePricingController {

    private final MarkupPolicy markupPolicy;
    private final CatalogClient catalogClient;
    private final PurchaseService purchaseService;

    @GetMapping("/suggestedPrice")
    public GenericResponse suggestedPrice(@RequestParam("productId") Long productId,
                                          @RequestParam(name = "cost", required = false) BigDecimal cost) {
        ProductRef ref;
        try {
            ref = catalogClient.getProduct(productId);
        } catch (RuntimeException notFound) {
            return new GenericResponse("ERROR", "Product not found");
        }
        if (ref == null) return new GenericResponse("ERROR", "Product not found");
        MarkupPolicy.Suggestion s = markupPolicy.suggest(cost, ref.getMarkupPct(), ref.getSellingPrice());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", s.mode());
        out.put("purchaseMode", purchaseService.purchasePriceMode());
        out.put("basis", s.basis());
        out.put("rounding", s.rounding());
        out.put("pct", s.pct());
        out.put("pctSource", s.pctSource());
        out.put("cost", s.cost());
        out.put("raw", s.raw());
        out.put("price", s.price());
        out.put("current", s.current());
        out.put("guard", s.guard());
        out.put("autoApplies", s.autoApplies()
                && !PurchaseService.PRICE_MODE_KEEP.equals(purchaseService.purchasePriceMode()));
        return new GenericResponse("SUCCESS", "Suggested price", out);
    }
}
