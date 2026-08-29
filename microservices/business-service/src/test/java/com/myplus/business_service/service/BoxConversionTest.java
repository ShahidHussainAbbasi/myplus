package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.StockDTO;

/**
 * U5 — turning a delivery counted in boxes into stock counted in packs.
 *
 * <p>Design: {@code docs/slices/u5-buying-in-boxes.md}. Pure logic, no database, no Docker — it runs on every
 * {@code mvn test}, because this arithmetic decides what every later margin report says.
 *
 * <p><b>The defect being prevented:</b> a buyer keys a box of 10 packs costing 1000 as "10 @ 1000", and the
 * product's cost becomes 1000 per pack instead of 100. Nothing errors. The error only surfaces later, as
 * impossible margins and a guard refusing sales the shop can make.
 */
class BoxConversionTest {

    private static PurchaseDTO delivery(String unit, Integer packsPerBox, float qty, String rate) {
        PurchaseDTO d = new PurchaseDTO();
        d.setPurchaseUnit(unit);
        d.setPacksPerBox(packsPerBox);
        d.setQuantity(qty);
        StockDTO st = new StockDTO();
        st.setBpurchaseRate(new BigDecimal(rate));
        st.setBsellRate(new BigDecimal("150.00"));
        d.setStock(st);
        return d;
    }

