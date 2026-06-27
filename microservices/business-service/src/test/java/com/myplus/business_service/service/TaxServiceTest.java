package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.myplus.business_service.dto.TaxSettingDTO;
import com.myplus.business_service.entity.TaxMode;
import com.myplus.business_service.entity.TaxSetting;

import org.junit.jupiter.api.Test;

/**
 * G3 tax engine (slice 35) — pure math + rate resolution, always runs (no Spring/DB). EXCLUSIVE adds on top;
 * INCLUSIVE backs the tax out of a price that already includes it; the two are inverses for the same rate.
 */
class TaxServiceTest {

    private static final BigDecimal RATE17 = new BigDecimal("17.00");

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void exclusive_adds_tax_on_top() {
        TaxResult r = TaxService.compute(bd("100.00"), RATE17, TaxMode.EXCLUSIVE);
        assertThat(r.net()).isEqualByComparingTo("100.00");
        assertThat(r.tax()).isEqualByComparingTo("17.00");
        assertThat(r.gross()).isEqualByComparingTo("117.00");
    }

    @Test
    void inclusive_backs_tax_out_of_the_price() {
        TaxResult r = TaxService.compute(bd("117.00"), RATE17, TaxMode.INCLUSIVE);
        assertThat(r.net()).isEqualByComparingTo("100.00");
        assertThat(r.tax()).isEqualByComparingTo("17.00");
        assertThat(r.gross()).isEqualByComparingTo("117.00");
    }

    @Test
    void zero_rate_is_all_net_no_tax() {
        TaxResult r = TaxService.compute(bd("100.00"), BigDecimal.ZERO, TaxMode.EXCLUSIVE);
        assertThat(r.tax()).isEqualByComparingTo("0.00");
        assertThat(r.gross()).isEqualByComparingTo("100.00");
    }

    @Test
    void rounds_half_up_to_two_places() {
        // 49.99 @ 17% = 8.4983 -> 8.50
        TaxResult r = TaxService.compute(bd("49.99"), RATE17, TaxMode.EXCLUSIVE);
        assertThat(r.tax()).isEqualByComparingTo("8.50");
        assertThat(r.gross()).isEqualByComparingTo("58.49");
    }

    @Test
    void resolve_rate_prefers_product_then_org_default() {
        TaxSetting setting = TaxSetting.builder().defaultRate(bd("5.00")).build();
        assertThat(TaxService.resolveRate(bd("12.00"), setting)).isEqualByComparingTo("12.00"); // product wins
        assertThat(TaxService.resolveRate(null, setting)).isEqualByComparingTo("5.00");          // falls back
        assertThat(TaxService.resolveRate(BigDecimal.ZERO, setting)).isEqualByComparingTo("5.00");
    }

    @Test
    void disabled_tax_yields_zero_even_with_a_rate() {
        TaxService svc = new TaxService(null);   // taxForLine does not touch the repo
        TaxSetting off = TaxSetting.builder().enabled(false).taxMode(TaxMode.EXCLUSIVE).defaultRate(RATE17).build();
        TaxResult r = svc.taxForLine(bd("100.00"), RATE17, off);
        assertThat(r.tax()).isEqualByComparingTo("0.00");
        assertThat(r.gross()).isEqualByComparingTo("100.00");
    }

    @Test
    void enabled_tax_applies_the_mode() {
        TaxService svc = new TaxService(null);
        TaxSetting inclusive = TaxSetting.builder().enabled(true).taxMode(TaxMode.INCLUSIVE).defaultRate(BigDecimal.ZERO).build();
        TaxResult r = svc.taxForLine(bd("117.00"), RATE17, inclusive);  // product rate used
        assertThat(r.net()).isEqualByComparingTo("100.00");
        assertThat(r.tax()).isEqualByComparingTo("17.00");
    }

    @Test
    void dto_field_present_compiles() {   // guards the DTO used by the controller
        TaxSettingDTO dto = new TaxSettingDTO();
        dto.setTaxMode("INCLUSIVE");
        assertThat(dto.getTaxMode()).isEqualTo("INCLUSIVE");
    }
}
