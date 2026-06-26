package com.myplus.marketplace.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.StorefrontCustomer;
import com.myplus.marketplace.service.CustomerAccountService;
import com.myplus.marketplace.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Storefront shopper accounts (slice 61, E4) — anonymous register/login + own order history. Reached at
 * {@code /api/marketplace/public/customer/**} (gateway allow-lists {@code /api/marketplace/public/}; StripPrefix=2
 * → service sees {@code /public/customer/**}). Store-scoped via organizationId in the body.
 */
@RestController
@RequestMapping("/public/customer")
@RequiredArgsConstructor
public class PublicCustomerController {

    private final CustomerAccountService accounts;
    private final OrderService orderService;

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(accounts.register(asLong(body.get("organizationId")),
                str(body.get("email")), str(body.get("password")), str(body.get("name"))), "Registered");
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(accounts.login(asLong(body.get("organizationId")),
                str(body.get("email")), str(body.get("password"))), "Signed in");
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderDTO>> orders(@RequestParam String token) {
        StorefrontCustomer c = accounts.authenticate(token);
        return ApiResponse.success(orderService.listForCustomer(c.getId()));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
