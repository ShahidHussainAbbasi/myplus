package com.myplus.marketplace.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.CartDTO;
import com.myplus.marketplace.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public storefront cart (slice 68, E3). Anonymous — reached at {@code /api/marketplace/public/cart} (gateway
 * allow-lists {@code /api/marketplace/public/}; StripPrefix=2 → service sees {@code /public/cart}). The store
 * (organizationId) + cartToken identify the cart since there's no JWT identity.
 */
@RestController
@RequestMapping("/public/cart")
@RequiredArgsConstructor
public class PublicCartController {

    private final CartService cartService;

    @GetMapping
    public ApiResponse<CartDTO> view(@RequestParam Long organizationId,
                                     @RequestParam(required = false) String cartToken,
                                     @RequestParam(required = false) String customerToken) {
        return ApiResponse.success(cartService.view(CartDTO.Request.builder()
                .organizationId(organizationId).cartToken(cartToken).customerToken(customerToken).build()));
    }

    @PostMapping("/add")
    public ApiResponse<CartDTO> add(@RequestBody CartDTO.Request req) {
        return ApiResponse.success(cartService.add(req), "Added to cart");
    }

    @PostMapping("/update")
    public ApiResponse<CartDTO> update(@RequestBody CartDTO.Request req) {
        return ApiResponse.success(cartService.update(req), "Cart updated");
    }

    @PostMapping("/remove")
    public ApiResponse<CartDTO> remove(@RequestBody CartDTO.Request req) {
        return ApiResponse.success(cartService.remove(req), "Removed from cart");
    }
}
