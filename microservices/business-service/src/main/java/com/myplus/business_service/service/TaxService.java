package com.myplus.business_service.service;

import com.myplus.business_service.dto.TaxSettingDTO;
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
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

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
        return taxSettingRepo.save(s);
    }

    /** Resolve the rate to apply: the product's own rate, else the org default. Never null/negative. */
    public static BigDecimal resolveRate(BigDecimal productRate, TaxSetting setting) {
        BigDecimal rate = (productRate != null && productRate.signum() > 0)
                ? productRate
                : (setting != null && setting.getDefaultRate() != null ? setting.getDefaultRate() : BigDecimal.ZERO);
        return rate.signum() < 0 ? BigDecimal.ZERO : rate;
    }

    /** Compute tax for one line given the line amount (after discount), the rate (%) and the mode. */
    public static TaxResult compute(BigDecimal lineAmount, BigDecimal rate, TaxMode mode) {
        BigDecimal amount = lineAmount != null ? lineAmount : BigDecimal.ZERO;
        BigDecimal r = (rate != null && rate.signum() > 0) ? rate : BigDecimal.ZERO;
        if (r.signum() == 0) {
            BigDecimal net = scale(amount);
            return new TaxResult(net, BigDecimal.ZERO, BigDecimal.ZERO, net);
        }
        if (mode == TaxMode.INCLUSIVE) {
            // price already includes tax: net = gross / (1 + r/100); tax = gross - net
            BigDecimal gross = scale(amount);
            BigDecimal divisor = BigDecimal.ONE.add(r.divide(HUNDRED, 6, RoundingMode.HALF_UP));
            BigDecimal net = gross.divide(divisor, SCALE, RoundingMode.HALF_UP);
            return new TaxResult(net, r, gross.subtract(net), gross);
        }
        // EXCLUSIVE: tax on top
        BigDecimal net = scale(amount);
        BigDecimal tax = net.multiply(r).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        return new TaxResult(net, r, tax, net.add(tax));
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
