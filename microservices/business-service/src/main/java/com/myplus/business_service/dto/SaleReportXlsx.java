package com.myplus.business_service.dto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * SRA-4c — the Sale Detail Report as an Excel workbook: exactly what the screen shows (design §7.3), built from the SAME
 * answer loadSR gives the screen and the CSV, so the three cannot disagree.
 *
 * <ul>
 *   <li>A header block says what the file is: the period's dates, every filter applied, the grouping and the column split
 *       — a spreadsheet forwarded on its own must still say which slice of the business it is.</li>
 *   <li>Money and quantities are NUMBERS with a number format, never text — a manager sums a column without retyping it.</li>
 *   <li>Grouped: one row per group at every level, indented, with Excel's own outline (the +/− in the margin) so a level
 *       folds the way it does on screen; a bold grand total last. Ungrouped: the CSV's detail columns.</li>
 * </ul>
 * Labels are the stored names; a nameless group is written as its code (NOT_RECORDED, NO_REP, COUNTER …), as in the CSV.
 */
public final class SaleReportXlsx {

    private SaleReportXlsx() {}

    /** What the file is about, printed above the table. Keys and values are written as given, in order. */
    public static byte[] grouped(Map<String, String> about, List<SaleReportGroup> tree, List<SaleReportGrouping> levels,
                                 SaleReportGrouping split) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("Sale report");
            int r = header(sh, st, about);

            Map<String, String> splitCols = new LinkedHashMap<>();
            if (split != null) collectCols(tree, splitCols);
            List<String> head = new ArrayList<>();
            head.add(String.join(" › ", levels.stream().map(Enum::name).toList()));
            head.addAll(splitCols.values());
            head.addAll(List.of("Invoices", "Qty", "Total", "Tax", "Gross", "Bonus qty", "Bonus value"));
            r = titles(sh, st, r, head);

            int[] rowNo = { r };
            BigDecimal[] grand = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
            java.util.Set<String> invoices = new java.util.TreeSet<>();
            for (SaleReportGroup g : tree) {
                grand[0] = grand[0].add(nz(g.getQuantity()));
                grand[1] = grand[1].add(nz(g.getTotal()));
                grand[2] = grand[2].add(nz(g.getTax()));
                grand[3] = grand[3].add(nz(g.getGross()));
                grand[4] = grand[4].add(nz(g.getBonusQuantity()));
                grand[5] = grand[5].add(nz(g.getBonusValue()));
                if (g.getInvoiceNos() != null) invoices.addAll(g.getInvoiceNos());
            }
            writeNodes(sh, st, tree, 0, splitCols, rowNo);

            Row t = sh.createRow(rowNo[0]);
            text(t, 0, "Grand total", st.bold);
            int c = 1 + splitCols.size();
            number(t, c++, invoices.size(), st.intBold);
            number(t, c++, grand[0], st.qtyBold);
            number(t, c++, grand[1], st.moneyBold);
            number(t, c++, grand[2], st.moneyBold);
            number(t, c++, grand[3], st.moneyBold);
            number(t, c++, grand[4], st.qtyBold);
            number(t, c, grand[5], st.moneyBold);

