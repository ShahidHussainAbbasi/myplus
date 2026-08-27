package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
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
 * B2B Phase 4a — SHARED-POOL credit limit.
 *
 * <p>A company sets ONE limit; its branches all draw on it. What this pins down is the behaviour that was
 * impossible before the hierarchy existed and is silent when wrong:
 *
 * <ul>
 *   <li>the limit that applies is the <b>credit account's</b>, not the billed row's — a branch typically has no
 *       limit of its own, and reading its blank limit would mean "no check at all";</li>
 *   <li>the balance compared against it is the <b>pool</b> — otherwise three branches each spend the company's
 *       full limit and the cap means nothing;</li>
 *   <li>a standalone customer must be <b>completely unaffected</b>, because it is a single-member group. This is
 *       the regression that matters: every existing customer in every existing shop is one.</li>
 * </ul>
 *
 * <p>Pure logic — mocked repo and settings, no database, no Docker — so it runs on every {@code mvn test}.
 */
@ExtendWith(MockitoExtension.class)
class SharedPoolCreditTest {

    private static final Long ORG = 1L, USER = 1L;

    @Mock private SettingsService settingsService;
    @Mock private CustomerRepo customerRepo;
    @Mock private RequestUtil requestUtil;

    /**
     * Built by hand for the same reason as MarginPolicyTest. Note the split: {@code requestUtil} is constructor
     * argument 4 (a final collaborator), while {@code customerRepo} and {@code settingsService} are
     * {@code @Autowired} fields — deliberately so, per the comment on the field itself, so that widening the
     * constructor does not break MarginPolicyTest's exact list of nulls.
     */
    private SagaSellService service;

    @BeforeEach
    void setUp() {
        service = new SagaSellService(null, null, null, requestUtil, null, null, null);
        ReflectionTestUtils.setField(service, "settingsService", settingsService);
        ReflectionTestUtils.setField(service, "customerRepo", customerRepo);
        // O7 D2 moved creditAccountOf/groupExposure OUT of SagaSellService into CreditStandingService, which
        // arrives as a third @Autowired field. A REAL one over the same two mocks, not a mock of it: mocking the
        // collaborator would stub out the pool lookup itself, and every scenario below would keep passing while
        // asserting nothing about shared-pool credit — the only thing this test exists to pin down.
        ReflectionTestUtils.setField(service, "creditStandingService",
                new CreditStandingService(customerRepo, requestUtil));

        lenient().when(settingsService.getChoice(eq("pos.sale.creditLimitPolicy"), any(), anyString()))
                .thenReturn("block");   // block makes a breach assert-able as a thrown refusal

        AuthenticatedUser user = org.mockito.Mockito.mock(AuthenticatedUser.class);
        lenient().when(user.getOrganizationId()).thenReturn(ORG);
        lenient().when(user.getUserId()).thenReturn(USER);
        lenient().when(requestUtil.getCurrentUser()).thenReturn(user);
    }

    private static Customer customer(Long id, String name, BigDecimal due, BigDecimal limit, Long creditAccount) {
        Customer c = new Customer();
        c.setCustomerId(id);
        c.setName(name);
        c.setDueAmount(due);
        c.setCreditLimit(limit);
        c.setCreditAccountCustomerId(creditAccount);
        c.setOrganizationId(ORG);
        return c;
    }

    /** A sale of {@code amount}, entirely unpaid, to {@code customerId}. */
    private static CustomerHistoryDTO saleOf(Long customerId, double amount) {
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        CustomerDTO c = new CustomerDTO();
        c.setCustomerId(customerId);
        dto.setCustomer(c);
        dto.setPaidAmount(BigDecimal.ZERO);
        dto.setWarnings(new java.util.ArrayList<>());
        return dto;
    }

    private static List<SagaLine> lineOf(double net) {
        return List.of(new SagaLine(
                1L, 1f, BigDecimal.valueOf(net), BigDecimal.ZERO, BigDecimal.valueOf(net),
                BigDecimal.valueOf(net), null, null, null, null, null, null, null, null, null,
                null, null, null, null));   // U2: soldUnit/soldQuantity/soldRate/packSizeSnapshot
    }

