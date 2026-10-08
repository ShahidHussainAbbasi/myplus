package com.myplus.marketplace.multiseller.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceShortageService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2b — a part a seller did not fulfil. Rules live in {@link MarketplaceShortageService}.
 *
 * <pre>
 *   POST /public/mkt/orders/{orderNo}/shortages/{id}/decision   anonymous: {phone, accept} — the shopper's answer
 *   POST /mkt/seller/shortages/{id}/dispute                     seller: {note} — disputes the cause recorded against it
 *   GET  /mkt/operator/shortages?status=&amp;page=&amp;size=          operator: RECORDED, DISPUTED, UPHELD, OVERTURNED, or all
 *   POST /mkt/operator/shortages/{id}/decide                    operator: {outcome: UPHELD|OVERTURNED, note}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceShortageController {

    private final MarketplaceShortageService shortages;

    @PostMapping("/public/mkt/orders/{orderNo}/shortages/{id}/decision")
    public ApiResponse<MarketplaceOrderDTOs.OrderView> decide(@PathVariable String orderNo, @PathVariable Long id,
            @RequestBody(required = false) MarketplaceOrderDTOs.ShortageDecision body) {
        MarketplaceOrderDTOs.OrderView v = shortages.decide(orderNo, id, body);
        return ApiResponse.success(v, Boolean.TRUE.equals(body == null ? null : body.accept())
                ? "Accepted. The new seller is asked to confirm." : "Declined. Your money for this part is returned.");
    }

    @PostMapping("/mkt/seller/shortages/{id}/dispute")
    public ApiResponse<MarketplaceOrderDTOs.SellerShortageView> dispute(@PathVariable Long id,
            @RequestBody(required = false) MarketplaceOrderDTOs.DisputeRequest body) {
        return ApiResponse.success(shortages.dispute(id, body), "Disputed. MaxTheService will review it and tell you.");
    }

    @GetMapping("/mkt/operator/shortages")
    public ApiResponse<PageResponse<MarketplaceOrderDTOs.OperatorShortageView>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(shortages.operatorList(status, page, size));
    }

    @PostMapping("/mkt/operator/shortages/{id}/decide")
    public ApiResponse<MarketplaceOrderDTOs.OperatorShortageView> decide(@PathVariable Long id,
            @RequestBody(required = false) MarketplaceOrderDTOs.ShortageRuling body) {
        return ApiResponse.success(shortages.rule(id, body), "Decided. The seller sees the outcome.");
    }
}
