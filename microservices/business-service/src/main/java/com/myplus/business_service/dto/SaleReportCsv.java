package com.myplus.business_service.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.myplus.business_service.util.Quantities;
import com.myplus.common.imports.CsvWriter;

/**
 * SRA-4b — the grouped Sale Detail Report as a CSV: exactly the tree the screen shows, one row per group at every level,
 * so a spreadsheet can filter on any level and the subtotals are already there.
 *
 * <pre>
 *   REP,AREA,Invoices,Qty,Total,Tax,Gross,Bonus qty,Bonus value[,&lt;split value&gt;…]
 *   Irfan,,2,40,4600.00,…           ← the rep's subtotal (deeper levels blank)
 *   Irfan,Zahir Pir,1,30,3450.00,…   ← each row repeats its parents, so a filter on AREA keeps the rep beside it
 * </pre>
 * A one-level report with no split writes exactly the header and rows it wrote before SRA-4b. A nameless group is written
 * as its CODE (NOT_RECORDED, NO_REP, COUNTER …) — stable for a spreadsheet to filter on.
 */
public final class SaleReportCsv {

    private SaleReportCsv() {}

    public static String grouped(List<SaleReportGroup> tree, List<SaleReportGrouping> levels, SaleReportGrouping split) {
        List<String> header = new ArrayList<>();
        for (SaleReportGrouping l : levels) header.add(l.name());
        header.addAll(Arrays.asList("Invoices", "Qty", "Total", "Tax", "Gross", "Bonus qty", "Bonus value"));

        // The split's columns: every value that appears anywhere, in first-seen order — the same order the screen uses.
        Map<String, String> splitCols = new LinkedHashMap<>();
        if (split != null) collectCols(tree, splitCols);
        header.addAll(splitCols.values());

        List<List<?>> rows = new ArrayList<>();
        walk(tree, new ArrayList<>(), levels.size(), splitCols, rows);
        return CsvWriter.write(header, rows);
    }

    private static void walk(List<SaleReportGroup> nodes, List<String> path, int depth, Map<String, String> splitCols,
                             List<List<?>> rows) {
        for (SaleReportGroup g : nodes) {
            List<String> mine = new ArrayList<>(path);
            mine.add(label(g));
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < depth; i++) row.add(i < mine.size() ? mine.get(i) : "");
            row.addAll(Arrays.asList(g.getInvoices(), Quantities.plain(g.getQuantity()), g.getTotal(), g.getTax(),
                    g.getGross(), Quantities.plain(g.getBonusQuantity()), g.getBonusValue()));
            if (!splitCols.isEmpty()) {
                Map<String, Object> cell = new LinkedHashMap<>();
                if (g.getCols() != null) for (SaleReportGroup c : g.getCols()) cell.put(key(c), c.getTotal());
                for (String k : splitCols.keySet()) row.add(cell.getOrDefault(k, ""));
            }
            rows.add(row);
            if (g.getChildren() != null && !g.getChildren().isEmpty()) walk(g.getChildren(), mine, depth, splitCols, rows);
        }
    }

    private static void collectCols(List<SaleReportGroup> nodes, Map<String, String> into) {
        for (SaleReportGroup g : nodes) {
            if (g.getCols() != null) for (SaleReportGroup c : g.getCols()) into.putIfAbsent(key(c), label(c));
            if (g.getChildren() != null) collectCols(g.getChildren(), into);
        }
    }

    private static String label(SaleReportGroup g) {
        return g.getLabel() != null ? g.getLabel() : g.getLabelCode();
    }

    /** A name and a code can never collide as column keys ("Counter" the rep vs COUNTER the source). */
    private static String key(SaleReportGroup g) {
        return g.getLabel() != null ? "n:" + g.getLabel() : "c:" + g.getLabelCode();
    }
}
