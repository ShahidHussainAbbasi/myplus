package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
 */
class CreditNoteSnapshotTest {

    @Test
    @DisplayName("⭐⭐ a LOOSE request records the pieces it asked for")
    void looseRequestRecordsItsOwnPieces() {
        // "return 3 tablets" — the caller already speaks in pieces, so nothing is derived.
        assertThat(SellController.piecesReturned(true, 3f, 10f, 0.075f, 0.25f)).isEqualTo(3f);
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
        assertThat(SellController.piecesReturned(false, 0f, 10f, 0.075f, 0.25f)).isEqualTo(3f);
        assertThat(SellController.piecesReturned(false, 0f, 10f, 0.125f, 0.25f)).isEqualTo(5f);
    }

    @Test
    @DisplayName("⭐⭐ the awkward pack: a box of 3, one tablet — a full return records every piece")
    void aFullReturnRecordsEveryPieceEvenWhenTheDivisionDoesNotTerminate() {
        /*
         * One tablet from a box of 3 stores 0.333… A full return passes that same stored figure back, and the
         * snapshot must say 1 piece — not 0 (truncation) and not 0.999. This is the case that breaks any
         * implementation which divides by the pack size instead of scaling the line.
         */
        assertThat(SellController.piecesReturned(false, 0f, 1f, 0.3333f, 0.3333f)).isEqualTo(1f);
        // ...and the same line returned through the loose path agrees, which is the property that matters:
        // two ways of asking for one return must record the same number of tablets.
        assertThat(SellController.piecesReturned(true, 1f, 1f, 0.3333f, 0.3333f)).isEqualTo(1f);
    }

    @Test
    @DisplayName("⭐ pieces are whole things — a proportion that lands on 2.9999998 records 3")
    void piecesAreRounded() {
        // 6 of 7 tablets from a line stored as 0.7: the float arithmetic lands just under 6.
        assertThat(SellController.piecesReturned(false, 0f, 7f, 0.6f, 0.7f)).isEqualTo(6f);
    }

    @Test
    @DisplayName("a degenerate line records nothing rather than dividing by zero")
    void aZeroLineIsSafe() {
        // Neither can happen through the controller (both are guarded above), but a helper that throws on the
        // impossible input turns a bad row into a failed RETURN — and the return is already applied by then.
        assertThat(SellController.piecesReturned(false, 0f, 10f, 0.1f, 0f)).isEqualTo(0f);
        assertThat(SellController.piecesReturned(false, 0f, 0f, 0.1f, 0.25f)).isEqualTo(0f);
    }

    @Test
    @DisplayName("⭐ the two halves of one return agree: pieces back and packs back describe the same goods")
    void theInverseAgreesWithU13() {
        /*
         * packsForLooseReturn (U13) answers "how much shelf goes back for 3 tablets"; piecesReturned (CN-1)
         * answers "how many tablets was that". Round-tripping must return the number the customer asked for,
         * or the document and the stock ledger would describe different returns.
         */
        float packsBack = SellController.packsForLooseReturn(0.25f, 10f, 3f);
        assertThat(SellController.piecesReturned(false, 0f, 10f, packsBack, 0.25f)).isEqualTo(3f);

        float packsBackAwkward = SellController.packsForLooseReturn(0.3333f, 3f, 2f);
        assertThat(SellController.piecesReturned(false, 0f, 3f, packsBackAwkward, 0.3333f)).isEqualTo(2f);
    }
}
