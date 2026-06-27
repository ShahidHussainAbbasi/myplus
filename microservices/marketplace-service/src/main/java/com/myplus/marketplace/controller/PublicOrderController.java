package com.myplus.marketplace.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.dto.OrderTrackDTO;
import com.myplus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Public order tracking (slice 56) — look up an order's status by ref + contact (no account). */
    @GetMapping("/order/track")
    public ApiResponse<OrderTrackDTO> track(@RequestParam Long ref, @RequestParam String contact) {
        return ApiResponse.success(orderService.trackPublic(ref, contact));
    }

    /** Public return request (E10, slice 71) — verified by order ref + contact; only a delivered order. */
    @PostMapping("/order/return")
    public ApiResponse<OrderTrackDTO> requestReturn(@RequestBody java.util.Map<String, Object> body) {
        Long ref = body.get("ref") == null ? null : Long.valueOf(body.get("ref").toString());
        String contact = body.get("contact") == null ? null : body.get("contact").toString();
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        return ApiResponse.success(orderService.requestReturn(ref, contact, reason), "Return requested");
    }
}
