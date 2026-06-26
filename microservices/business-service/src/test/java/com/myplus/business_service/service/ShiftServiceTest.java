package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * POS day-close (slice 39) — pure cash math, always runs. expectedCash = float + cash sales + refunds(neg)
 * + pay-ins − pay-outs − drops; variance = counted − expected.
 */
class ShiftServiceTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void expected_cash_sums_float_sales_refunds_and_movements() {
        // float 100 + cash sales 500 + refunds -20 + payIns 50 - payOuts 30 - drops 200 = 400
        BigDecimal exp = ShiftService.expectedCash(bd("100"), bd("500"), bd("-20"), bd("50"), bd("30"), bd("200"));
        assertThat(exp).isEqualByComparingTo("400");
    }

    @Test
    void expected_cash_is_null_safe() {
        assertThat(ShiftService.expectedCash(null, null, null, null, null, null)).isEqualByComparingTo("0");
        assertThat(ShiftService.expectedCash(bd("100"), null, null, null, null, null)).isEqualByComparingTo("100");
    }

    @Test
    void variance_is_counted_minus_expected() {
        BigDecimal expected = ShiftService.expectedCash(bd("100"), bd("500"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal counted = bd("590");
        assertThat(counted.subtract(expected)).isEqualByComparingTo("-10");   // £10 short
    }

    @Test
    void card_and_wallet_do_not_affect_drawer_cash() {
        // only cash sales feed the drawer; a card-heavy day with float 100 + cash 0 = 100 expected
        assertThat(ShiftService.expectedCash(bd("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo("100");
    }
}
