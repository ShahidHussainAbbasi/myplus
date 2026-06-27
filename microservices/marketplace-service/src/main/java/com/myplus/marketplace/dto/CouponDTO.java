package com.myplus.marketplace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Coupon create/list payload (slice 72, E13). */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CouponDTO {
    private Long id;
    private String code;
    private String type;            // PERCENT | FIXED
    private BigDecimal value;
    private BigDecimal minSpend;
    private Boolean active;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Integer usageLimit;
    private Integer usedCount;
}
