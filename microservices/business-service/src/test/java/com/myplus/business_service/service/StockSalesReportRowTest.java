package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.StockMovementType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSR-1 — the report's arithmetic, against the owner's own "DAVIS SSR" rows (Sep 2026). Quantities from the ledger,
 * money and the bonus split from the sale documents, TP from the product.
 */
class StockSalesReportRowTest {

    private static ProductRef product(String sku, String name, int pack, String tp) {
        ProductRef p = new ProductRef();
        p.setId(1L);
        p.setSku(sku);
        p.setName(name);
        p.setPackSize(pack);
        p.setLastPurchaseRate(new BigDecimal(tp));
        return p;
    }

    private static Map<String, Object> ledger(String opening, String closing, Object... typeQty) {
        Map<String, BigDecimal> mv = new java.util.LinkedHashMap<>();
        for (int i = 0; i < typeQty.length; i += 2) mv.put((String) typeQty[i], new BigDecimal((String) typeQty[i + 1]));
        return Map.of("opening", new BigDecimal(opening), "movements", mv, "closing", new BigDecimal(closing));
    }

    private static BigDecimal d(Map<String, Object> r, String k) { return (BigDecimal) r.get(k); }

    @Test
    @DisplayName("ACNE CARE CREAM: −8 + 564 = 556; 482 sold, 42 back, 27 bonus → closing 89, 9,314 at TP 104.65")
    void acne_care_row() {
        Map<String, Object> r = StockSalesReportService.row(1L, product("2117", "ACNE CARE CREAM 20G", 1, "104.65"),
                ledger("-8", "89",
                        StockMovementType.PURCHASE, "564",
                        StockMovementType.SALE, "-509",          // 482 paid + 27 bonus left together
                        StockMovementType.SALE_RETURN, "42"),
                new BigDecimal("55620"), new BigDecimal("27"), new BigDecimal("4675"));

        assertThat(d(r, "opening")).isEqualByComparingTo("-8");
        assertThat(d(r, "purchase")).isEqualByComparingTo("564");
        assertThat(d(r, "totalStock")).isEqualByComparingTo("556");
        assertThat(d(r, "salesQty")).as("goods out minus bonus").isEqualByComparingTo("482");
        assertThat(d(r, "salesReturnQty")).isEqualByComparingTo("42");
        assertThat(d(r, "netSalesQty")).isEqualByComparingTo("440");
        assertThat(d(r, "bonusQty")).isEqualByComparingTo("27");
        assertThat(d(r, "bonusValue")).as("bonus at TP: 27 × 104.65").isEqualByComparingTo("2825.55");
        assertThat(d(r, "closingQty")).isEqualByComparingTo("89");
        assertThat(d(r, "closingValue")).as("89 × 104.65 (the PDF prints 9,314)").isEqualByComparingTo("9313.85");
        // Standard, not the PDF's convention: net sales value is the money actually taken (sales − returns), so it
        // reconciles with the books; the PDF values it at a fixed list price (440 × 117 = 51,480).
        assertThat(d(r, "netSalesValue")).isEqualByComparingTo("50945.00");
        assertThat(r.get("adds")).as("the row reaches its closing").isEqualTo(true);
        assertThat(r.get("pack")).isEqualTo("1'S");
    }

    @Test
    @DisplayName("a purchase return, a correction and a quarantined return all land in the row, which still adds up")
    void returns_and_adjustments() {
        Map<String, Object> r = StockSalesReportService.row(1L, product("976", "CALPLEX C", 10, "80.08"),
                ledger("4826", "5234",
                        StockMovementType.PURCHASE, "640",
                        StockMovementType.PURCHASE_RETURN, "-5",
                        StockMovementType.SALE, "-301",          // 269 + 32 bonus
                        StockMovementType.SALE_RETURN, "86",
                        StockMovementType.QUARANTINE, "-2",      // returned but not sellable
                        StockMovementType.ADJUSTMENT, "-10"),
                new BigDecimal("23666"), new BigDecimal("32"), new BigDecimal("8307"));

        assertThat(d(r, "purchase")).isEqualByComparingTo("640");
        assertThat(d(r, "purchaseReturn")).isEqualByComparingTo("5");
        assertThat(d(r, "totalStock")).isEqualByComparingTo("5461");
        assertThat(d(r, "salesQty")).isEqualByComparingTo("269");
        assertThat(d(r, "netSalesQty")).isEqualByComparingTo("183");
        assertThat(d(r, "adjustments")).as("correction + quarantine").isEqualByComparingTo("-12");
        assertThat(r.get("adds")).isEqualTo(true);
        assertThat(r.get("pack")).isEqualTo("10'S");
    }

