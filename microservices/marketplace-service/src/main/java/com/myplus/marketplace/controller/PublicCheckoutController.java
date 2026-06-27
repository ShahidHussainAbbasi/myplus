package com.myplus.marketplace.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.CheckoutDTO;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public storefront checkout (slice 69, E5). Anonymous — reached at {@code /api/marketplace/public/checkout} (gateway
 * allow-lists {@code /api/marketplace/public/}; StripPrefix=2 → service sees {@code /public/checkout}). Totals are
 * server-computed from the cart; the client supplies only the cart token + shipping/contact choices.
 */
@RestController
@RequestMapping("/public/checkout")
@RequiredArgsConstructor
public class PublicCheckoutController {

    private final CheckoutService checkoutService;

    /** Live totals breakdown for the current cart + chosen shipping method (no order placed). */
    @GetMapping("/quote")
    public ApiResponse<CheckoutDTO.Quote> quote(@RequestParam Long organizationId,
                                                @RequestParam(required = false) String cartToken,
                                                @RequestParam(required = false) String shippingMethod) {
        return ApiResponse.success(checkoutService.quote(organizationId, cartToken, shippingMethod));
    }

    /** Place the order from the server cart (reserve→charge→confirm saga). */
    @PostMapping
    public ApiResponse<OrderDTO> place(@RequestBody CheckoutDTO.Request req) {
        return ApiResponse.success(checkoutService.place(req), "Order placed");
    }
}
