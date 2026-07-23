package com.myplus.business_service.service;

import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.SellRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Multi-rate tax: the per-rate tax breakdown for filing ("taxable @18% = X, tax = Y; @5% = …"). Sourced from the
 * transactional sell + purchase lines (which carry {@code taxRate}/{@code taxAmount}) grouped by rate over a period —
 * the GL posts aggregate tax to one TAX account and has no per-rate detail. Tenant-scoped (org + NULL-fallback). The
 * finance GL-sourced register remains the authoritative net-payable; this is the indicative per-rate split beneath it.
 */
@Service
@RequiredArgsConstructor
public class TaxBreakdownService {

    private final SellRepo sellRepo;
    private final PurchaseRepo purchaseRepo;
    private final RequestUtil requestUtil;

    private static final int OUT_TAXABLE = 0, OUT_TAX = 1, IN_TAXABLE = 2, IN_TAX = 3;

    @Transactional(readOnly = true)
    public Map<String, Object> breakdown(LocalDate from, LocalDate to) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        Long org = u != null ? u.getOrganizationId() : null;
        Long user = u != null ? u.getUserId() : null;
        LocalDate fromD = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate toD = to != null ? to : LocalDate.now();
        LocalDateTime f = fromD.atStartOfDay();
        LocalDateTime t = toD.atTime(23, 59, 59);

        // rate -> [outputTaxable, outputTax, inputTaxable, inputTax]. TreeMap → rows sorted by rate ascending.
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        for (Object[] r : sellRepo.taxBreakdownByRate(f, t, org, user))
            accumulate(byRate, rate(r[0]), OUT_TAXABLE, money(r[1]), OUT_TAX, money(r[2]));
        for (Object[] r : purchaseRepo.taxBreakdownByRate(f, t, org, user))
            accumulate(byRate, rate(r[0]), IN_TAXABLE, money(r[1]), IN_TAX, money(r[2]));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totOutTaxable = BigDecimal.ZERO, totOutTax = BigDecimal.ZERO,
                totInTaxable = BigDecimal.ZERO, totInTax = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal[]> e : byRate.entrySet()) {
            BigDecimal[] v = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rate", e.getKey());
            row.put("outputTaxable", v[OUT_TAXABLE]);
            row.put("outputTax", v[OUT_TAX]);
            row.put("inputTaxable", v[IN_TAXABLE]);
            row.put("inputTax", v[IN_TAX]);
            row.put("netTax", v[OUT_TAX].subtract(v[IN_TAX]));
            rows.add(row);
            totOutTaxable = totOutTaxable.add(v[OUT_TAXABLE]);
            totOutTax = totOutTax.add(v[OUT_TAX]);
            totInTaxable = totInTaxable.add(v[IN_TAXABLE]);
            totInTax = totInTax.add(v[IN_TAX]);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromD.toString());
        out.put("to", toD.toString());
        out.put("rows", rows);
        out.put("totalOutputTaxable", totOutTaxable);
        out.put("totalOutputTax", totOutTax);
        out.put("totalInputTaxable", totInTaxable);
        out.put("totalInputTax", totInTax);
        out.put("netPayable", totOutTax.subtract(totInTax));
        return out;
    }

    private static void accumulate(Map<BigDecimal, BigDecimal[]> byRate, BigDecimal rate,
                                   int taxableIdx, BigDecimal taxable, int taxIdx, BigDecimal tax) {
        BigDecimal[] v = byRate.computeIfAbsent(rate,
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        v[taxableIdx] = v[taxableIdx].add(taxable);
        v[taxIdx] = v[taxIdx].add(tax);
    }

    private static BigDecimal rate(Object o) { return o != null ? (BigDecimal) o : BigDecimal.ZERO; }
    private static BigDecimal money(Object o) { return o != null ? (BigDecimal) o : BigDecimal.ZERO; }
}