    // ── ⭐ the case the slice exists for ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ 10 boxes of 10 at 1000.00 becomes 100 packs at 100.00")
    void a_box_delivery_becomes_packs_and_a_per_pack_cost() {
        PurchaseDTO d = delivery("BOX", 10, 10f, "1000.00");

        PurchaseService.convertBoxesToPacks(d);

        assertThat(d.getQuantity()).as("100 packs on the shelf").isEqualTo(100f);
        assertThat(d.getStock().getBpurchaseRate()).as("cost per PACK — the number COGS reads")
                .isEqualByComparingTo("100.00");

        // The bill is unchanged: 10 x 1000 and 100 x 100 are the same money. If this ever stops holding,
        // the conversion has invented or destroyed value.
        assertThat(d.getStock().getBpurchaseRate().multiply(BigDecimal.valueOf(d.getQuantity())))
                .as("what the supplier charged").isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("⭐ the SELLING price is never converted")
    void the_shelf_price_is_left_alone() {
        // stampRatesOnProduct pushes bsellRate onto the product as its selling price. Converting it would
        // reprice the product to a tenth of its shelf price — the same error this method prevents, inverted.
        PurchaseDTO d = delivery("BOX", 10, 10f, "1000.00");

        PurchaseService.convertBoxesToPacks(d);

        assertThat(d.getStock().getBsellRate()).as("a shop prices its shelf in packs, whatever it bought in")
                .isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("nothing downstream hears the word \"box\"")
    void the_box_does_not_survive_the_conversion() {
        // The property that keeps this an INPUT AID rather than a second unit of measure (parent design §4):
        // delete the feature tomorrow and every stored row still means exactly what it means today.
        PurchaseDTO d = delivery("BOX", 10, 10f, "1000.00");

        PurchaseService.convertBoxesToPacks(d);

        assertThat(d.getPurchaseUnit()).isEqualTo("PACK");
        assertThat(d.getPacksPerBox()).isNull();
    }

    // ── the ordinary purchase, which is every purchase until a shop uses this ─────────────────────────

    @Test
    @DisplayName("an ordinary purchase is untouched")
    void a_pack_purchase_passes_straight_through() {
        PurchaseDTO d = delivery(null, null, 10f, "100.00");

        PurchaseService.convertBoxesToPacks(d);

        assertThat(d.getQuantity()).isEqualTo(10f);
        assertThat(d.getStock().getBpurchaseRate()).isEqualByComparingTo("100.00");
        assertThat(d.getPurchaseUnit()).isNull();
    }

    @Test
    @DisplayName("an explicit PACK unit is also untouched, and a stray packsPerBox is ignored")
    void an_explicit_pack_unit_converts_nothing() {
        PurchaseDTO d = delivery("PACK", 10, 10f, "100.00");

        PurchaseService.convertBoxesToPacks(d);

        assertThat(d.getQuantity()).as("10 packs, not 100").isEqualTo(10f);
        assertThat(d.getStock().getBpurchaseRate()).isEqualByComparingTo("100.00");
    }

    // ── rounding: a cost must never be understated ────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a box price that does not divide rounds the cost UP, never down")
    void an_awkward_box_never_understates_the_cost() {
        // 1000 / 3 = 333.333... and bpurchase_rate is DECIMAL(19,2), so a residue is unavoidable.
        // CEILING, because an UNDERSTATED cost silently overstates every margin report — and the margin
        // guard reads exactly this number to decide whether to refuse a sale.
        PurchaseDTO d = delivery("BOX", 3, 1f, "1000.00");

        PurchaseService.convertBoxesToPacks(d);

        assertThat(d.getQuantity()).isEqualTo(3f);
        assertThat(d.getStock().getBpurchaseRate()).isEqualByComparingTo("333.34");

        // Cost recovered across the packs is never LESS than what was paid.
        assertThat(d.getStock().getBpurchaseRate().multiply(BigDecimal.valueOf(3)))
                .isGreaterThanOrEqualTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("the cost is never understated, swept across many box sizes and prices")
    void the_shop_never_flatters_its_own_margin() {
        int checked = 0;
        for (int packs = 2; packs <= 48; packs++) {
            for (int paisa = 101; paisa <= 500_000; paisa += 7919) {   // an odd step, so thirds and sevenths land
                BigDecimal boxCost = BigDecimal.valueOf(paisa, 2);
                PurchaseDTO d = delivery("BOX", packs, 1f, boxCost.toPlainString());

                PurchaseService.convertBoxesToPacks(d);

                BigDecimal recovered = d.getStock().getBpurchaseRate().multiply(BigDecimal.valueOf(packs));
                assertThat(recovered)
                        .as("box of %d at %s: the recorded cost must never be less than what was paid",
                                packs, boxCost.toPlainString())
                        .isGreaterThanOrEqualTo(boxCost);
                assertThat(recovered.subtract(boxCost))
                        .as("box of %d at %s: and the excess is rounding, not a markup", packs,
                                boxCost.toPlainString())
                        .isLessThan(BigDecimal.valueOf(packs, 2));
                checked++;
            }
        }
        assertThat(checked).as("the sweep actually ran").isGreaterThan(2_000);
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BOX with no packs-per-box is refused")
    void the_conversion_factor_is_required() {
        assertThatThrownBy(() -> PurchaseService.convertBoxesToPacks(delivery("BOX", null, 10f, "1000.00")))
                .hasMessageContaining("How many packs are in a box");
        assertThatThrownBy(() -> PurchaseService.convertBoxesToPacks(delivery("BOX", 0, 10f, "1000.00")))
                .hasMessageContaining("How many packs are in a box");
    }

    @Test
    @DisplayName("an absurd packs-per-box is refused rather than creating a warehouse")
    void a_typo_cannot_create_a_million_packs() {
        assertThatThrownBy(() -> PurchaseService.convertBoxesToPacks(delivery("BOX", 100_000, 10f, "1000.00")))
                .hasMessageContaining("looks like a typo");
    }

    @Test
    @DisplayName("a zero box cost is refused — it would make every later sale look like pure profit")
    void a_free_box_is_not_a_purchase() {
        assertThatThrownBy(() -> PurchaseService.convertBoxesToPacks(delivery("BOX", 10, 10f, "0.00")))
                .hasMessageContaining("cost of one box");
    }

    @Test
    @DisplayName("zero boxes is refused")
    void a_delivery_of_nothing_is_refused() {
        assertThatThrownBy(() -> PurchaseService.convertBoxesToPacks(delivery("BOX", 10, 0f, "1000.00")))
                .hasMessageContaining("how many boxes");
    }
}
