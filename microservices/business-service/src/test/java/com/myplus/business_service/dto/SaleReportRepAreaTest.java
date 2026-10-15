package com.myplus.business_service.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SRA-4a — the sales-by-rep-and-area dimensions of the Sale Detail Report: filters and one-level grouping are pure, so
 * the figures a distributor pays commission from are tested directly. Design: sra-sales-by-rep-and-area.md §7.
 *
 * The scripted month (Test Book 29b): Irfan's paper order 3,450 with 3 bonus at TP 100; the booker's own order 2,300;
 * a counter sale 460; and one sale from before SRA-3 (no source, no rep) 100. Grand total 6,310.
 */
class SaleReportRepAreaTest {

    private static SellDTO row(String invoice, Long repId, String repName, String source, Long areaId, String area,
                               String city, Long supplierId, String supplier, String total, String bonusQty, String bonusValue) {
        SellDTO r = new SellDTO();
        r.setDated("15-10-2026");
        r.setInvoiceNo(invoice);
        r.setRepId(repId);
        r.setRepName(repName);
        r.setSaleSource(source);
        r.setAreaId(areaId);
        r.setArea(area);
        r.setCity(city);
        r.setSupplierId(supplierId);
        r.setSupplier(supplier);
        r.setQuantity(BigDecimal.ONE);
        r.setTotalAmount(new BigDecimal(total));
        r.setTaxAmount(BigDecimal.ZERO);
        r.setBonusQuantity(bonusQty == null ? null : new BigDecimal(bonusQty));
        r.setBonusValue(bonusValue == null ? null : new BigDecimal(bonusValue));
        return r;
    }

    private static List<SellDTO> month() {
        return List.of(
                row("INV-1", 11L, "Irfan", "REP_PAPER", 1L, "Zahir Pir", "Khanpur", 7L, "Davis", "3450.00", "3", "300.00"),
                row("INV-2", 12L, "Booker", "REP_LOGIN", 2L, "1st Sector", "Khanpur", 7L, "Davis", "2300.00", null, null),
                row("INV-3", null, null, "COUNTER", 3L, "Model Town", "Rahim Yar Khan", null, null, "460.00", "0", "0.00"),
                row("INV-0", null, null, null, null, null, null, null, null, "100.00", null, null));
    }

    private static BigDecimal total(List<SaleReportGroup> g) {
        return g.stream().map(SaleReportGroup::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("by rep: one group per rep, and 'no rep' is kept apart from 'not recorded' — both as CODES, not names")
    void byRep() {
        List<SaleReportGroup> g = SaleReportGrouping.REP.aggregate(month());
        assertEquals(4, g.size());
        assertEquals("Irfan", g.get(0).getLabel());
        assertEquals(0, new BigDecimal("3450.00").compareTo(g.get(0).getTotal()));
        assertEquals(0, new BigDecimal("3").compareTo(g.get(0).getBonusQuantity()), "bonus qty rides with the group");
        assertEquals(0, new BigDecimal("300.00").compareTo(g.get(0).getBonusValue()), "bonus value at TP");
        assertNull(g.get(2).getLabel(), "a counter sale has no rep NAME");
        assertEquals("NO_REP", g.get(2).getLabelCode());
        assertEquals("NOT_RECORDED", g.get(3).getLabelCode(), "a sale from before SRA-3");
        assertEquals(0, new BigDecimal("6310.00").compareTo(total(g)), "the groups add up to the grand total");
    }

    @Test
    @DisplayName("by source and by city: codes for the source, names for the city, and the gap is NO_AREA")
    void bySourceAndCity() {
        List<String> sources = SaleReportGrouping.SOURCE.aggregate(month()).stream()
                .map(SaleReportGroup::getLabelCode).collect(Collectors.toList());
        assertEquals(List.of("REP_PAPER", "REP_LOGIN", "COUNTER", "NOT_RECORDED"), sources);
        List<SaleReportGroup> city = SaleReportGrouping.CITY.aggregate(month());
        assertEquals(3, city.size());
        assertEquals("Khanpur", city.get(0).getLabel());
        assertEquals(0, new BigDecimal("5750.00").compareTo(city.get(0).getTotal()), "both Khanpur areas together");
        assertEquals("NO_AREA", city.get(2).getLabelCode());
    }

    @Test
    @DisplayName("a rep NAMED like a code is still a name — it can never be confused with the gap")
    void nameLikeACode() {
        SellDTO r = row("INV-9", 13L, "NO_REP", "REP_PAPER", null, null, null, null, null, "10.00", null, null);
        SaleReportGroup g = SaleReportGrouping.REP.aggregate(List.of(r)).get(0);
        assertEquals("NO_REP", g.getLabel());
        assertNull(g.getLabelCode());
    }

    @Test
    @DisplayName("filters: a rep, 'none' (0), a source, NOT_RECORDED, an area, a supplier, with / without bonus")
    void filters() {
        assertEquals(List.of("INV-1"), invoices(SaleReportFilter.builder().repId(11L).build()));
        assertEquals(List.of("INV-3", "INV-0"), invoices(SaleReportFilter.builder().repId(0L).build()), "0 = no rep at all");
        assertEquals(List.of("INV-3"), invoices(SaleReportFilter.builder().saleSource("COUNTER").build()));
        assertEquals(List.of("INV-0"), invoices(SaleReportFilter.builder().saleSource("NOT_RECORDED").build()));
        assertEquals(List.of("INV-2"), invoices(SaleReportFilter.builder().areaId(2L).build()));
        assertEquals(List.of("INV-0"), invoices(SaleReportFilter.builder().areaId(0L).build()));
        assertEquals(List.of("INV-1", "INV-2"), invoices(SaleReportFilter.builder().supplierId(7L).build()));
        assertEquals(List.of("INV-1"), invoices(SaleReportFilter.builder().bonus("WITH").build()));
        assertEquals(List.of("INV-2", "INV-3", "INV-0"), invoices(SaleReportFilter.builder().bonus("WITHOUT").build()),
                "a zero bonus is no bonus");
        assertTrue(SaleReportFilter.builder().build().isEmpty(), "nothing chosen = the report as it was");
        assertFalse(SaleReportFilter.builder().bonus("WITH").build().isEmpty());
    }

    private static List<String> invoices(SaleReportFilter f) {
        return month().stream().filter(f.asPredicate()).map(SellDTO::getInvoiceNo).collect(Collectors.toList());
    }
}
