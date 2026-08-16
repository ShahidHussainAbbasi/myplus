package com.myplus.business_service.service;

import com.myplus.business_service.dto.TaxSettingDTO;
import com.myplus.commerce.domain.TaxMath;
import com.myplus.business_service.entity.TaxMode;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.business_service.repository.TaxSettingRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tax engine (G3, slice 35). Pure math ({@link #compute}) + rate resolution ({@link #resolveRate}) are static and
 * unit-testable without Spring; {@link #settingsFor} loads the per-org policy. EXCLUSIVE adds tax on top of the
 * line amount; INCLUSIVE backs the tax out of a price that already includes it. Money is BigDecimal, scale 2,
 * HALF_UP.
 */
@Service
@RequiredArgsConstructor
public class TaxService {

    static final int SCALE = 2;

    private final TaxSettingRepo taxSettingRepo;

    /** The org's tax policy, or a disabled default when none is configured. */
    public TaxSetting settingsFor(Long orgId) {
        return taxSettingRepo.findByOrganizationId(orgId)
                .orElseGet(() -> TaxSetting.builder().organizationId(orgId).enabled(false).build());
    }

    /** Upsert the org's tax policy (one row per tenant). */
    @Transactional
    public TaxSetting saveSetting(Long orgId, Long userId, TaxSettingDTO dto) {
        TaxSetting s = taxSettingRepo.findByOrganizationId(orgId)
                .orElseGet(() -> TaxSetting.builder().organizationId(orgId).build());
        s.setUserId(userId);
        s.setTaxMode("INCLUSIVE".equalsIgnoreCase(dto.getTaxMode()) ? TaxMode.INCLUSIVE : TaxMode.EXCLUSIVE);
        s.setDefaultRate(dto.getDefaultRate() != null ? dto.getDefaultRate() : BigDecimal.ZERO);
        s.setTaxLabel(dto.getTaxLabel() != null && !dto.getTaxLabel().isBlank() ? dto.getTaxLabel().trim() : "Tax");
        s.setTaxRegNo(dto.getTaxRegNo());
        s.setEnabled(Boolean.TRUE.equals(dto.getEnabled()));
        s.setInputTaxEnabled(Boolean.TRUE.equals(dto.getInputTaxEnabled()));   // Phase B: Purchase tax toggle
        return taxSettingRepo.save(s);
    }

    // ── The arithmetic now lives ONCE, in commerce-domain's TaxMath ──────────────────────────────────
    //
    // These two stay as the service's public API (SagaSellService and the purchase paths call them) but
    // they no longer own the maths. The storefront checkout used to carry its own copy of this logic and
    // drifted from it — no tenant switch, no org default, no INCLUSIVE branch — so a shop with tax OFF was
    // quoted a taxed total its own invoice then contradicted. A duplicated RULE is worse than a duplicated
    // function: with one implementation behind both, the books and every channel cannot disagree again.

    /** Resolve the rate to apply: the product's own rate, else the org default. Never null/negative. */
    public static BigDecimal resolveRate(BigDecimal productRate, TaxSetting setting) {
        return TaxMath.resolveRate(productRate, setting != null ? setting.getDefaultRate() : null);
    }

    /** Compute tax for one line given the line amount (after discount), the rate (%) and the mode. */
    public static TaxResult compute(BigDecimal lineAmount, BigDecimal rate, TaxMode mode) {
        TaxMath.TaxAmounts a = TaxMath.compute(lineAmount, rate, mode == TaxMode.INCLUSIVE);
        return new TaxResult(a.net(), a.rate(), a.tax(), a.gross());
    }

    /** Line tax honouring the org switch: when tax is disabled, the whole amount is net with zero tax. */
    public TaxResult taxForLine(BigDecimal lineAmount, BigDecimal productRate, TaxSetting setting) {
        if (setting == null || !Boolean.TRUE.equals(setting.getEnabled())) {
            BigDecimal net = scale(lineAmount != null ? lineAmount : BigDecimal.ZERO);
            return new TaxResult(net, BigDecimal.ZERO, BigDecimal.ZERO, net);
        }
        return compute(lineAmount, resolveRate(productRate, setting), setting.getTaxMode());
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