    // ── the standalone case: nothing may change ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a standalone customer is a single-member group — behaviour is exactly as before 4a")
    void standaloneCustomerUnaffected() {
        Customer solo = customer(7L, "Walk-in Trader", new BigDecimal("400"), new BigDecimal("1000"), 7L);
        when(customerRepo.findById(7L)).thenReturn(Optional.of(solo));
        when(customerRepo.sumDueByCreditAccount(7L, ORG, USER)).thenReturn(new BigDecimal("400"));

        // 400 owed + 500 unpaid = 900, within the 1000 limit.
        assertThatCode(() -> service.assertCreditPolicy(saleOf(7L, 500), lineOf(500), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a standalone customer still breaches its own limit")
    void standaloneCustomerStillBreaches() {
        Customer solo = customer(7L, "Walk-in Trader", new BigDecimal("900"), new BigDecimal("1000"), 7L);
        when(customerRepo.findById(7L)).thenReturn(Optional.of(solo));
        when(customerRepo.sumDueByCreditAccount(7L, ORG, USER)).thenReturn(new BigDecimal("900"));

        assertThatThrownBy(() -> service.assertCreditPolicy(saleOf(7L, 500), lineOf(500), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("over their credit limit");
    }

    // ── the group case: the point of the slice ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a branch is capped by the COMPANY's limit, not its own blank one")
    void branchUsesCompanyLimit() {
        Customer company = customer(1L, "Al-Karam Distributors", new BigDecimal("0"), new BigDecimal("100000"), 1L);
        Customer branch = customer(2L, "Al-Karam — Lahore", new BigDecimal("30000"), null, 1L);

        when(customerRepo.findById(2L)).thenReturn(Optional.of(branch));
        when(customerRepo.findById(1L)).thenReturn(Optional.of(company));
        // Pool: Lahore 30k + Multan 60k = 90k already owed across the group.
        when(customerRepo.sumDueByCreditAccount(1L, ORG, USER)).thenReturn(new BigDecimal("90000"));

        // 90k pooled + 20k unpaid = 110k, past the company's 100k. The branch's OWN due (30k) would have passed.
        assertThatThrownBy(() -> service.assertCreditPolicy(saleOf(2L, 20000), lineOf(20000), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("over their credit limit");
    }

    @Test
    @DisplayName("the refusal names the GROUP, so the cashier knows whose limit was hit")
    void messageNamesTheGroup() {
        Customer company = customer(1L, "Al-Karam Distributors", BigDecimal.ZERO, new BigDecimal("100000"), 1L);
        Customer branch = customer(2L, "Al-Karam — Lahore", new BigDecimal("30000"), null, 1L);

        when(customerRepo.findById(2L)).thenReturn(Optional.of(branch));
        when(customerRepo.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepo.sumDueByCreditAccount(1L, ORG, USER)).thenReturn(new BigDecimal("90000"));

        assertThatThrownBy(() -> service.assertCreditPolicy(saleOf(2L, 20000), lineOf(20000), null))
                .hasMessageContaining("Al-Karam Distributors")
                .hasMessageContaining("(group)");
    }

    @Test
    @DisplayName("a branch sale within the group's remaining headroom proceeds")
    void branchWithinGroupHeadroomProceeds() {
        Customer company = customer(1L, "Al-Karam Distributors", BigDecimal.ZERO, new BigDecimal("100000"), 1L);
        Customer branch = customer(2L, "Al-Karam — Lahore", new BigDecimal("30000"), null, 1L);

        when(customerRepo.findById(2L)).thenReturn(Optional.of(branch));
        when(customerRepo.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepo.sumDueByCreditAccount(1L, ORG, USER)).thenReturn(new BigDecimal("90000"));

        // 90k + 10k = exactly 100k. At the limit is WITHIN it (CreditLimitPolicy's rule).
        assertThatCode(() -> service.assertCreditPolicy(saleOf(2L, 10000), lineOf(10000), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a group with no limit on the company is unlimited — a blank branch limit never invents one")
    void groupWithoutLimitIsUnchecked() {
        Customer company = customer(1L, "No-Limit Co", BigDecimal.ZERO, null, 1L);
        Customer branch = customer(2L, "No-Limit — Branch", new BigDecimal("999999"), null, 1L);

        when(customerRepo.findById(2L)).thenReturn(Optional.of(branch));
        when(customerRepo.findById(1L)).thenReturn(Optional.of(company));

        assertThatCode(() -> service.assertCreditPolicy(saleOf(2L, 500000), lineOf(500000), null))
                .doesNotThrowAnyException();
    }

    // ── degradation: a missing stamp must never mean "no limit" ─────────────────────────────────────────────

    @Test
    @DisplayName("a null credit-account stamp falls back to the customer's OWN limit, never to no check")
    void nullStampFallsBackToOwnLimit() {
        // The pre-V36 shape: a row that predates the backfill.
        Customer legacy = customer(7L, "Legacy Trader", new BigDecimal("900"), new BigDecimal("1000"), null);
        when(customerRepo.findById(7L)).thenReturn(Optional.of(legacy));
        when(customerRepo.sumDueByCreditAccount(7L, ORG, USER)).thenReturn(null);   // matches no rows

        // Failing safe means the customer's own due is still used — not a zero balance that waves the sale through.
        assertThatThrownBy(() -> service.assertCreditPolicy(saleOf(7L, 500), lineOf(500), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an unreadable credit account (deleted head) falls back to the buying customer")
    void missingAccountHeadFallsBack() {
        Customer branch = customer(2L, "Orphan Branch", new BigDecimal("900"), new BigDecimal("1000"), 99L);
        when(customerRepo.findById(2L)).thenReturn(Optional.of(branch));
        when(customerRepo.findById(99L)).thenReturn(Optional.empty());   // head is gone
        when(customerRepo.sumDueByCreditAccount(2L, ORG, USER)).thenReturn(new BigDecimal("900"));

        assertThatThrownBy(() -> service.assertCreditPolicy(saleOf(2L, 500), lineOf(500), null))
                .isInstanceOf(ValidationException.class);
    }
}
