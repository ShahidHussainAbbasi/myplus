package com.myplus.marketplace.multiseller.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceCheckoutService;
import com.myplus.marketplace.multiseller.service.MarketplaceRoutingService;
import com.myplus.marketplace.multiseller.service.MarketplaceSettingsService;
import com.myplus.marketplace.multiseller.service.SellerOrderService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1e — marketplace orders. Rules live in the services.
 *
 * <pre>
 *   POST /public/mkt/checkout                         anonymous (gateway allow-lists /api/marketplace/public/)
 *   GET  /public/mkt/orders/{orderNo}?phone=          anonymous tracking: number AND phone
 *   GET  /mkt/seller-orders?status=&amp;page=&amp;size=       seller: own org's seller orders
 *   POST /mkt/seller-orders/{id}/accept               seller: within the window {version, serials}
 *   POST /mkt/seller-orders/{id}/reject               seller: {version, reason}
 *   GET  /mkt/operator/orders?status=                 operator
 *   GET/POST /mkt/operator/settings/accept-window     operator: minutes a seller has to accept; MKT-2a multi-seller checkout
 *   GET  /mkt/operator/routing                        operator (MKT-2c): limits, sellers not answering, the test switch
 *   POST /mkt/operator/routing/close                  operator: {sellerOrganizationId} — ask that seller again now
 *   POST /mkt/operator/routing/test                   operator: {sellerOrganizationId, delayMs} — test switch only
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceOrderController {

    private final MarketplaceCheckoutService checkout;
    private final SellerOrderService sellerOrders;
    private final MarketplaceSettingsService settings;
    /** MKT-2c. */
    private final MarketplaceRoutingService routing;

    @PostMapping("/public/mkt/checkout")
    public ApiResponse<MarketplaceOrderDTOs.OrderView> checkout(@RequestBody MarketplaceOrderDTOs.CheckoutRequest body) {
        MarketplaceOrderDTOs.OrderView v = checkout.checkout(body);
        return ApiResponse.success(v, "CANCELLED".equals(v.status()) ? v.cancelReason()
                : "Waiting for " + (v.sellerName() == null ? "the seller" : v.sellerName()) + " to confirm.");
    }

    @GetMapping("/public/mkt/orders/{orderNo}")
    public ApiResponse<MarketplaceOrderDTOs.OrderView> track(@PathVariable String orderNo,
            @RequestParam(required = false) String phone) {
        return ApiResponse.success(checkout.track(orderNo, phone));
    }

    @GetMapping("/mkt/seller-orders")
    public ApiResponse<PageResponse<MarketplaceOrderDTOs.SellerOrderView>> mine(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(sellerOrders.mine(status, page, size));
    }

    @PostMapping("/mkt/seller-orders/{id}/accept")
    public ApiResponse<MarketplaceOrderDTOs.SellerOrderView> accept(@PathVariable Long id,
            @RequestBody(required = false) MarketplaceOrderDTOs.AcceptRequest body) {
        MarketplaceOrderDTOs.SellerOrderView v = sellerOrders.accept(id, body);
        // MKT-1f: a card order was paid online (MKT-1e2) — telling the rider to collect cash would charge the customer twice.
        return ApiResponse.success(v, "CARD".equals(v.paymentMode())
                ? "Accepted. The sale is in your books. This order is PAID ONLINE: deliver it and do not collect cash."
                : "Accepted. The sale is in your books; deliver and collect the cash.");
    }

    @PostMapping("/mkt/seller-orders/{id}/reject")
    public ApiResponse<MarketplaceOrderDTOs.SellerOrderView> reject(@PathVariable Long id,
            @RequestBody(required = false) MarketplaceOrderDTOs.RejectRequest body) {
        return ApiResponse.success(sellerOrders.reject(id, body), "Rejected. The stock is released and the shopper is told.");
    }

    @GetMapping("/mkt/operator/orders")
    public ApiResponse<PageResponse<MarketplaceOrderDTOs.OrderView>> operatorOrders(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(checkout.operatorOrders(status, page, size));
    }

    @GetMapping("/mkt/operator/settings/accept-window")
    public ApiResponse<MarketplaceOrderDTOs.AcceptWindow> acceptWindow() {
        return ApiResponse.success(new MarketplaceOrderDTOs.AcceptWindow(settings.operatorAcceptMinutes(), settings.multiSeller(),
                settings.reroute()));
    }

    /** Each field is saved only when sent (MKT-2a added {@code multiSeller} to the same form). */
    @PostMapping("/mkt/operator/settings/accept-window")
    public ApiResponse<MarketplaceOrderDTOs.AcceptWindow> setAcceptWindow(@RequestBody MarketplaceOrderDTOs.AcceptWindow body) {
        if (body == null || (body.minutes() == null && body.multiSeller() == null && body.reroute() == null))
            settings.setAcceptMinutes(null);                                                          // refused in words
        int minutes = body.minutes() == null ? settings.operatorAcceptMinutes() : settings.setAcceptMinutes(body.minutes());
        boolean multi = body.multiSeller() == null ? settings.multiSeller() : settings.setMultiSeller(body.multiSeller());
        boolean reroute = body.reroute() == null ? settings.reroute() : settings.setReroute(body.reroute());
        return ApiResponse.success(new MarketplaceOrderDTOs.AcceptWindow(minutes, multi, reroute), "Order settings saved.");
    }

    // ── MKT-2-06: the acceptance window by the part's value ─────────────────────────────────────────────

    @GetMapping("/mkt/operator/settings/accept-tiers")
    public ApiResponse<MarketplaceOrderDTOs.AcceptTiers> acceptTiers() {
        return ApiResponse.success(tiersView(settings.operatorAcceptTiers()));
    }

    /** Body {tiers: [{above, minutes}]}: replaces the rules; an empty list removes them. */
    @PostMapping("/mkt/operator/settings/accept-tiers")
    public ApiResponse<MarketplaceOrderDTOs.AcceptTiers> setAcceptTiers(@RequestBody(required = false) MarketplaceOrderDTOs.AcceptTiers body) {
        List<com.myplus.marketplace.multiseller.domain.AcceptanceByValue.Tier> in = body == null || body.tiers() == null ? List.of()
                : body.tiers().stream().map(t -> t == null ? null : new com.myplus.marketplace.multiseller.domain.AcceptanceByValue.Tier(
                        t.above(), t.minutes() == null ? 0 : t.minutes())).toList();
        return ApiResponse.success(tiersView(settings.setAcceptTiers(in)), "Acceptance rules saved. They apply to orders placed from now on.");
    }

    private MarketplaceOrderDTOs.AcceptTiers tiersView(com.myplus.marketplace.multiseller.domain.AcceptanceByValue v) {
        return new MarketplaceOrderDTOs.AcceptTiers(settings.acceptMinutes(),
                v.tiers().stream().map(t -> new MarketplaceOrderDTOs.AcceptTier(t.above(), t.minutes())).toList());
    }

    // ── MKT-2c: live routing ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/mkt/operator/routing")
    public ApiResponse<MarketplaceOrderDTOs.RoutingView> routing() {
        return ApiResponse.success(routing.view());
    }

    @PostMapping("/mkt/operator/routing/close")
    public ApiResponse<MarketplaceOrderDTOs.RoutingView> closeCircuit(@RequestBody(required = false) MarketplaceOrderDTOs.RoutingClose body) {
        return ApiResponse.success(routing.close(body == null ? null : body.sellerOrganizationId()),
                "The seller will be asked again on its next order.");
    }

    @PostMapping("/mkt/operator/routing/test")
    public ApiResponse<MarketplaceOrderDTOs.RoutingView> routingTest(@RequestBody(required = false) MarketplaceOrderDTOs.RoutingTest body) {
        MarketplaceOrderDTOs.RoutingView v = routing.test(body);
        return ApiResponse.success(v, v.slowSellerOrganizationId() == null ? "Test slowness is off." : "Test slowness is on.");
    }
}