            sh.setRowSumsBelow(false);   // the +/− sits on the parent row, above its children, as on screen
            widths(sh, head.size());
            return bytes(wb);
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the workbook", e);
        }
    }

    /** Ungrouped: the CSV's detail columns, one row per sale line. */
    public static byte[] detail(Map<String, String> about, List<SellDTO> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("Sale report");
            int r = header(sh, st, about);
            List<String> head = List.of("Date", "Invoice", "Customer", "Channel", "SKU", "Item", "Category", "Qty", "Rate",
                    "Total", "Tax", "Rep", "Source", "Keyed by", "Area", "City", "Supplier", "Bonus qty", "Bonus value");
            r = titles(sh, st, r, head);
            for (SellDTO s : rows) {
                Row x = sh.createRow(r++);
                int c = 0;
                text(x, c++, s.getDated(), null);
                text(x, c++, s.getInvoiceNo(), null);
                text(x, c++, s.getCn(), null);
                text(x, c++, s.getCustomerType(), null);
                text(x, c++, s.getItemCode(), null);
                text(x, c++, s.getItemName(), null);
                text(x, c++, s.getCategory(), null);
                number(x, c++, s.getQuantity(), st.qty);
                number(x, c++, s.getSellRate(), st.money);
                number(x, c++, s.getTotalAmount(), st.money);
                number(x, c++, s.getTaxAmount(), st.money);
                text(x, c++, s.getRepName(), null);
                text(x, c++, s.getSaleSource(), null);
                text(x, c++, s.getKeyedBy(), null);
                text(x, c++, s.getArea(), null);
                text(x, c++, s.getCity(), null);
                text(x, c++, s.getSupplier(), null);
                number(x, c++, s.getBonusQuantity(), st.qty);
                number(x, c, s.getBonusValue(), st.money);
            }
            widths(sh, head.size());
            return bytes(wb);
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the workbook", e);
        }
    }

    // ── pieces ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static void writeNodes(Sheet sh, Styles st, List<SaleReportGroup> nodes, int depth, Map<String, String> splitCols,
                                   int[] rowNo) {
        for (SaleReportGroup g : nodes) {
            int first = rowNo[0];
            Row x = sh.createRow(rowNo[0]++);
            Cell label = x.createCell(0);
            label.setCellValue(labelOf(g));
            label.setCellStyle(st.indent(depth));
            int c = 1;
            Map<String, BigDecimal> cell = new LinkedHashMap<>();
            if (g.getCols() != null) for (SaleReportGroup col : g.getCols()) cell.put(key(col), col.getTotal());
            for (String k : splitCols.keySet()) number(x, c++, cell.get(k), st.money);
            number(x, c++, g.getInvoices(), st.integer);
            number(x, c++, g.getQuantity(), st.qty);
            number(x, c++, g.getTotal(), st.money);
            number(x, c++, g.getTax(), st.money);
            number(x, c++, g.getGross(), st.money);
            number(x, c++, g.getBonusQuantity(), st.qty);
            number(x, c, g.getBonusValue(), st.money);
            if (g.getChildren() != null && !g.getChildren().isEmpty()) {
                writeNodes(sh, st, g.getChildren(), depth + 1, splitCols, rowNo);
                sh.groupRow(first + 1, rowNo[0] - 1);   // Excel's outline: fold the children under this row
            }
        }
    }

    private static int header(Sheet sh, Styles st, Map<String, String> about) {
        int r = 0;
        text(sh.createRow(r++), 0, "Sale Detail Report", st.title);
        for (Map.Entry<String, String> e : about.entrySet()) {
            Row x = sh.createRow(r++);
            text(x, 0, e.getKey(), st.bold);
            text(x, 1, e.getValue(), null);
        }
        return r + 1;   // a blank row before the table
    }

    private static int titles(Sheet sh, Styles st, int r, List<String> head) {
        Row x = sh.createRow(r);
        for (int i = 0; i < head.size(); i++) text(x, i, head.get(i), st.head);
        sh.createFreezePane(1, r + 1);   // the column titles and the group label stay put while scrolling
        return r + 1;
    }

    private static void collectCols(List<SaleReportGroup> nodes, Map<String, String> into) {
        for (SaleReportGroup g : nodes) {
            if (g.getCols() != null) for (SaleReportGroup c : g.getCols()) into.putIfAbsent(key(c), labelOf(c));
            if (g.getChildren() != null) collectCols(g.getChildren(), into);
        }
    }

    private static String labelOf(SaleReportGroup g) {
        return g.getLabel() != null ? g.getLabel() : g.getLabelCode();
    }

    private static String key(SaleReportGroup g) {
        return g.getLabel() != null ? "n:" + g.getLabel() : "c:" + g.getLabelCode();
    }

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    /** A NUMBER cell (never text); blank when there is no value, so an empty split cell is not a zero. */
    private static void number(Row row, int col, Number value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value.doubleValue());
        c.setCellStyle(style);
    }

    private static void widths(Sheet sh, int cols) {
        sh.setColumnWidth(0, 40 * 256);
        for (int i = 1; i < cols; i++) sh.setColumnWidth(i, 14 * 256);
    }

    private static byte[] bytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** One workbook's styles — created once each; POI caps a workbook at 64,000 styles, so never one per cell. */
    private static final class Styles {
        final CellStyle title, head, bold, money, moneyBold, qty, qtyBold, integer, intBold;
        private final XSSFWorkbook wb;
        private final Map<Integer, CellStyle> indents = new LinkedHashMap<>();

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
            DataFormat df = wb.createDataFormat();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            Font b = wb.createFont();
            b.setBold(true);
            title = wb.createCellStyle();
            title.setFont(big);
            head = wb.createCellStyle();
            head.setFont(b);
            bold = wb.createCellStyle();
            bold.setFont(b);
            money = fmt(df.getFormat("#,##0.00"), null);
            moneyBold = fmt(df.getFormat("#,##0.00"), b);
            qty = fmt(df.getFormat("#,##0.####"), null);
            qtyBold = fmt(df.getFormat("#,##0.####"), b);
            integer = fmt(df.getFormat("0"), null);
            intBold = fmt(df.getFormat("0"), b);
        }

        private CellStyle fmt(short format, Font font) {
            CellStyle s = wb.createCellStyle();
            s.setDataFormat(format);
            if (font != null) s.setFont(font);
            return s;
        }

        CellStyle indent(int depth) {
            return indents.computeIfAbsent(depth, d -> {
                CellStyle s = wb.createCellStyle();
                s.setIndention((short) (d * 2));
                return s;
            });
        }
    }
}
