package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.commerce.contracts.dto.PolicyCheckResponse;
import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * O7 D1b — the policy DRY RUN: same rules, reported instead of thrown.
 *
 * <p>Pure logic, mocked settings, no database — so it runs on every {@code mvn test}, where the Cypress gate
 * runs only against a deployed stack. Built on the same harness as {@link MarginPolicyTest} for the same
 * reason it gives: {@code SagaSellService} has seven final collaborators, and the checks under test touch only
 * the settings service.
 *
 * <p><b>What these cases are really defending.</b> The dry run's whole value is that it calls the SAME
 * {@code assertMarginPolicy} / {@code assertCreditPolicy} the sale calls. The failure mode worth guarding is
 * therefore not "the response has the wrong field" but "somebody re-implemented the rules here", which drifts
 * silently: the reviewer's panel says fine, dispatch refuses, and neither log explains the other. So every case
 * below pins an answer that a re-implementation would plausibly get wrong — the tenant's policy setting being
 * honoured, uncosted lines excluded from both sides, and warn/block reported as different things.
 */
@ExtendWith(MockitoExtension.class)
class PolicyCheckTest {

    @Mock private SettingsService settingsService;

    private SagaSellService service;

    @BeforeEach
    void setUp() {
        service = new SagaSellService(null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "settingsService", settingsService);
    }

    private void policy(String value) {
        when(settingsService.getChoice(eq("pos.sale.marginPolicy"), any(), anyString())).thenReturn(value);
    }

    /** qty 1, so netAmount and costPrice read directly as the line's money. */
    private static SagaLine line(double net, Double cost) {
        return new SagaLine(1L, 1f,
                BigDecimal.valueOf(net), BigDecimal.ZERO,
                BigDecimal.valueOf(net), BigDecimal.valueOf(net),
                null, null, null, null, null, null,
                cost == null ? null : BigDecimal.valueOf(cost),
                null, null);
    }

    /**
     * The dry run as the endpoint reaches it, minus {@code buildLines}.
     *
     * <p>{@code checkPolicy(dto)} resolves products through the repositories, which a pure test has none of.
     * The part under test is what happens to already-built lines, so the lines are handed in directly — the
     * same shape {@link MarginPolicyTest} uses, and for the same reason.
     */
    private PolicyCheckResponse check(List<SagaLine> lines, CustomerHistoryDTO dto) {
        return service.checkPolicyForLines(lines, dto);
    }

    // ── the point of the slice ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — a loss-making basket is reported, and nothing is thrown")
    void loss_is_reported_not_thrown() {
        policy("warn");
        // Sold at 50, cost 100. The sale path would append a warning and carry on; the dry run must say so
        // WITHOUT throwing, because a caller rendering one panel should not have to catch two exception types.
        PolicyCheckResponse r = check(List.of(line(50, 100.0)), new CustomerHistoryDTO());

        assertThat(r.isOk()).isFalse();
        assertThat(r.getWarnings()).isNotEmpty();
        assertThat(r.getWarnings().get(0)).containsIgnoringCase("no profit");
        assertThat(r.getMargin()).isEqualByComparingTo("-50");
        // warn is NOT block: the sale would still be recorded, so the reviewer is being informed, not refused.
        assertThat(r.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("POSITIVE CONTROL — a profitable basket reports ok, with nothing to say")
    void healthy_basket_is_ok() {
        /*
         * Without this, every "a problem is reported" assertion above would pass just as happily against an
         * implementation that complains about everything. This programme has been caught by exactly that
         * shape before — an absence assertion that went green against a 404.
         */
        policy("warn");
        PolicyCheckResponse r = check(List.of(line(150, 100.0)), new CustomerHistoryDTO());

        assertThat(r.isOk()).isTrue();
        assertThat(r.isBlocked()).isFalse();
        assertThat(r.getWarnings()).isEmpty();
        assertThat(r.getMargin()).isEqualByComparingTo("50");
    }

    // ── warn and block are different answers ────────────────────────────────────────────────────

    @Test
    @DisplayName("block: the same basket comes back BLOCKED — what the sale would actually refuse")
    void block_policy_reports_blocked() {
        policy("block");
        PolicyCheckResponse r = check(List.of(line(50, 100.0)), new CustomerHistoryDTO());

        // The distinction is the reason `blocked` exists apart from `ok`. Collapsing them would either hide a
        // real refusal or cry wolf about an advisory note.
        assertThat(r.isBlocked()).isTrue();
        assertThat(r.isOk()).isFalse();
        assertThat(r.getWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("off: the tenant switched the rule off, and the check honours that")
    void off_policy_reports_ok() {
        // A dry run that warned anyway would be a second policy — the exact drift this slice exists to avoid.
        policy("off");
        PolicyCheckResponse r = check(List.of(line(50, 100.0)), new CustomerHistoryDTO());

        assertThat(r.isOk()).isTrue();
        assertThat(r.getWarnings()).isEmpty();
        // The MEASUREMENT is still reported: the rule is off, the arithmetic is not wrong.
        assertThat(r.getMargin()).isEqualByComparingTo("-50");
    }

    // ── the arithmetic the rule already defines ─────────────────────────────────────────────────

    @Test
    @DisplayName("an uncosted line is excluded from BOTH sides, not counted as pure profit")
    void uncosted_line_excluded_from_both_sides() {
        /*
         * The line sells for 500 with no recorded cost. Counting it as profit would swamp the real loss on the
         * costed line and report a healthy basket — and it would do so on exactly the legacy or
         * never-purchased products most likely to be mispriced.
         */
        policy("warn");
        PolicyCheckResponse r = check(List.of(line(50, 100.0), line(500, null)), new CustomerHistoryDTO());

        assertThat(r.getMargin()).isEqualByComparingTo("-50");
        assertThat(r.isOk()).isFalse();
        // netTotal is the WHOLE basket — it is what the customer pays, uncosted lines included. Only the
        // MARGIN excludes them, which is why the two figures legitimately disagree.
        assertThat(r.getNetTotal()).isEqualByComparingTo("550");
    }

    @Test
    @DisplayName("no line has a cost: margin is NULL, not zero, and nothing is raised")
    void no_costs_at_all_reports_null_margin() {
        /*
         * A shop that has never recorded a purchase has nothing to judge. Reporting 0 here would read as "no
         * profit" and send someone hunting a pricing error that does not exist — the same reason
         * assertMarginPolicy returns early rather than treating unknown as zero.
         */
        policy("warn");
        PolicyCheckResponse r = check(List.of(line(50, null), line(70, null)), new CustomerHistoryDTO());

        assertThat(r.getMargin()).isNull();
        assertThat(r.isOk()).isTrue();
        assertThat(r.getWarnings()).isEmpty();
        assertThat(r.getNetTotal()).isEqualByComparingTo("120");
    }

    // ── it must not invent findings ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("warnings the CALLER already put on the request are not echoed back as findings")
    void preexisting_warnings_are_not_reported_as_findings() {
        /*
         * assertMarginPolicy APPENDS to dto.getWarnings() under `warn`, so the dry run collects what this call
         * added by remembering the size beforehand. If it returned the whole list instead, a request that
         * arrived carrying warnings would report them as though the check had raised them — and the panel
         * would show a problem that no rule found.
         */
        policy("warn");
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.getWarnings().add("something the caller said earlier");

        PolicyCheckResponse r = check(List.of(line(150, 100.0)), dto);

        assertThat(r.isOk()).isTrue();
        assertThat(r.getWarnings()).isEmpty();
    }
}
