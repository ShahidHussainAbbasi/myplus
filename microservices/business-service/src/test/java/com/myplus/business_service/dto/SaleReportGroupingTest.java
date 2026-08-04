package com.myplus.business_service.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2B-P3e-2 (#6): grouping and subtotals are pure, so the arithmetic a shop reconciles against is tested
 * directly — no Spring, no database. Runs on every {@code mvn test}.
 */
class SaleReportGroupingTest {

    private SellDTO row(String dated, String invoice, String customer, String item,
                        String category, float qty, String total, String tax) {
        SellDTO r = new SellDTO();
        r.setDated(dated);
        r.setInvoiceNo(invoice);
        r.setCn(customer);
        r.setItemName(item);
        r.setCategory(category);
        r.setQuantity(qty);
        r.setTotalAmount(new BigDecimal(total));
        r.setTaxAmount(new BigDecimal(tax));
        return r;
    }

    private List<SellDTO> twoInvoicesOnOneDay() {
        return List.of(
                // one invoice, TWO lines — this is one transaction, not two
                row("2026-08-04 10:00", "INV-000001", "Acme", "Cola", "Drinks", 2f, "100.00", "10.00"),
                row("2026-08-04 10:00", "INV-000001", "Acme", "Chips", "Snacks", 1f, "50.00", "5.00"),
                row("2026-08-04 11:00", "INV-000002", "Beta", "Cola", "Drinks", 3f, "150.00", "15.00"));
    }

    @Test
    @DisplayName("grouping by day sums the whole day into one line")
    void byDay() {
        List<SaleReportGroup> g = SaleReportGrouping.DAY.aggregate(twoInvoicesOnOneDay());
        assertEquals(1, g.size());
        assertEquals("2026-08-04", g.get(0).getLabel());
        assertEquals(0, new BigDecimal("300.00").compareTo(g.get(0).getTotal()));
        assertEquals(0, new BigDecimal("30.00").compareTo(g.get(0).getTax()));
        assertEquals(0, new BigDecimal("330.00").compareTo(g.get(0).getGross()), "gross = total + tax");
    }

    @Test
    @DisplayName("a multi-line invoice counts as ONE transaction")
    void distinctInvoices() {
        // The failure this prevents: reporting 3 "sales" for a day that had 2, because one had two lines.
        List<SaleReportGroup> g = SaleReportGrouping.DAY.aggregate(twoInvoicesOnOneDay());
        assertEquals(2, g.get(0).getInvoices());
    }

    @Test
    @DisplayName("grouping by customer separates who bought what")
    void byCustomer() {
        List<SaleReportGroup> g = SaleReportGrouping.CUSTOMER.aggregate(twoInvoicesOnOneDay());
        assertEquals(2, g.size());
        assertEquals("Acme", g.get(0).getLabel(), "order follows first appearance, not hash order");
        assertEquals(0, new BigDecimal("150.00").compareTo(g.get(0).getTotal()));
        assertEquals(0, new BigDecimal("150.00").compareTo(g.get(1).getTotal()));
    }

    @Test
    @DisplayName("grouping by category crosses invoices")
    void byCategory() {
        List<SaleReportGroup> g = SaleReportGrouping.CATEGORY.aggregate(twoInvoicesOnOneDay());
        assertEquals(2, g.size());
        SaleReportGroup drinks = g.stream().filter(x -> "Drinks".equals(x.getLabel())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("250.00").compareTo(drinks.getTotal()), "both invoices' drinks");
        assertEquals(0, new BigDecimal("5").compareTo(drinks.getQuantity()), "2 + 3 units");
    }

    @Test
    @DisplayName("grouping by month rolls days up")
    void byMonth() {
        List<SaleReportGroup> g = SaleReportGrouping.MONTH.aggregate(List.of(
                row("2026-08-04 10:00", "INV-1", "A", "X", "C", 1f, "10.00", "0.00"),
                row("2026-08-31 10:00", "INV-2", "A", "X", "C", 1f, "20.00", "0.00"),
                row("2026-09-01 10:00", "INV-3", "A", "X", "C", 1f, "40.00", "0.00")));
        assertEquals(2, g.size());
        assertEquals("2026-08", g.get(0).getLabel());
        assertEquals(0, new BigDecimal("30.00").compareTo(g.get(0).getTotal()));
    }

    @Test
    @DisplayName("a missing dimension groups under a dash, never crashes the report")
    void missingValues() {
        List<SaleReportGroup> g = SaleReportGrouping.CATEGORY.aggregate(List.of(
                row("2026-08-04 10:00", "INV-1", "A", "X", null, 1f, "10.00", "0.00")));
        assertEquals("—", g.get(0).getLabel());
    }

    @Test
    @DisplayName("an unknown or blank groupBy means UNGROUPED, not an error")
    void unknownGrouping() {
        // A stale bookmark or a typed URL must not 500 a report.
        assertNull(SaleReportGrouping.from(null));
        assertNull(SaleReportGrouping.from("   "));
        assertNull(SaleReportGrouping.from("byUnicorn"));
        assertEquals(SaleReportGrouping.DAY, SaleReportGrouping.from("day"), "case-insensitive");
    }

    @Test
    @DisplayName("subtotals use BigDecimal, so a month of lines does not drift")
    void noFloatingPointDrift() {
        // 0.10 x 3 is 0.30 exactly here; summing doubles would give 0.30000000000000004.
        List<SellDTO> rows = List.of(
                row("2026-08-04 10:00", "INV-1", "A", "X", "C", 1f, "0.10", "0.00"),
                row("2026-08-04 10:00", "INV-1", "A", "X", "C", 1f, "0.10", "0.00"),
                row("2026-08-04 10:00", "INV-1", "A", "X", "C", 1f, "0.10", "0.00"));
        assertEquals(0, new BigDecimal("0.30").compareTo(SaleReportGrouping.DAY.aggregate(rows).get(0).getTotal()));
    }
}
