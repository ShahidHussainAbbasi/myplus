package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.dto.PostEventRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SALE and SALE_RETURN journals must balance — asserted against the REAL builders, not a copy of the
 * formula.
 *
 * <h3>Why this test exists</h3>
 * {@code postSale} now has four interacting legs: the tender split (cash vs AR), store credit, a
 * contra-revenue discount, and delivery income. Delivery is the dangerous one — it rides inside
 * {@code grandTotal} but is deliberately excluded from {@code subTotal} and {@code taxTotal}, so a credit leg
 * that forgets it produces a journal that is lopsided by exactly the delivery fee. Nothing caught that before,
 * because reaching this code required posting to a real ledger.
 *
 * <p>Every case asserts the IDENTITY (Σ debits == Σ credits) rather than a list of expected lines. A test that
 * pins the exact lines only proves the code still does what it did; a test that pins the identity proves the
 * thing that must never be false.
 */
class SalePostingBalanceTest {

    private static final String SALES = "4000", TAX = "2100", AR = "1100", CASH = "1000",
            STORE_CREDIT = "2200", SALES_DISCOUNT = "4200", DELIVERY_INCOME = "4300";

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    private static BigDecimal sumDebits(List<JournalLineDTO> lines) {
        return lines.stream().map(l -> l.getDebit() == null ? BigDecimal.ZERO : l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static BigDecimal sumCredits(List<JournalLineDTO> lines) {
        return lines.stream().map(l -> l.getCredit() == null ? BigDecimal.ZERO : l.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static BigDecimal on(List<JournalLineDTO> lines, String code) {
        return lines.stream().filter(l -> code.equals(l.getAccountCode()))
                .map(l -> (l.getDebit() == null ? BigDecimal.ZERO : l.getDebit())
                        .add(l.getCredit() == null ? BigDecimal.ZERO : l.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static void assertBalanced(List<JournalLineDTO> lines, String what) {
        assertThat(sumDebits(lines)).as("%s must balance (Dr %s vs Cr %s)", what, sumDebits(lines), sumCredits(lines))
                .isEqualByComparingTo(sumCredits(lines));
    }

    /** The caller's contract: sub and grand are NET of the discount; grand INCLUDES shipping; sub/tax do not. */
    private static PostEventRequest sale(String sub, String tax, String ship, String discount, String paid) {
        BigDecimal s = bd(sub), t = bd(tax), sh = bd(ship);
        return PostEventRequest.builder()
                .eventType("SALE").ref("INV-TEST")
                .subTotal(s).taxTotal(t).shippingFee(sh).discountTotal(bd(discount))
                .grandTotal(s.add(t).add(sh))
                .paidAmount(bd(paid)).method("CASH")
                .build();
    }

    // ── the plain cases must be untouched ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a plain paid till sale balances and posts no discount or delivery line")
    void plainTillSale() {
        List<JournalLineDTO> lines = PostingService.saleLines(sale("100.00", "10.00", "0", "0", "110.00"));

        assertBalanced(lines, "till sale");
        assertThat(on(lines, CASH)).isEqualByComparingTo("110.00");
        assertThat(on(lines, SALES)).isEqualByComparingTo("100.00");
        assertThat(on(lines, TAX)).isEqualByComparingTo("10.00");
        assertThat(on(lines, SALES_DISCOUNT)).isEqualByComparingTo("0");   // no line at all
        assertThat(on(lines, DELIVERY_INCOME)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an unpaid sale puts the whole bill in AR and still balances")
    void unpaidSaleGoesToReceivables() {
        List<JournalLineDTO> lines = PostingService.saleLines(sale("100.00", "10.00", "0", "0", "0"));

        assertBalanced(lines, "credit sale");
        assertThat(on(lines, AR)).isEqualByComparingTo("110.00");
        assertThat(on(lines, CASH)).isEqualByComparingTo("0");
    }

    // ── delivery: the leg that rides inside grandTotal ───────────────────────────────────────────────

    @Test
    @DisplayName("delivery is credited to 4300 and NOT to Sales — and the journal still balances")
    void deliveryGetsItsOwnIncomeLine() {
        // Goods 100 + tax 10 + delivery 5. The shopper owes 115; Sales must still read 100.
        List<JournalLineDTO> lines = PostingService.saleLines(sale("100.00", "10.00", "5.00", "0", "115.00"));

        assertBalanced(lines, "sale with delivery");
        assertThat(on(lines, DELIVERY_INCOME)).isEqualByComparingTo("5.00");
        assertThat(on(lines, SALES)).as("delivery must not inflate goods revenue").isEqualByComparingTo("100.00");
        assertThat(on(lines, TAX)).as("delivery must not enter the tax register").isEqualByComparingTo("10.00");
        assertThat(on(lines, CASH)).isEqualByComparingTo("115.00");
    }

    // ── the concession ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a coupon is contra-revenue: Sales stays at the goods' list value, the concession sits on 4200")
    void discountIsContraRevenue() {
        // List goods 100, coupon 2 → sub arrives as 98. The shopper pays 98 + 10 tax = 108.
        List<JournalLineDTO> lines = PostingService.saleLines(sale("98.00", "10.00", "0", "2.00", "108.00"));

        assertBalanced(lines, "discounted sale");
        assertThat(on(lines, SALES)).as("Sales at list value, not netted down").isEqualByComparingTo("100.00");
        assertThat(on(lines, SALES_DISCOUNT)).isEqualByComparingTo("2.00");
        assertThat(on(lines, CASH)).as("the customer owes the DISCOUNTED figure").isEqualByComparingTo("108.00");
    }

    @Test
    @DisplayName("discount AND delivery together still balance — the case with every leg live")
    void discountAndDeliveryTogether() {
        // List 100, coupon 2, tax 10, delivery 5, half paid.
        List<JournalLineDTO> lines = PostingService.saleLines(sale("98.00", "10.00", "5.00", "2.00", "56.50"));

        assertBalanced(lines, "discount + delivery + part payment");
        assertThat(on(lines, SALES)).isEqualByComparingTo("100.00");
        assertThat(on(lines, SALES_DISCOUNT)).isEqualByComparingTo("2.00");
        assertThat(on(lines, DELIVERY_INCOME)).isEqualByComparingTo("5.00");
        assertThat(on(lines, CASH).add(on(lines, AR)))
                .as("tender + receivable = the whole bill").isEqualByComparingTo("113.00");
    }

    @Test
    @DisplayName("store credit, discount and delivery all at once still balance")
    void everyLegAtOnce() {
        PostEventRequest r = PostEventRequest.builder()
                .eventType("SALE").ref("INV-TEST")
                .subTotal(bd("98.00")).taxTotal(bd("10.00")).shippingFee(bd("5.00")).discountTotal(bd("2.00"))
                .grandTotal(bd("113.00")).paidAmount(bd("113.00")).storeCredit(bd("20.00")).method("CARD")
                .build();

        List<JournalLineDTO> lines = PostingService.saleLines(r);

        assertBalanced(lines, "everything at once");
        assertThat(on(lines, STORE_CREDIT)).isEqualByComparingTo("20.00");
    }

    // ── the reversal must mirror it exactly ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("voiding a sale with a discount and delivery reverses BOTH, and balances")
    void voidReversesEveryLeg() {
        PostEventRequest r = PostEventRequest.builder()
                .eventType("SALE_RETURN").ref("INV-TEST")
                .subTotal(bd("98.00")).taxTotal(bd("10.00")).shippingFee(bd("5.00")).discountTotal(bd("2.00"))
                .grandTotal(bd("113.00")).paidAmount(bd("113.00")).method("CASH")
                .build();

        List<JournalLineDTO> lines = PostingService.saleReturnLines(r);

        assertBalanced(lines, "void reversal");
        // Every account the sale touched is touched back by the same amount, or the void leaves a residue.
        assertThat(on(lines, SALES)).isEqualByComparingTo("100.00");
        assertThat(on(lines, SALES_DISCOUNT)).isEqualByComparingTo("2.00");
        assertThat(on(lines, DELIVERY_INCOME)).isEqualByComparingTo("5.00");
        assertThat(on(lines, CASH)).isEqualByComparingTo("113.00");
    }

    @Test
    @DisplayName("a PARTIAL return refunds the goods and not the delivery — unchanged from before")
    void partialReturnIsUntouched() {
        // A credit note sends neither discount nor shipping, so the journal must be exactly what it always was.
        PostEventRequest r = PostEventRequest.builder()
                .eventType("SALE_RETURN").ref("INV-TEST")
                .subTotal(bd("20.00")).taxTotal(bd("2.00")).grandTotal(bd("22.00")).paidAmount(bd("22.00"))
                .method("CASH").build();

        List<JournalLineDTO> lines = PostingService.saleReturnLines(r);

        assertBalanced(lines, "partial return");
        assertThat(on(lines, DELIVERY_INCOME)).as("delivery is never refunded on a partial return")
                .isEqualByComparingTo("0");
        assertThat(on(lines, SALES)).isEqualByComparingTo("20.00");
        assertThat(on(lines, CASH)).isEqualByComparingTo("22.00");
    }
}
