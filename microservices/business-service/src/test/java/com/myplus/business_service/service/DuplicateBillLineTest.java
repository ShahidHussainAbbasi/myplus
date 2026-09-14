package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.Purchase;

/**
 * DOC-INT C — the "same line" rule, pure logic, no Spring and no database.
 *
 * <p>The database only narrows to the bill's own lines; every decision below is made here. So each case pins one
 * clause of the rule, and the regressions (a second product, a second batch) are the ones that protect real work:
 * a guard that prompted on every multi-line bill would be switched off by the first shop that met it.
 */
class DuplicateBillLineTest {

    private static Purchase line(Long productId, String batch, String status) {
        Purchase p = new Purchase();
        p.setProductId(productId);
        p.setBatchNo(batch);
        p.setStatus(status);
        return p;
    }

    @Test
    void the_same_product_with_no_batch_is_the_same_line() {
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, null)), 7L, null)).isPresent();
    }

    @Test
    void blank_and_missing_batch_are_the_same_batch() {
        assertThat(DuplicateBillLine.find(List.of(line(7L, "", null)), 7L, "   ")).isPresent();
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, null)), 7L, "")).isPresent();
    }

    @Test
    void the_batch_is_compared_trimmed_and_case_insensitively() {
        assertThat(DuplicateBillLine.find(List.of(line(7L, "B1", null)), 7L, " b1 ")).isPresent();
    }

    @Test
    void REGRESSION_a_different_product_on_the_bill_is_a_new_line() {
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, null)), 8L, null)).isEmpty();
    }

    @Test
    void REGRESSION_the_same_product_in_a_different_batch_is_a_new_line() {
        assertThat(DuplicateBillLine.find(List.of(line(7L, "B1", null)), 7L, "B2")).isEmpty();
        assertThat(DuplicateBillLine.find(List.of(line(7L, "B1", null)), 7L, null)).isEmpty();
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, null)), 7L, "B1")).isEmpty();
    }

    @Test
    void a_void_line_is_history_not_a_line() {
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, "VOID")), 7L, null)).isEmpty();
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, "ACTIVE")), 7L, null)).isPresent();
    }

    @Test
    void nothing_to_compare_means_no_match() {
        assertThat(DuplicateBillLine.find(List.of(), 7L, null)).isEmpty();
        assertThat(DuplicateBillLine.find(null, 7L, null)).isEmpty();
        assertThat(DuplicateBillLine.find(List.of(line(7L, null, null)), null, null)).isEmpty();
    }

    @Test
    void the_message_names_the_bill_the_vendor_the_batch_the_day_and_the_quantity() {
        Purchase prior = line(7L, "B1", null);
        prior.setDated(LocalDateTime.of(2026, 9, 12, 14, 5));
        prior.setQuantity(10f);
        assertThat(DuplicateBillLine.message("4471", " ACME ", prior))
                .isEqualTo("Bill 4471 from ACME already has this product (batch B1) — saved 12-09-2026, qty 10."
                        + " Save this line again?");
    }

    @Test
    void the_message_leaves_out_what_it_does_not_know() {
        Purchase prior = line(7L, null, null);
        prior.setQuantity(2.5f);
        assertThat(DuplicateBillLine.message("4471", null, prior))
                .isEqualTo("Bill 4471 already has this product, qty 2.5. Save this line again?");
    }
}
