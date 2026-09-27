package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.business_service.config.InstallmentMarkupGuard;
import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.repository.InstallmentPlanRepo;
import com.myplus.common.installment.Frequency;
import com.myplus.common.installment.InstallmentEligibilityPolicy.Decision;
import com.myplus.common.installment.PlanTerms;
import com.myplus.common.settings.SettingsService;

/**
 * SET-CERT F1 — the four installment eligibility rules are READ FROM SETTINGS AND ENFORCED.
 *
 * <p>Until 2026-09-26 the rules sat on the Configuration screen and InstallmentEligibilityPolicy had no caller: every
 * rule saved, none applied. The policy's own arithmetic is not re-tested here; what is pinned is the WIRING — each
 * setting reaches the decision, the customer's standing is built from the right plans, and every rule is inert until an
 * owner switches it on (the owner's ruling: max open plans defaults to 0 = no limit).
 */
class InstallmentEligibilityWiringTest {

    private static final Long ORG = 13L, CUSTOMER = 501L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    private InstallmentPlanService service;
    private InstallmentPlanRepo planRepo;
    private SettingsService settings;
    private final List<InstallmentPlan> plans = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new InstallmentPlanService();
        planRepo = mock(InstallmentPlanRepo.class);
        settings = mock(SettingsService.class);
        ReflectionTestUtils.setField(service, "planRepo", planRepo);
        ReflectionTestUtils.setField(service, "settingsService", settings);
        when(planRepo.findByCustomerScoped(anyLong(), anyLong())).thenReturn(plans);
        // Every rule OFF unless a test switches it on — exactly what a shop that configured nothing has.
        when(settings.getBool(anyString())).thenReturn(false);
        when(settings.getInt(anyString(), anyInt())).thenAnswer((inv) -> inv.getArgument(1));
    }

    private static PlanTerms terms(String price, String down) {
        return new PlanTerms(new BigDecimal(price), new BigDecimal(down), 6, Frequency.MONTHLY,
                TODAY.plusMonths(1), BigDecimal.ZERO);
    }

    private void openPlan(String status, int daysLate) {
        InstallmentPlan p = new InstallmentPlan();
        p.setStatus(status);
        Installment i = new Installment();
        i.setDueDate(TODAY.minusDays(daysLate));
        i.setAmount(new BigDecimal("1000.00"));
        i.setPaidAmount(BigDecimal.ZERO);
        i.setOutstanding(new BigDecimal("1000.00"));
        i.setStatus(Installment.SCHEDULED);
        List<Installment> list = new ArrayList<>();
        list.add(i);
        p.setInstallments(list);
        plans.add(p);
    }

    private Decision decide(String cnic, PlanTerms t) {
        return service.eligibility(ORG, CUSTOMER, cnic, true, t, TODAY);
    }

    // ── nothing configured = nothing refused (the owner's ruling) ──────────────────────────────────────────

    @Test
    void with_nothing_configured_a_customer_with_a_late_open_plan_and_no_CNIC_is_still_allowed() {
        openPlan(InstallmentPlan.ACTIVE, 90);
        assertThat(decide(null, terms("10000", "0")).allowed()).isTrue();
    }

    // ── require CNIC ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void require_CNIC_refuses_a_customer_with_none_on_file_and_allows_one_with() {
        when(settings.getBool(InstallmentPlanService.KEY_REQUIRE_CNIC)).thenReturn(true);
        Decision refused = decide("  ", terms("10000", "0"));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).contains("CNIC");
        assertThat(decide("35202-1234567-1", terms("10000", "0")).allowed()).isTrue();
    }

    // ── max open plans ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void max_open_plans_counts_ACTIVE_and_DEFAULTED_but_not_finished_plans() {
        when(settings.getInt(eq(InstallmentPlanService.KEY_MAX_OPEN_PLANS), anyInt())).thenReturn(1);
        openPlan(InstallmentPlan.COMPLETED, 0);
        openPlan(InstallmentPlan.CANCELLED, 0);
        assertThat(decide(null, terms("10000", "0")).allowed()).as("finished plans do not count").isTrue();

        openPlan(InstallmentPlan.DEFAULTED, 0);
        Decision refused = decide(null, terms("10000", "0"));
        assertThat(refused.allowed()).as("a DEFAULTED plan is still money owed").isFalse();
        assertThat(refused.reason()).contains("already has an installment plan");
    }

    @Test
    void max_open_plans_zero_means_no_limit() {
        when(settings.getInt(eq(InstallmentPlanService.KEY_MAX_OPEN_PLANS), anyInt())).thenReturn(0);
        openPlan(InstallmentPlan.ACTIVE, 0);
        openPlan(InstallmentPlan.ACTIVE, 0);
        assertThat(decide(null, terms("10000", "0")).allowed()).isTrue();
    }

    // ── block while overdue ────────────────────────────────────────────────────────────────────────────────

    @Test
    void block_while_overdue_refuses_at_the_threshold_and_allows_below_it() {
        when(settings.getInt(eq(InstallmentPlanService.KEY_BLOCK_IF_OVERDUE_DAYS), anyInt())).thenReturn(30);
        openPlan(InstallmentPlan.ACTIVE, 10);
        assertThat(decide(null, terms("10000", "0")).allowed()).as("10 days late, threshold 30").isTrue();

        openPlan(InstallmentPlan.ACTIVE, 40);
        Decision refused = decide(null, terms("10000", "0"));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).contains("40 days late");
    }

    @Test
    void a_paid_installment_is_never_overdue() {
        when(settings.getInt(eq(InstallmentPlanService.KEY_BLOCK_IF_OVERDUE_DAYS), anyInt())).thenReturn(30);
        openPlan(InstallmentPlan.ACTIVE, 60);
        plans.get(0).getInstallments().get(0).setOutstanding(BigDecimal.ZERO);
        assertThat(decide(null, terms("10000", "0")).allowed()).isTrue();
    }

    // ── minimum down payment ───────────────────────────────────────────────────────────────────────────────

    @Test
    void minimum_down_payment_refuses_short_and_allows_exact() {
        when(settings.getInt(eq(InstallmentPlanService.KEY_MIN_DOWN_PAYMENT_PCT), anyInt())).thenReturn(20);
        Decision refused = decide(null, terms("1000", "150"));
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).contains("200");
        assertThat(decide(null, terms("1000", "200")).allowed()).isTrue();
    }

    // ── a plan must belong to someone ──────────────────────────────────────────────────────────────────────

    @Test
    void an_unnamed_customer_is_refused_even_with_every_rule_off() {
        Decision refused = service.eligibility(ORG, null, null, false, terms("1000", "0"), TODAY);
        assertThat(refused.allowed()).isFalse();
    }

    @Test
    void a_new_customer_typed_by_name_has_no_history_and_is_allowed() {
        when(settings.getInt(eq(InstallmentPlanService.KEY_MAX_OPEN_PLANS), anyInt())).thenReturn(1);
        assertThat(service.eligibility(ORG, null, null, true, terms("1000", "0"), TODAY).allowed()).isTrue();
    }

    // ── markup: locked until finance can book it ───────────────────────────────────────────────────────────

    @Test
    void markup_cannot_be_switched_on_but_can_be_switched_off() {
        InstallmentMarkupGuard guard = new InstallmentMarkupGuard();
        assertThatThrownBy(() -> guard.check(ORG, "pos.installment.markupEnabled", "true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not available yet");
        guard.check(ORG, "pos.installment.markupEnabled", "false");       // allowed
        guard.check(ORG, "pos.installment.enabled", "true");             // not its key
    }
}
