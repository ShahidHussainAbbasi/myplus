package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DOC-FIELDS — the server's document field whitelist, pinned where a unit test can see it.
 *
 * <h3>The defect this records</h3>
 * {@code DocumentProfileValidator.HEADER_FIELDS} and {@code FIELD_WHITELIST} in {@code receipt.js} are
 * duplicated across the language boundary DELIBERATELY: the client renders from its copy and the server
 * validates against its own, because a server that trusts the client's list is not validating anything. The
 * cost of that choice is that the two can drift — and they had.
 *
 * <p>Task #28 shipped five QUOTE header fields in the renderer, with their own {@code ui.js.doc*} labels and
 * resolvers, and the {@code QUOTE_A4} preset BINDS two of them. The server list never learned any of them.
 * Since {@code FIELD_WHITELIST} is what the DESIGNER offers, a shop could place "Quote #" or "Valid until" on
 * a quote layout, press save, and be refused — for a field the product itself had put in front of them. Saving
 * the renderer's own quote preset unmodified was refused too.
 *
 * <h3>What this test can and cannot do</h3>
 * It cannot read {@code receipt.js}: that file belongs to the monolith, not to this module, and a unit test
 * reaching across repositories to parse JavaScript would break for reasons that have nothing to do with the
 * rule it checks. The true cross-language pin is {@code document-designer.cy.js}'s contract case, which asks
 * the running server and the loaded renderer and compares them — and that is the case that CAUGHT this.
 *
 * <p>What this adds is the half a unit test can hold: the documented groups are present, so a later edit that
 * drops one fails here in seconds rather than in a browser gate minutes later.
 */
class DocumentFieldWhitelistTest {

    private final Map<String, List<String>> whitelist = new DocumentProfileValidator().whitelist();

    @Test
    @DisplayName("⭐⭐ the QUOTE fields are valid on the server — the drift that refused a layout the designer offered")
    void quoteHeaderFieldsAreAccepted() {
        assertThat(whitelist.get("header"))
                .as("Task #28's quote fields, bound by QUOTE_A4 and offered by the designer")
                .contains("quoteNo", "quoteStatus", "quoteValidUntil", "quotePoNumber", "quoteInvoiceNo");
    }

    @Test
    @DisplayName("⭐ the RETURN document fields are still there — a credit note is not an invoice")
    void returnDocumentFieldsSurvive() {
        // Task #15 kept these separate from invoiceNo on purpose: the LABEL is the point, and a credit note
        // printed under an "Invoice #" heading is the confusion the note numbers exist to end.
        assertThat(whitelist.get("header"))
                .contains("creditNoteNo", "debitNoteNo", "referenceNo", "returnReason", "supplierName");
    }

    @Test
    @DisplayName("⭐ the everyday invoice fields are untouched by any of it")
    void invoiceHeaderFieldsSurvive() {
        assertThat(whitelist.get("header"))
                .contains("invoiceNo", "dated", "customerName", "customerMobile", "paymentMode");
    }

    @Test
    @DisplayName("⭐ ONE-DISCOUNT-ROW: the till slips' new fields are accepted, so a shop can lay them out and save")
    void oneDiscountRowFieldsAreAccepted() {
        // receipt.js's two 80mm presets bind these; a designer that offers them and a server that refuses them
        // is the unitRate / "Quote #" drift again.
        assertThat(whitelist.get("line")).contains("lineAmount");
        assertThat(whitelist.get("totals")).contains("subTotalGross", "totalDiscount");
    }

    @Test
    @DisplayName("the three groups are all published, sorted, and none is empty")
    void everyGroupIsPublished() {
        // A group that quietly became empty would make the designer offer nothing for it while the renderer
        // kept binding fields — the same class of silent divergence, one level up.
        for (String group : new String[] { "header", "line", "totals" }) {
            List<String> keys = whitelist.get(group);
            assertThat(keys).as("%s is published", group).isNotNull().isNotEmpty();
            assertThat(keys).as("%s is sorted, so the client's sorted compare can match it", group)
                    .isSorted();
        }
    }

    @Test
    @DisplayName("⭐⭐ the LINE fields the renderer binds are accepted — unitRate was refused though offered")
    void lineFieldsTheRendererBindsAreAccepted() {
        /*
         * Found by sweeping the two lists in BOTH directions, which the failing gate did not ask for: it
         * reported the header mismatch only. `unitRate` carries ui.js.docRate and is bound by two presets, so
         * a Rate column could be laid out and then refused on save — the same defect as "Quote #", a level
         * down, and invisible to anyone who fixed only what the red told them.
         */
        assertThat(whitelist.get("line"))
                .contains("unitRate", "quantity", "itemName", "lineTotal", "taxAmount");
    }

    @Test
    @DisplayName("an unknown field is still refused — widening the list must not turn the gate off")
    void unknownFieldsAreStillRejected() {
        // The whole point of the server's own copy. Adding the quote fields must not have made the validator
        // permissive: a key nobody declared is still not a key a layout may use.
        assertThat(whitelist.get("header")).doesNotContain("notAField", "customerSecret", "quoteAnything");
    }
}
