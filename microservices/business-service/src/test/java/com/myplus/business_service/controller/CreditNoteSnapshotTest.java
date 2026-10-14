package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CN-1 — the pieces a credit note records, so the printed document can say what the customer bought.
 *
 * <h3>The defect</h3>
 * {@code sale_return} stored only the SHELF figure, so a return of three tablets from a box of forty printed
 * "0.075 × 311.60" on the one piece of paper the customer takes away. U13 converted the return DIALOG and the
 * sale grid; the document was never converted. Live in dev: CRN-000038, quantity 0.075.
 *
 * <h3>Why the pieces must be computed here and not at print time</h3>
 * The customer's view lives on the {@code Sell} line, and a FULL return DELETES that line
 * ({@code sellService.deleteById}). A print-time lookup therefore has nothing to read. V65 snapshots the
 * pieces at return time; this is the arithmetic that fills it.
 *
 * <p>Pure JUnit — no database, no HTTP — for the same reason {@link LooseReturnQuantityTest} is.
 * NUM-1 (2026-10-14): exact {@code BigDecimal}; "rounded" is HALF_UP to a whole piece, as {@code Math.round} was.
 */
class CreditNoteSnapshotTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    @DisplayName("⭐⭐ a LOOSE request records the pieces it asked for")
    void looseRequestRecordsItsOwnPieces() {
        // "return 3 tablets" — the caller already speaks in pieces, so nothing is derived.
        assertThat(SellController.piecesReturned(true, d("3"), d("10"), d("0.075"), d("0.25"))).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("⭐⭐ an OLD-CONTRACT request in shelf units records the proportional pieces")
    void shelfUnitRequestConvertsToPieces() {
        /*
         * The compatibility path: no returnUnit, so the caller states a fraction of a box. 0.075 of a 0.25
         * line that was sold as 10 tablets is 3 tablets. Derived as a PROPORTION OF THE LINE, never as
         * 0.075 × packSize — the sale derived the shelf figure once and a second derivation would disagree
         * with it whenever the division does not terminate.
         */
        assertThat(SellController.piecesReturned(false, d("0"), d("10"), d("0.075"), d("0.25"))).isEqualByComparingTo("3");
        assertThat(SellController.piecesReturned(false, d("0"), d("10"), d("0.125"), d("0.25"))).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("⭐⭐ the awkward pack: a box of 3, one tablet — a full return records every piece")
    void aFullReturnRecordsEveryPieceEvenWhenTheDivisionDoesNotTerminate() {
        /*
         * One tablet from a box of 3 stores 0.3333. A full return passes that same stored figure back, and the
         * snapshot must say 1 piece — not 0 (truncation) and not 0.999. This is the case that breaks any
         * implementation which divides by the pack size instead of scaling the line.
         */
        assertThat(SellController.piecesReturned(false, d("0"), d("1"), d("0.3333"), d("0.3333"))).isEqualByComparingTo("1");
        // ...and the same line returned through the loose path agrees, which is the property that matters:
        // two ways of asking for one return must record the same number of tablets.
        assertThat(SellController.piecesReturned(true, d("1"), d("1"), d("0.3333"), d("0.3333"))).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("⭐ pieces are whole things — a proportion is rounded to a whole piece")
    void piecesAreRounded() {
        // 6 of 7 tablets from a line stored as 0.7 — exact now; the float version landed just under 6.
        assertThat(SellController.piecesReturned(false, d("0"), d("7"), d("0.6"), d("0.7"))).isEqualByComparingTo("6");
        // and a proportion that is not whole rounds half up: 10 × 0.0875 / 0.25 = 3.5 → 4
        assertThat(SellController.piecesReturned(false, d("0"), d("10"), d("0.0875"), d("0.25"))).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a degenerate line records nothing rather than dividing by zero")
    void aZeroLineIsSafe() {
        // Neither can happen through the controller (both are guarded above), but a helper that throws on the
        // impossible input turns a bad row into a failed RETURN — and the return is already applied by then.
        assertThat(SellController.piecesReturned(false, d("0"), d("10"), d("0.1"), d("0"))).isZero();
        assertThat(SellController.piecesReturned(false, d("0"), d("0"), d("0.1"), d("0.25"))).isZero();
    }

    @Test
    @DisplayName("⭐ the two halves of one return agree: pieces back and packs back describe the same goods")
    void theInverseAgreesWithU13() {
        /*
         * packsForLooseReturn (U13) answers "how much shelf goes back for 3 tablets"; piecesReturned (CN-1)
         * answers "how many tablets was that". Round-tripping must return the number the customer asked for,
         * or the document and the stock ledger would describe different returns.
         */
        BigDecimal packsBack = SellController.packsForLooseReturn(d("0.25"), d("10"), d("3"));
        assertThat(SellController.piecesReturned(false, d("0"), d("10"), packsBack, d("0.25"))).isEqualByComparingTo("3");

        BigDecimal packsBackAwkward = SellController.packsForLooseReturn(d("0.3333"), d("3"), d("2"));
        assertThat(SellController.piecesReturned(false, d("0"), d("3"), packsBackAwkward, d("0.3333"))).isEqualByComparingTo("2");
    }
}
