package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import com.myplus.marketplace.entity.Coupon;
import com.myplus.marketplace.repository.CouponRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 72, E13 — pure Mockito. Coupon validate/compute: PERCENT + FIXED, min-spend / active / expiry / usage-limit
 * gates, unknown code → no discount.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    private static final Long ORG = 1L;

    @Mock private CouponRepository repo;
    @InjectMocks private CouponService service;

    private Coupon coupon(String type, String value) {
        return Coupon.builder().id(7L).organizationId(ORG).code("SAVE").type(type)
                .value(new BigDecimal(value)).active(Boolean.TRUE).usedCount(0).build();
    }

    @Test
    void percent_coupon_discounts_the_subtotal() {
        when(repo.findByOrganizationIdAndCode(ORG, "SAVE")).thenReturn(Optional.of(coupon("PERCENT", "10")));
        var r = service.validateAndCompute(ORG, "save", new BigDecimal("200.00"));   // case-insensitive
        assertThat(r.discount()).isEqualByComparingTo("20.00");
        assertThat(r.message()).isNull();
        assertThat(r.couponId()).isEqualTo(7L);
    }

    @Test
    void fixed_coupon_is_capped_at_the_subtotal() {
        when(repo.findByOrganizationIdAndCode(ORG, "SAVE")).thenReturn(Optional.of(coupon("FIXED", "50")));
        var r = service.validateAndCompute(ORG, "SAVE", new BigDecimal("30.00"));    // 50 off but only 30 to give
        assertThat(r.discount()).isEqualByComparingTo("30.00");
    }

    @Test
    void min_spend_not_met_yields_no_discount_with_a_message() {
        Coupon c = coupon("FIXED", "10");
        c.setMinSpend(new BigDecimal("100.00"));
        when(repo.findByOrganizationIdAndCode(ORG, "SAVE")).thenReturn(Optional.of(c));
        var r = service.validateAndCompute(ORG, "SAVE", new BigDecimal("50.00"));
        assertThat(r.discount()).isEqualByComparingTo("0");
        assertThat(r.message()).contains("Spend at least");
    }

    @Test
    void expired_coupon_is_rejected() {
        Coupon c = coupon("PERCENT", "10");
        c.setEndsAt(LocalDateTime.now().minusDays(1));
        when(repo.findByOrganizationIdAndCode(ORG, "SAVE")).thenReturn(Optional.of(c));
        var r = service.validateAndCompute(ORG, "SAVE", new BigDecimal("100.00"));
        assertThat(r.discount()).isEqualByComparingTo("0");
        assertThat(r.message()).contains("expired");
    }

    @Test
    void usage_limit_reached_is_rejected() {
        Coupon c = coupon("PERCENT", "10");
        c.setUsageLimit(2);
        c.setUsedCount(2);
        when(repo.findByOrganizationIdAndCode(ORG, "SAVE")).thenReturn(Optional.of(c));
        var r = service.validateAndCompute(ORG, "SAVE", new BigDecimal("100.00"));
        assertThat(r.discount()).isEqualByComparingTo("0");
        assertThat(r.message()).contains("usage limit");
    }

    @Test
    void unknown_code_yields_no_discount() {
        when(repo.findByOrganizationIdAndCode(ORG, "NOPE")).thenReturn(Optional.empty());
        var r = service.validateAndCompute(ORG, "NOPE", new BigDecimal("100.00"));
        assertThat(r.discount()).isEqualByComparingTo("0");
        assertThat(r.message()).contains("Invalid");
    }

    @Test
    void blank_code_is_a_no_op() {
        var r = service.validateAndCompute(ORG, "  ", new BigDecimal("100.00"));
        assertThat(r.discount()).isEqualByComparingTo("0");
        assertThat(r.code()).isNull();
        assertThat(r.message()).isNull();
    }
}
