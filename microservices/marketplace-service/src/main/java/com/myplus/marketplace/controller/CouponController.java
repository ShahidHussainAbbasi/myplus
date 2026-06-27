package com.myplus.marketplace.controller;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.CouponDTO;
import com.myplus.marketplace.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Coupon back-office (E13, slice 72). Mapped at {@code /coupons} → {@code /api/marketplace/coupons} via the gateway
 * (StripPrefix=2). Tenant-scoped via CurrentUser.
 */
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    public ApiResponse<CouponDTO> create(@RequestBody CouponDTO dto) {
        return ApiResponse.success(couponService.create(dto, CurrentUser.organizationId()), "Coupon created");
    }

    @GetMapping
    public ApiResponse<List<CouponDTO>> list() {
        return ApiResponse.success(couponService.list(CurrentUser.organizationId()));
    }
}
