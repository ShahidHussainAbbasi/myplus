package com.myplus.business_service.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    DAY(row -> dayKey(row.getDated())),

    /** Sales per month, for comparing periods. */
    MONTH(row -> monthKey(row.getDated())),

    CUSTOMER(row -> blankToDash(row.getCn())),

    PRODUCT(row -> blankToDash(row.getItemName())),

    CATEGORY(row -> blankToDash(row.getCategory())),

    /**
     * #18 — subtotals per manufacturer/COMPANY.
     *
     * <p>One enum constant, exactly as this type promises: the manufacturer already rides on every enriched
     * row from ProductRef, so grouping by it needs no new query and no join.
     */
    COMPANY(row -> blankToDash(row.getManufacturer())),

    /** B2B vs B2C — the channel split Phase 0 introduced. */
    CHANNEL(row -> blankToDash(row.getCustomerType())),

    /*
     * SRA-4a — sales by rep and area (design sra-sales-by-rep-and-area.md §7). A group with no name is a CODE, never "—":
     * "Not recorded" (a sale from before SRA-3) and "No rep" (a counter or portal sale) are different answers, and
     * the report must keep them apart or the gap it shows can never be chased.
     */
    REP(row -> row.getRepName() != null && !row.getRepName().isBlank() ? row.getRepName()
            : code(row.getSaleSource() == null || row.getSaleSource().isBlank() ? "NOT_RECORDED" : "NO_REP")),

    SOURCE(row -> code(row.getSaleSource() == null || row.getSaleSource().isBlank() ? "NOT_RECORDED" : row.getSaleSource())),

    KEYED_BY(row -> blankToDash(row.getKeyedBy())),

    AREA(row -> row.getArea() != null && !row.getArea().isBlank() ? row.getArea() : code("NO_AREA")),

    CITY(row -> row.getCity() != null && !row.getCity().isBlank() ? row.getCity() : code("NO_AREA")),

    SUPPLIER(row -> row.getSupplier() != null && !row.getSupplier().isBlank() ? row.getSupplier() : code("NO_SUPPLIER"));

    /** Marks a key as a code (see {@link SaleReportGroup#getLabelCode()}); a name typed by a person cannot start with it. */
    private static final String CODE = "\u0001";

    private static String code(String c) {
        return CODE + c;
    }

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
        return tree(rows, List.of(this), null);
    }

    /** SRA-4b: the group key of one line — a name, or a CODE-marked gap (see {@link #code}). */
    public String keyOf(SellDTO row) {
        return keyOf.apply(row);
    }

    /** The most levels the screen offers — deeper is unreadable on paper (design §7.2). */
    public static final int MAX_LEVELS = 4;

    /**
     * SRA-4b — "Rep, then Area, then Product": the request's comma list, unknown names dropped, a repeat dropped, at
     * most {@link #MAX_LEVELS}. Empty = ungrouped. A single name is exactly today's one-level grouping.
     */
    public static List<SaleReportGrouping> levels(String value) {
        List<SaleReportGrouping> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split(",")) {
            SaleReportGrouping g = from(part);
            if (g != null && !out.contains(g) && out.size() < MAX_LEVELS) out.add(g);
        }
        return out;
    }

    /**
     * SRA-4b — the grouped report as a TREE: one node per value of the first level, each holding the next level's
     * nodes, down to the last; every node carries the same measures, so each level adds up to the one above (that
     * is the gate's first check). With a column {@code split}, every node also carries one cell per split value —
     * the same rows, cut the other way. Groups keep the order they first appear in, as the one-level report did.
     *
     * <p>Pure arithmetic over rows already filtered and scoped by loadSR — no query, no visibility decision here.
     */
    public static List<SaleReportGroup> tree(List<SellDTO> rows, List<SaleReportGrouping> levels, SaleReportGrouping split) {
        if (levels == null || levels.isEmpty()) return new ArrayList<>();
        Map<String, List<SellDTO>> byKey = new LinkedHashMap<>();
        SaleReportGrouping level = levels.get(0);
        for (SellDTO row : rows) {
            if (row == null) continue;
            byKey.computeIfAbsent(level.keyOf(row), k -> new ArrayList<>()).add(row);
        }
        List<SaleReportGroup> out = new ArrayList<>();
        List<SaleReportGrouping> rest = levels.subList(1, levels.size());
        for (Map.Entry<String, List<SellDTO>> e : byKey.entrySet()) {
            SaleReportGroup g = measure(e.getKey(), e.getValue());
            g.setChildren(tree(e.getValue(), rest, split));
            if (split != null) {
                Map<String, List<SellDTO>> cells = new LinkedHashMap<>();
                for (SellDTO row : e.getValue()) cells.computeIfAbsent(split.keyOf(row), k -> new ArrayList<>()).add(row);
                List<SaleReportGroup> cols = new ArrayList<>();
                cells.forEach((k, v) -> cols.add(measure(k, v)));
                g.setCols(cols);
            }
            out.add(g);
        }
        return out;
    }

    /** One node's measures over its rows: quantity, money, free goods, and the DISTINCT invoices behind them. */
    private static SaleReportGroup measure(String key, List<SellDTO> rows) {
        SaleReportGroup g = new SaleReportGroup(key, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        if (key.startsWith(CODE)) { g.setLabel(null); g.setLabelCode(key.substring(CODE.length())); }
        Set<String> invoices = new java.util.TreeSet<>();
        for (SellDTO row : rows) {
            g.setQuantity(g.getQuantity().add(nz(row.getQuantity())));   // NUM-1: exact, no double hop
            g.setTotal(g.getTotal().add(nz(row.getTotalAmount())));
            g.setTax(g.getTax().add(nz(row.getTaxAmount())));
            g.setBonusQuantity(g.getBonusQuantity().add(nz(row.getBonusQuantity())));   // SRA-4a
            g.setBonusValue(g.getBonusValue().add(nz(row.getBonusValue())));
            // Count DISTINCT invoices: a three-line sale is one transaction, not three.
            if (row.getInvoiceNo() != null && !row.getInvoiceNo().isEmpty()) invoices.add(row.getInvoiceNo());
        }
        g.setInvoices(invoices.size());
        g.setInvoiceNos(new ArrayList<>(invoices));
        return g;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToDash(String s) {
        return s == null || s.trim().isEmpty() ? "—" : s;
    }

    /**
     * The day key, from the report's rendered date.
     *
     * <p><b>SR-1 — the format is {@code dd-MM-yyyy}, not ISO.</b> {@code SellController.loadSR} renders it
     * with {@code AppUtil.getDateStr}, whose formatter is {@code dd-MM-yyyy}; verified against 541 of 541
     * rows on a live tenant, none of them carrying a time. These two helpers were written to the comment
     * "yyyy-MM-dd from a rendered date", which was never true of this report.
     *
     * <p>Day grouping SURVIVED that mistake by luck — it takes the whole 10-character string, which is
     * unique per day whichever way round it is written. Month grouping did not: see {@link #monthKey}.
     */
    private static String dayKey(String dated) {
        return dated == null || dated.length() < 10 ? blankToDash(dated) : dated.substring(0, 10);
    }

    /**
     * The month key {@code MM-yyyy}, from a {@code dd-MM-yyyy} rendered date.
     *
     * <p>This used to return {@code dated.substring(0, 7)} — the first seven characters of
     * {@code "01-09-2026"}, which is <b>{@code "01-09-2"}</b>: a day, a month, and the first digit of the
     * year. Every day therefore became its own "month", and "group by month" quietly produced the same rows
     * as "group by day". It could only have been noticed by someone counting the groups.
     */
    private static String monthKey(String dated) {
        // Positions 3..9 of dd-MM-yyyy — month and year, the part that identifies the month.
        return dated == null || dated.length() < 10 ? blankToDash(dated) : dated.substring(3, 10);
    }
}
