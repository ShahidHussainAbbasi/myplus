package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OMS O7 D2 — the READ half: what the order booker is shown at the counter.
 *
 * <p>{@link SharedPoolCreditTest} covers the WRITE half (the sale refusing), and both now run through the same
 * {@link CreditStandingService}, which is the entire point of the D2 move. This class covers what only the read
 * has: {@code standingFor} resolves a customer id that arrives from a query string, and decides what "no limit"
 * looks like on screen.
 *
 * <p>Both of those are silent when wrong. An unscoped read leaks another tenant's limit and balance to anyone
 * who can guess a number, and nothing in the UI would look amiss. An uncapped customer rendered as "0 of 0"
 * reads as maximally breached, which trains bookers to ignore the warning — the one outcome that makes the
 * feature worse than not having it.
 *
 * <p>Pure logic — mocked repo and request context, no database, no Docker — so it runs on every {@code mvn test}.
 */
@ExtendWith(MockitoExtension.class)
class CreditStandingReadTest {

    private static final Long ORG = 1L, USER = 1L;

    @Mock private CustomerRepo customerRepo;
    @Mock private RequestUtil requestUtil;

    private CreditStandingService service;

    @BeforeEach
    void setUp() {
        service = new CreditStandingService(customerRepo, requestUtil);

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

    // ── anti-IDOR: the id came from a query string ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("another tenant's customer reads as absent — the id is caller-supplied, so the read is SCOPED")
    void otherOrgsCustomerIsNotReadable() {
        // findByIdScoped is what the service must call. Another org's row does not match the scope predicate,
        // so it comes back empty — indistinguishable from a genuinely missing id, which is what stops the
        // endpoint being used to probe which ids exist.
        when(customerRepo.findByIdScoped(4242L, ORG, USER)).thenReturn(Optional.empty());

        assertThat(service.standingFor(4242L)).isNull();

        // The real defect this guards: reaching for the unscoped findById, which would happily return the row.
        verify(customerRepo, never()).findById(anyLong());
    }

    @Test
    @DisplayName("no customer id — nothing is read at all")
    void noCustomerIdReadsNothing() {
        assertThat(service.standingFor(null)).isNull();
        verifyNoInteractions(customerRepo);
    }

    // ── what "no limit" must look like ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an uncapped customer returns null, NOT a zero limit they are 100% through")
    void uncappedCustomerHasNothingToReport() {
        Customer solo = customer(7L, "Walk-in Trader", new BigDecimal("400"), null, 7L);
        when(customerRepo.findByIdScoped(7L, ORG, USER)).thenReturn(Optional.of(solo));

        assertThat(service.standingFor(7L)).isNull();

        // Not merely unreported — not even looked up. A customer with no limit has no exposure question.
        verify(customerRepo, never()).sumDueByCreditAccount(anyLong(), anyLong(), anyLong());
    }

    // ── the group case: the booker must be shown whose limit actually binds ─────────────────────────────────

    @Test
    @DisplayName("a branch is reported against the COMPANY's limit and the GROUP's balance")
    void branchReportsTheGroupsPosition() {
        Customer company = customer(1L, "Al-Karam Distributors", BigDecimal.ZERO, new BigDecimal("100000"), 1L);
        Customer branch = customer(2L, "Al-Karam — Lahore", new BigDecimal("30000"), null, 1L);

        when(customerRepo.findByIdScoped(2L, ORG, USER)).thenReturn(Optional.of(branch));
        when(customerRepo.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepo.sumDueByCreditAccount(1L, ORG, USER)).thenReturn(new BigDecimal("90000"));

        CreditStandingService.Standing s = service.standingFor(2L);

        assertThat(s).isNotNull();
        assertThat(s.grouped()).isTrue();
        assertThat(s.accountName()).isEqualTo("Al-Karam Distributors");   // NOT the branch
        assertThat(s.creditLimit()).isEqualByComparingTo("100000");       // NOT the branch's blank one
        assertThat(s.owed()).isEqualByComparingTo("90000");               // the POOL, not the branch's 30000
        assertThat(s.available()).isEqualByComparingTo("10000");
        assertThat(s.overLimit()).isFalse();

        // The booker's headroom must be the same number the sale will enforce: a 10,000 order is the last one
        // that goes through, which is exactly where SharedPoolCreditTest puts the write path's boundary.
    }

    @Test
    @DisplayName("a standalone customer is reported under their own name, ungrouped")
    void standaloneReportsItself() {
        Customer solo = customer(7L, "Walk-in Trader", new BigDecimal("400"), new BigDecimal("1000"), 7L);
        when(customerRepo.findByIdScoped(7L, ORG, USER)).thenReturn(Optional.of(solo));
        when(customerRepo.sumDueByCreditAccount(7L, ORG, USER)).thenReturn(new BigDecimal("400"));

        CreditStandingService.Standing s = service.standingFor(7L);

        assertThat(s).isNotNull();
        assertThat(s.grouped()).isFalse();
        assertThat(s.accountName()).isEqualTo("Walk-in Trader");
        assertThat(s.available()).isEqualByComparingTo("600");
        assertThat(s.overLimit()).isFalse();
    }

    // ── over the limit: how far over is the actionable part ─────────────────────────────────────────────────

    @Test
    @DisplayName("an over-limit account reports NEGATIVE headroom, not a floor of zero")
    void overLimitKeepsTheOverage() {
        Customer solo = customer(7L, "Over-extended Trader", new BigDecimal("1200"), new BigDecimal("1000"), 7L);
        when(customerRepo.findByIdScoped(7L, ORG, USER)).thenReturn(Optional.of(solo));
        when(customerRepo.sumDueByCreditAccount(7L, ORG, USER)).thenReturn(new BigDecimal("1200"));

        CreditStandingService.Standing s = service.standingFor(7L);

        assertThat(s).isNotNull();
        assertThat(s.overLimit()).isTrue();
        // "You are 200 over" is actionable; "you have 0 available" is not. Flooring this at zero is the
        // plausible-looking change that would quietly remove the only number the booker can act on.
        assertThat(s.available()).isEqualByComparingTo("-200");
    }

    // ── degradation ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a pool sum of null falls back to the customer's OWN due, never to a clean slate")
    void nullPoolFallsBackToOwnDue() {
        // The pre-V36 shape: an unstamped row the group SUM matches no rows for. Reporting zero owed here
        // would show a customer at their full limit when they are already deep into it.
        Customer legacy = customer(7L, "Legacy Trader", new BigDecimal("900"), new BigDecimal("1000"), null);
        when(customerRepo.findByIdScoped(7L, ORG, USER)).thenReturn(Optional.of(legacy));
        when(customerRepo.sumDueByCreditAccount(7L, ORG, USER)).thenReturn(null);

        CreditStandingService.Standing s = service.standingFor(7L);

        assertThat(s).isNotNull();
        assertThat(s.owed()).isEqualByComparingTo("900");
        assertThat(s.available()).isEqualByComparingTo("100");
    }
}
