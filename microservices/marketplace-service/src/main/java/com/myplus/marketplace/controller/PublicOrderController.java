package com.myplus.marketplace.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public storefront checkout (slice 47) — anonymous guest order. Reached at {@code /api/marketplace/public/order}
 * (gateway allow-lists {@code /api/marketplace/public/}; SecurityConfig permits {@code /public/**}; StripPrefix=2 →
 * service sees {@code /public/order}). The store (organizationId) is in the body since there's no JWT identity.
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicOrderController {

    private final OrderService orderService;

    @PostMapping("/order")
    public ApiResponse<OrderDTO> placeOrder(@RequestBody OrderDTO dto) {
        return ApiResponse.success(orderService.placePublic(dto), "Order placed");
    }
}
