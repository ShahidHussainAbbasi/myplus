package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The report's group-by dimensions (slice b2b-P3e-2 = requirement #6).
 *
 * <p>Each constant carries its own key extractor, so adding a dimension is one enum constant rather than
 * another branch in the controller. Pure — no Spring, no database — so the aggregation rules are unit
 * tested directly.
 */
public enum SaleReportGrouping {

    /** Sales per day — the most common question a shop asks of a month. */
    DAY(row -> firstTen(row.getDated())),

    /** Sales per month, for comparing periods. */
    MONTH(row -> firstSeven(row.getDated())),

    CUSTOMER(row -> blankToDash(row.getCn())),

    PRODUCT(row -> blankToDash(row.getItemName())),

    CATEGORY(row -> blankToDash(row.getCategory())),

    /** B2B vs B2C — the channel split Phase 0 introduced. */
    CHANNEL(row -> blankToDash(row.getCustomerType()));

    private final Function<SellDTO, String> keyOf;

    SaleReportGrouping(Function<SellDTO, String> keyOf) {
        this.keyOf = keyOf;
    }

    /** Parse a request value; unknown or blank means "no grouping". */
    public static SaleReportGrouping from(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        for (SaleReportGrouping g : values()) {
            if (g.name().equalsIgnoreCase(value.trim())) return g;
        }
        return null;   // an unrecognised value must not blow up a report — it just means ungrouped
    }

    /**
     * Aggregate rows into subtotals, preserving the order groups first appear so the output follows the
     * report's own ordering rather than an arbitrary hash order.
     */
    public List<SaleReportGroup> aggregate(List<SellDTO> rows) {
        Map<String, SaleReportGroup> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> invoicesByKey = new LinkedHashMap<>();

        for (SellDTO row : rows) {
            if (row == null) continue;
            String key = keyOf.apply(row);
            SaleReportGroup g = byKey.computeIfAbsent(key,
                    k -> new SaleReportGroup(k, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

            g.setQuantity(g.getQuantity().add(BigDecimal.valueOf(
                    row.getQuantity() == null ? 0d : row.getQuantity().doubleValue())));
            g.setTotal(g.getTotal().add(nz(row.getTotalAmount())));
            g.setTax(g.getTax().add(nz(row.getTaxAmount())));

            // Count DISTINCT invoices: a three-line sale is one transaction, not three.
            if (row.getInvoiceNo() != null && !row.getInvoiceNo().isEmpty()) {
                invoicesByKey.computeIfAbsent(key, k -> new HashSet<>()).add(row.getInvoiceNo());
                g.setInvoices(invoicesByKey.get(key).size());
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToDash(String s) {
        return s == null || s.trim().isEmpty() ? "—" : s;
    }

    /** yyyy-MM-dd from a rendered date, without parsing — the report already formats it. */
    private static String firstTen(String dated) {
        return dated == null || dated.length() < 10 ? blankToDash(dated) : dated.substring(0, 10);
    }

    /** yyyy-MM from a rendered date. */
    private static String firstSeven(String dated) {
        return dated == null || dated.length() < 7 ? blankToDash(dated) : dated.substring(0, 7);
    }
}
