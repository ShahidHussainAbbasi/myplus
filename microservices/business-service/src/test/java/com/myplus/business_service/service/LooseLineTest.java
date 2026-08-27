package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.business_service.dto.SellDTO;
import com.myplus.commerce.contracts.dto.ProductRef;

/**
 * U2 — the arithmetic of breaking a pack, as pure logic.
 *
 * <p>Design: {@code docs/slices/u2-loose-sale-arithmetic.md}. Runs on every {@code mvn test} — no database, no
 * Docker, no deployed stack — because this is the code that decides what a customer is charged, and a test
 * that only runs against a running system is a test that stops running.
 *
 * <p><b>What these cases are really guarding.</b> The failure this slice can produce is not a crash. It is a
 * line priced per pack instead of per piece — a customer charged 120 for five tablets, or 12 for a whole
 * pack — and nothing in the system would object, because both are perfectly well-formed sales.
 */
class LooseLineTest {

    private static final String NAME = "Panadol";

    private static ProductRef product(Integer packSize, boolean allowLoose) {
        return ProductRef.builder()
                .id(1L).name(NAME)
                .packSize(packSize)
                .allowLoose(allowLoose)
                .looseUnit("tablet").looseUnitPlural("tablets")
                .build();
    }

    private static SellDTO asks(double pieces) {
        SellDTO s = new SellDTO();
        s.setSoldUnit("LOOSE");
        s.setSoldQuantity((float) pieces);
        return s;
    }

    private static SagaSellService.LooseLine price(double pieces, int packSize, String packRate, String markupPct) {
        return SagaSellService.looseLine(asks(pieces), product(packSize, true), NAME,
                new BigDecimal(packRate), new BigDecimal(markupPct));
    }

    // ── ⭐ the case the slice exists for ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ 5 tablets of a 120.00 pack of 10 cost 60.00 — and the line identity still holds")
    void five_tablets_of_a_ten_pack() {
        SagaSellService.LooseLine l = price(5, 10, "120.00", "0");

        assertThat(l.lineTotal()).as("what the customer pays").isEqualByComparingTo("60.00");
        assertThat(l.perPiece()).as("per tablet, for the receipt").isEqualByComparingTo("12.00");
        assertThat(l.quantity()).as("half a pack leaves the shelf").isEqualByComparingTo("0.5");
        assertThat(l.packSize()).as("frozen at the sale").isEqualTo(10);

        // THE INVARIANT EVERY REPORT IN THIS SYSTEM SUMS. An earlier draft of the design stored quantity 0.5
        // WITH a rate of 12.00, which totals 6.00 — a tenfold variance in every invoice, report, tax return
        // and audit export, because `total = quantity x rate` is what a line MEANS.
        assertThat(l.quantity().multiply(l.lineRate()).setScale(2, java.math.RoundingMode.HALF_UP))
                .as("quantity x sellRate must equal the line total")
                .isEqualByComparingTo(l.lineTotal());
    }

