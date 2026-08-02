package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.common.settings.SettingsService;
import com.myplus.common.web.exception.ValidationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Whole-invoice margin policy (#3, slice b2b-P0).
 *
 * Pure logic — mocked settings, no database, no Docker — so it runs on every {@code mvn test}.
 *
 * The sell screen already warns per LINE as the cashier types. This guard exists because that one cannot
 * see an invoice-level discount applied after the lines are entered: the sale finishes at or below cost
 * while every individual line still looks fine.
 *
 * The cases that matter are the ones where getting it wrong is silent:
 *  - a line with NO recorded cost must not be counted as pure profit (it would make the guard useless on
 *    exactly the legacy/never-purchased products most likely to be mispriced);
 *  - a sale where NOTHING has a cost must still go through — a shop that has never recorded a purchase
 *    cannot be blocked from selling;
 *  - an unreadable policy must resolve to WARN, never OFF (standard C3 — a safety flag fails ON).
 */
@ExtendWith(MockitoExtension.class)
class MarginPolicyTest {

    @Mock private SettingsService settingsService;

    /**
     * Built by hand, not {@code @InjectMocks}. SagaSellService is {@code @RequiredArgsConstructor} with seven
     * final collaborators, so Mockito would use constructor injection — and then NOT field-inject the
     * {@code @Autowired settingsService}, leaving it null. Nulls for the constructor args are fine: the guard
     * under test touches only the settings service.
     */
    private SagaSellService service;

    @BeforeEach
    void setUp() {
        service = new SagaSellService(null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "settingsService", settingsService);
    }

    /**
     * qty 1, so netAmount and costPrice read directly as the line's money.
     *
     * <p>Every component is written out and labelled deliberately: this is the ONE place the test builds a
     * {@link SagaLine}, and widening that record has now broken this call site twice (SF-10 cost, then B2B-P2
     * priceReason). Naming each slot makes the next widening a one-line, obvious fix rather than a hunt
     * through a row of anonymous nulls.
     */
    private static SagaLine line(double net, Double cost) {
        return new SagaLine(
                1L,                                   // productId
                1f,                                   // quantity
                BigDecimal.valueOf(net),              // sellRate
                BigDecimal.ZERO,                      // discount
                BigDecimal.valueOf(net),              // totalAmount
                BigDecimal.valueOf(net),              // netAmount — what the margin guard sums
                null,                                 // srp
                null,                                 // taxRate
                null,                                 // taxAmount
                null,                                 // lineGross
                null,                                 // catalogPrice
                null,                                 // discountType
                cost == null ? null : BigDecimal.valueOf(cost),   // costPrice — null = uncosted line
                null);                                // priceReason (B2B-P2) — null = priced at catalog
    }

    private void policy(String value) {
        when(settingsService.getChoice(eq("pos.sale.marginPolicy"), any(), anyString())).thenReturn(value);
    }

    private CustomerHistoryDTO dto() {
        return new CustomerHistoryDTO();
    }

    // ── the policy values ──────────────────────────────────────────────────────

    @Test
    @DisplayName("off: no check at all, even at a loss")
    void off_does_nothing() {
        policy("off");
        CustomerHistoryDTO d = dto();

        assertThatCode(() -> service.assertMarginPolicy(List.of(line(50, 100.0)), d))
                .doesNotThrowAnyException();
        assertThat(d.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("warn (default): a loss-making sale is recorded, and the cashier is told")
    void warn_records_and_warns() {
        policy("warn");
        CustomerHistoryDTO d = dto();

        assertThatCode(() -> service.assertMarginPolicy(List.of(line(80, 100.0)), d))
                .as("warn must never stop the sale")
                .doesNotThrowAnyException();
        assertThat(d.getWarnings()).hasSize(1);
        assertThat(d.getWarnings().get(0)).contains("no profit");
    }

    @Test
    @DisplayName("block: a loss-making sale is refused before anything is reserved or written")
    void block_refuses() {
        policy("block");

        assertThatThrownBy(() -> service.assertMarginPolicy(List.of(line(80, 100.0)), dto()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    @DisplayName("a profitable sale is silent under every policy")
    void profit_is_silent() {
        for (String p : List.of("warn", "block")) {
            policy(p);
            CustomerHistoryDTO d = dto();
            assertThatCode(() -> service.assertMarginPolicy(List.of(line(150, 100.0)), d))
                    .doesNotThrowAnyException();
            assertThat(d.getWarnings()).as("policy=%s", p).isEmpty();
        }
    }

    @Test
    @DisplayName("exactly zero margin counts as no profit")
    void zero_margin_is_flagged() {
        policy("warn");
        CustomerHistoryDTO d = dto();

        service.assertMarginPolicy(List.of(line(100, 100.0)), d);

        assertThat(d.getWarnings()).as("break-even is not profit").hasSize(1);
    }

    // ── the silent-failure cases ───────────────────────────────────────────────

    @Test
    @DisplayName("a line with no recorded cost is EXCLUDED, not treated as pure profit")
    void unknown_cost_is_excluded() {
        policy("block");

        // Sold at a loss on the costed line; the uncosted line would mask it if counted as 100% margin.
        assertThatThrownBy(() -> service.assertMarginPolicy(
                List.of(line(80, 100.0), line(500, null)), dto()))
                .as("an uncosted line must not rescue a loss-making sale")
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a sale where NOTHING has a cost still goes through")
    void no_cost_anywhere_is_not_judged() {
        policy("block");
        CustomerHistoryDTO d = dto();

        assertThatCode(() -> service.assertMarginPolicy(List.of(line(50, null), line(20, null)), d))
                .as("a shop that has never recorded a purchase must not be blocked from selling")
                .doesNotThrowAnyException();
        assertThat(d.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("margin is judged across the WHOLE invoice, not per line")
    void whole_invoice_not_per_line() {
        policy("warn");
        CustomerHistoryDTO d = dto();

        // One line at a loss, one well above cost — together profitable, so nothing to say.
        service.assertMarginPolicy(List.of(line(80, 100.0), line(300, 100.0)), d);

        assertThat(d.getWarnings())
                .as("a below-cost line inside a profitable invoice is the cashier's business, not a warning")
                .isEmpty();
    }

    @Test
    @DisplayName("an empty or null line list is a no-op")
    void empty_is_safe() {
        policy("warn");
        assertThatCode(() -> service.assertMarginPolicy(List.of(), dto())).doesNotThrowAnyException();
        assertThatCode(() -> service.assertMarginPolicy(null, dto())).doesNotThrowAnyException();
    }

    // ── C3: the flag fails ON ──────────────────────────────────────────────────

    @Test
    @DisplayName("an unreadable/unknown policy resolves to warn, never off")
    void unknown_policy_fails_on() {
        // getChoice() is what enforces this — it returns the caller's fallback for anything not in the set.
        // Asserting the CALL here documents the contract the guard depends on.
        when(settingsService.getChoice(eq("pos.sale.marginPolicy"), any(), eq("warn"))).thenReturn("warn");
        CustomerHistoryDTO d = dto();

        service.assertMarginPolicy(List.of(line(80, 100.0)), d);

        assertThat(d.getWarnings()).as("a config typo must not silently disable the guard").hasSize(1);
    }
}