    @Test
    @DisplayName("sale lines claiming more bonus than physically left: shown as not adding up, never hidden")
    void bonus_mismatch_is_flagged() {
        Map<String, Object> r = StockSalesReportService.row(1L, product("1", "X", 1, "10"),
                ledger("0", "-5", StockMovementType.SALE, "-5"),
                new BigDecimal("50"), new BigDecimal("2"), BigDecimal.ZERO);
        assertThat(d(r, "salesQty")).isEqualByComparingTo("3");
        assertThat(r.get("adds")).isEqualTo(true);   // goods out 5 = 3 sold + 2 bonus: consistent
        Map<String, Object> wrong = StockSalesReportService.row(1L, product("1", "X", 1, "10"),
                Map.of("opening", BigDecimal.ZERO, "movements", Map.of(StockMovementType.SALE, new BigDecimal("-5")),
                        "closing", new BigDecimal("-4")),   // a closing the movements do not reach
                new BigDecimal("50"), new BigDecimal("2"), BigDecimal.ZERO);
        assertThat(wrong.get("adds")).isEqualTo(false);
    }

    @Test
    @DisplayName("go-live inside the period: its OPENING row is opening stock, not an adjustment")
    void go_live_inside_the_period() {
        Map<String, Object> r = StockSalesReportService.row(1L, product("1", "X", 1, "10"),
                ledger("0", "12", StockMovementType.OPENING, "10", StockMovementType.PURCHASE, "2"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(d(r, "opening")).isEqualByComparingTo("10");
        assertThat(d(r, "adjustments")).isEqualByComparingTo("0");
        assertThat(r.get("adds")).isEqualTo(true);
    }

    @Test
    @DisplayName("grouped by supplier (A→Z, 'No supplier' last) with subtotals and a Net Total")
    void groups_and_totals() {
        Map<String, Object> a = new java.util.HashMap<>(Map.of("supplier", "DAVIS PHARMA", "name", "B", "closingQty", new BigDecimal("2")));
        Map<String, Object> b = new java.util.HashMap<>(Map.of("supplier", "DAVIS PHARMA", "name", "A", "closingQty", new BigDecimal("3")));
        Map<String, Object> c = new java.util.HashMap<>(Map.of("supplier", StockSalesReportService.NO_SUPPLIER, "name", "C", "closingQty", new BigDecimal("1")));
        Map<String, Object> e = new java.util.HashMap<>(Map.of("supplier", "DAVIS PHARMA (LIFE LINE)", "name", "D", "closingQty", new BigDecimal("4")));
        List<Map<String, Object>> groups = StockSalesReportService.group(List.of(a, b, c, e));
        assertThat(groups).extracting(g -> g.get("supplier"))
                .containsExactly("DAVIS PHARMA", "DAVIS PHARMA (LIFE LINE)", StockSalesReportService.NO_SUPPLIER);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> davis = (List<Map<String, Object>>) groups.get(0).get("rows");
        assertThat(davis).extracting(r -> r.get("name")).containsExactly("A", "B");
        @SuppressWarnings("unchecked")
        Map<String, Object> sub = (Map<String, Object>) groups.get(0).get("total");
        assertThat((BigDecimal) sub.get("closingQty")).isEqualByComparingTo("5");
        assertThat((BigDecimal) StockSalesReportService.totals(List.of(a, b, c, e)).get("closingQty")).isEqualByComparingTo("10");
    }
}