    // ── whole packs are priced as packs ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ ten tablets of a ten-pack is a PACK — it never costs more than the sealed pack beside it")
    void a_full_pack_asked_for_in_pieces_is_priced_as_a_pack() {
        // With a markup set, the naive answer is 10 x 13.20 = 132.00 for goods on the shelf at 120.00, and
        // the customer can see both prices. This is the case that makes the shop look like it is overcharging.
        SagaSellService.LooseLine l = price(10, 10, "120.00", "10");

        assertThat(l.lineTotal()).as("the pack price, markup NOT applied").isEqualByComparingTo("120.00");
        assertThat(l.quantity()).isEqualByComparingTo("1");
        assertThat(l.perPiece()).as("effective rate per tablet").isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("25 tablets of a ten-pack is 2 packs and 5 loose — what the counter would do by hand")
    void a_mixed_request_splits_into_whole_packs_and_a_remainder() {
        SagaSellService.LooseLine l = price(25, 10, "120.00", "10");

        // 2 x 120.00 (packs, no markup) + 5 x 13.20 (loose, marked up) = 306.00
        assertThat(l.lineTotal()).isEqualByComparingTo("306.00");
        assertThat(l.quantity()).isEqualByComparingTo("2.5");
        assertThat(l.quantity().multiply(l.lineRate()).setScale(2, java.math.RoundingMode.HALF_UP))
                .as("the identity holds for a mixed line too").isEqualByComparingTo(l.lineTotal());
    }

    // ── rounding, which is where the money quietly leaks ──────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a pack of 3 at 100.00: one tablet is 33.34, not 33.33")
    void the_rounding_goes_to_the_shop_because_breaking_a_pack_destroys_value() {
        // 100 / 3 = 33.333... Rounding DOWN means three tablets sold singly return 99.99 for goods priced
        // 100.00 — a loss on every broken pack, invisible, on the fastest-moving lines in the shop.
        assertThat(price(1, 3, "100.00", "0").lineTotal()).isEqualByComparingTo("33.34");

        // Three SEPARATE single sales bill 100.02. That is the honest consequence and it is visible on the
        // receipt, rather than a hidden 0.01 leaking out of the shop each time.
        BigDecimal three = price(1, 3, "100.00", "0").lineTotal()
                .multiply(BigDecimal.valueOf(3));
        assertThat(three).as("three single sales").isEqualByComparingTo("100.02");

        // But three tablets on ONE line is a whole pack, and a whole pack costs the pack price.
        assertThat(price(3, 3, "100.00", "0").lineTotal())
                .as("one line of three = a pack").isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("⭐ the shop never loses on a broken pack — swept across pack sizes 2..24 and many prices")
    void the_loose_rate_never_undercharges_for_a_whole_pack() {
        // A fixture that divides cleanly is not a test of rounding (INST-5a). So: every pack size from 2 to
        // 24 — including 3, 6, 7, 9, 11, 13 which never divide exactly — against prices chosen to land on
        // awkward thirds and sevenths.
        int checked = 0;
        for (int packSize = 2; packSize <= 24; packSize++) {
            for (int paisa = 1; paisa <= 50_000; paisa += 337) {       // 1 paisa .. 500.00, an odd step
                BigDecimal packRate = BigDecimal.valueOf(paisa, 2);
                SagaSellService.LooseLine one = price(1, packSize, packRate.toPlainString(), "0");
                BigDecimal recovered = one.lineTotal().multiply(BigDecimal.valueOf(packSize));

                assertThat(recovered)
                        .as("pack of %d at %s: selling every piece singly must recover the pack price",
                                packSize, packRate.toPlainString())
                        .isGreaterThanOrEqualTo(packRate);

                // ...and the shop must not be overcharging either: the excess is the rounding of ONE piece,
                // so it is strictly under one paisa per piece.
                assertThat(recovered.subtract(packRate))
                        .as("pack of %d at %s: excess must be rounding, not a markup",
                                packSize, packRate.toPlainString())
                        .isLessThan(BigDecimal.valueOf(packSize, 2));
                checked++;
            }
        }
        assertThat(checked).as("the sweep actually ran").isGreaterThan(3_000);
    }

    @Test
    @DisplayName("the markup lifts only the loose part")
    void markup_applies_to_pieces_not_packs() {
        assertThat(price(1, 10, "120.00", "0").perPiece()).isEqualByComparingTo("12.00");
        assertThat(price(1, 10, "120.00", "10").perPiece()).isEqualByComparingTo("13.20");
        assertThat(price(1, 10, "120.00", "2.5").perPiece()).as("2.5% is a real answer").isEqualByComparingTo("12.30");
    }

    @Test
    @DisplayName("a null markup is 0, not a crash")
    void an_absent_markup_setting_changes_nothing() {
        SagaSellService.LooseLine l = SagaSellService.looseLine(
                asks(5), product(10, true), NAME, new BigDecimal("120.00"), null);
        assertThat(l.lineTotal()).isEqualByComparingTo("60.00");
    }

    // ── refusals: server-side, because the till is only one caller ────────────────────────────────────────

    @Test
    @DisplayName("a product that may not be split refuses a loose line")
    void allow_loose_is_the_control() {
        assertThatThrownBy(() -> SagaSellService.looseLine(
                asks(5), product(10, false), NAME, new BigDecimal("120.00"), BigDecimal.ZERO))
                .hasMessageContaining("not sold by the piece");
    }

    @Test
    @DisplayName("a product with no pack size has nothing to divide")
    void pack_size_is_required() {
        assertThatThrownBy(() -> SagaSellService.looseLine(
                asks(5), product(null, true), NAME, new BigDecimal("120.00"), BigDecimal.ZERO))
                .hasMessageContaining("nothing to divide");
        assertThatThrownBy(() -> SagaSellService.looseLine(
                asks(5), product(1, true), NAME, new BigDecimal("120.00"), BigDecimal.ZERO))
                .as("a pack of one is not divisible either").hasMessageContaining("nothing to divide");
    }

    @Test
    @DisplayName("half a tablet is refused, not rounded")
    void pieces_must_be_whole() {
        // Rounding it would be worse than refusing: either the customer pays for a piece they did not get,
        // or the shop gives one away — and neither is visible afterwards.
        assertThatThrownBy(() -> price(2.5, 10, "120.00", "0"))
                .hasMessageContaining("whole tablets");
    }

    @Test
    @DisplayName("zero and negative are refused")
    void pieces_must_be_positive() {
        assertThatThrownBy(() -> price(0, 10, "120.00", "0")).hasMessageContaining("above zero");
        assertThatThrownBy(() -> price(-3, 10, "120.00", "0")).hasMessageContaining("above zero");
    }

    @Test
    @DisplayName("a pack too large to represent is refused rather than sold as 0.0000")
    void an_absurd_pack_size_cannot_round_the_quantity_away() {
        // quantity is scale 4. One piece of a 20,000-piece pack is 0.00005, which rounds to 0.0001 or 0.0000
        // depending on the wind — and a line with quantity 0 moves no stock while taking the customer's money.
        assertThatThrownBy(() -> price(1, 20_000, "120.00", "0"))
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("the loose rate follows the rate the line actually got, not the list price")
    void a_contract_price_is_divided_not_the_list_price() {
        // buildLines resolves contract/tier prices BEFORE this is called and passes the winner in, so a
        // customer on a negotiated price gets their price divided. If this took the catalog price instead,
        // every B2B customer would silently lose their discount the moment they bought a single piece.
        assertThat(price(5, 10, "100.00", "0").lineTotal())
                .as("a 100.00 contract price, not the 120.00 list").isEqualByComparingTo("50.00");
    }
}
