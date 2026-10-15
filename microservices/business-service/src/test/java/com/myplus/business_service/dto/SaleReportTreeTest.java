package com.myplus.business_service.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SRA-4b — nested groups, the column split and the invoices behind each figure. The scripted month (Test Book 29b):
 * Irfan's paper order 3,450 (Zahir Pir, Davitran, bonus 3 = 300); the booker's order 1,150 Davitran + 1,150 Cefim
 * (1st Sector); a counter sale 460 Cefim (Model Town). Grand total 6,210.
 */
class SaleReportTreeTest {

    private static SellDTO row(String invoice, String rep, String source, String area, String product, String total,
                               String bonusQty, String bonusValue) {
        SellDTO r = new SellDTO();
        r.setDated("15-10-2026");
        r.setInvoiceNo(invoice);
        r.setRepName(rep);
        r.setSaleSource(source);
        r.setArea(area);
        r.setItemName(product);
        r.setQuantity(BigDecimal.ONE);
        r.setTotalAmount(new BigDecimal(total));
        r.setTaxAmount(BigDecimal.ZERO);
        r.setBonusQuantity(bonusQty == null ? null : new BigDecimal(bonusQty));
        r.setBonusValue(bonusValue == null ? null : new BigDecimal(bonusValue));
        return r;
    }

    private static List<SellDTO> month() {
        return List.of(
                row("INV-1", "Irfan", "REP_PAPER", "Zahir Pir", "Davitran", "3450.00", "3", "300.00"),
                row("INV-2", "Booker", "REP_LOGIN", "1st Sector", "Davitran", "1150.00", null, null),
                row("INV-2", "Booker", "REP_LOGIN", "1st Sector", "Cefim", "1150.00", null, null),
                row("INV-3", null, "COUNTER", "Model Town", "Cefim", "460.00", null, null));
    }

    private static BigDecimal sum(List<SaleReportGroup> g) {
        return g.stream().map(SaleReportGroup::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("levels: a comma list, unknown and repeated names dropped, at most four")
    void levels() {
        assertEquals(List.of(SaleReportGrouping.REP, SaleReportGrouping.AREA, SaleReportGrouping.PRODUCT),
                SaleReportGrouping.levels("REP, area,byUnicorn,REP,PRODUCT"));
        assertEquals(4, SaleReportGrouping.levels("REP,AREA,CITY,PRODUCT,SUPPLIER").size());
        assertTrue(SaleReportGrouping.levels(null).isEmpty());
        assertTrue(SaleReportGrouping.levels("").isEmpty());
    }

    @Test
    @DisplayName("Rep → Area → Product: every level adds up to the one above, and to the grand total")
    void threeLevels() {
        List<SaleReportGroup> reps = SaleReportGrouping.tree(month(),
                SaleReportGrouping.levels("REP,AREA,PRODUCT"), null);
        assertEquals(0, new BigDecimal("6210.00").compareTo(sum(reps)), "grand total");
        for (SaleReportGroup rep : reps) {
            assertEquals(0, rep.getTotal().compareTo(sum(rep.getChildren())), rep.getLabel() + ": areas add up");
            for (SaleReportGroup area : rep.getChildren()) {
                assertEquals(0, area.getTotal().compareTo(sum(area.getChildren())), "products add up");
                area.getChildren().forEach(p -> assertTrue(p.getChildren().isEmpty(), "the last level has no children"));
            }
        }
        SaleReportGroup booker = reps.get(1);
        assertEquals("Booker", booker.getLabel());
        assertEquals(1, booker.getInvoices(), "two lines, ONE invoice");
        assertEquals(List.of("Davitran", "Cefim"), booker.getChildren().get(0).getChildren().stream()
                .map(SaleReportGroup::getLabel).toList());
        assertEquals("NO_REP", reps.get(2).getLabelCode(), "the counter is a code at every level");
        assertEquals(0, new BigDecimal("300.00").compareTo(reps.get(0).getChildren().get(0).getBonusValue()),
                "bonus rides down the tree");
    }

    @Test
    @DisplayName("drill-down: each figure names exactly the invoices behind it")
    void invoicesBehindAFigure() {
        List<SaleReportGroup> products = SaleReportGrouping.tree(month(), SaleReportGrouping.levels("PRODUCT"), null);
        assertEquals(List.of("INV-1", "INV-2"), products.get(0).getInvoiceNos(), "Davitran");
        assertEquals(List.of("INV-2", "INV-3"), products.get(1).getInvoiceNos(), "Cefim");
    }

    @Test
    @DisplayName("column split: rows by area, one column per rep — the cells add up to the row")
    void split() {
        List<SaleReportGroup> areas = SaleReportGrouping.tree(month(), SaleReportGrouping.levels("AREA"),
                SaleReportGrouping.REP);
        for (SaleReportGroup a : areas) assertEquals(0, a.getTotal().compareTo(sum(a.getCols())), a.getLabel());
        assertEquals("Irfan", areas.get(0).getCols().get(0).getLabel());
        assertEquals("NO_REP", areas.get(2).getCols().get(0).getLabelCode());
        assertNull(SaleReportGrouping.tree(month(), SaleReportGrouping.levels("AREA"), null).get(0).getCols(),
                "no split, no cells");
    }

    @Test
    @DisplayName("CSV: one level and no split is byte-for-byte the SRA-4a file; nested rows repeat their parents")
    void csv() {
        String one = SaleReportCsv.grouped(SaleReportGrouping.tree(month(), SaleReportGrouping.levels("SOURCE"), null),
                SaleReportGrouping.levels("SOURCE"), null);
        assertTrue(one.startsWith("SOURCE,Invoices,Qty,Total,Tax,Gross,Bonus qty,Bonus value"), one);
        assertTrue(one.contains("REP_PAPER,1,1,3450.00,0,3450.00,3,300.00"), one);

        String two = SaleReportCsv.grouped(SaleReportGrouping.tree(month(), SaleReportGrouping.levels("REP,AREA"),
                SaleReportGrouping.PRODUCT), SaleReportGrouping.levels("REP,AREA"), SaleReportGrouping.PRODUCT);
        String[] lines = two.split("\\r?\\n");
        assertEquals("REP,AREA,Invoices,Qty,Total,Tax,Gross,Bonus qty,Bonus value,Davitran,Cefim", lines[0]);
        assertTrue(lines[1].startsWith("Irfan,,1,"), "the rep's subtotal leaves AREA blank: " + lines[1]);
        assertTrue(lines[2].startsWith("Irfan,Zahir Pir,1,"), "the area row repeats its rep: " + lines[2]);
        assertTrue(lines[3].startsWith("Booker,,1,2,2300.00"), lines[3]);
        assertTrue(lines[3].endsWith(",1150.00,1150.00"), "split cells per product: " + lines[3]);
    }
}
