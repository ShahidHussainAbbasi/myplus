package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.myplus.business_service.entity.QuoteStatus;
import com.myplus.business_service.entity.SalesQuote;
import com.myplus.business_service.repository.SalesQuoteRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B2B-P4b — the quote lifecycle.
 *
 * <p>Pure logic: mocked repo and settings, no database, no Docker — so it runs on every {@code mvn test}. That
 * matters here more than usual, because the Testcontainers suites SKIP on the dev machine and would give this
 * slice no executed unit coverage at all.
 *
 * <p>What is pinned down is what is silent when wrong: an illegal transition (billing a customer from a rejected
 * or expired offer), the internal approval gate firing at the wrong threshold, and derived expiry — which has no
 * job behind it, so nothing else would ever notice if the derivation broke.
 */
@ExtendWith(MockitoExtension.class)
class SalesQuoteTransitionTest {

    private static final Long ORG = 1L, USER = 7L;

    @Mock private SalesQuoteRepo quoteRepo;
    @Mock private RequestUtil requestUtil;
    @Mock private SettingsService settingsService;

    private SalesQuoteService service;

    @BeforeEach
    void setUp() {
        service = new SalesQuoteService();
        ReflectionTestUtils.setField(service, "quoteRepo", quoteRepo);
        ReflectionTestUtils.setField(service, "requestUtil", requestUtil);
        ReflectionTestUtils.setField(service, "settingsService", settingsService);

        AuthenticatedUser user = org.mockito.Mockito.mock(AuthenticatedUser.class);
        lenient().when(user.getOrganizationId()).thenReturn(ORG);
        lenient().when(user.getUserId()).thenReturn(USER);
        lenient().when(requestUtil.getCurrentUser()).thenReturn(user);

        // Default: no approval threshold configured — the common case, and the documented "unset means off".
        // getDecimal, not getText: the service reads the shared decimal port, which does the parse itself.
        lenient().when(settingsService.getDecimal(SalesQuoteService.SETTING_DISCOUNT_THRESHOLD, null))
                .thenReturn(null);
        lenient().when(settingsService.getInt(eq(SalesQuoteService.SETTING_VALIDITY_DAYS),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(quoteRepo.save(any(SalesQuote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A quote in a given state, valid for another week unless told otherwise. Not registered with the repo —
     *  the pure-derivation tests never load it, and a strict-stub run would fail on the unused stub. */
    private SalesQuote newQuote(QuoteStatus status) {
        SalesQuote q = new SalesQuote();
        q.setId(11L);
        q.setQuoteNo("QTE-000011");
        q.setStatus(status);
        q.setValidUntil(LocalDate.now().plusDays(7));
        q.setOrganizationId(ORG);
        q.setUserId(USER);
        q.setSubTotal(new BigDecimal("1000.00"));
        q.setGrandTotal(new BigDecimal("1000.00"));
        return q;
    }

    /** …and the same quote made loadable, for the tests that go through {@code transition}. */
    private SalesQuote quote(QuoteStatus status) {
        SalesQuote q = newQuote(status);
        when(quoteRepo.findByIdScoped(11L, ORG, USER)).thenReturn(Optional.of(q));
        return q;
    }

    // â”€â”€ the legal moves â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("DRAFT â†’ SENT is allowed when no approval threshold is configured")
    void draftCanBeSent() {
        quote(QuoteStatus.DRAFT);
        assertThat(service.transition(11L, QuoteStatus.SENT, null).getStatus()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("SENT â†’ ACCEPTED records the customer's decision")
    void sentCanBeAccepted() {
        quote(QuoteStatus.SENT);
        assertThat(service.transition(11L, QuoteStatus.ACCEPTED, null).getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
    }

    @Test
    @DisplayName("approving a PENDING_APPROVAL quote stamps who cleared it")
    void approvalIsStamped() {
        quote(QuoteStatus.PENDING_APPROVAL);
        SalesQuote sent = service.transition(11L, QuoteStatus.SENT, null);
        assertThat(sent.getStatus()).isEqualTo(QuoteStatus.SENT);
        assertThat(sent.getApprovedBy()).as("who approved the concession").isEqualTo(USER);
        assertThat(sent.getApprovedAt()).isNotNull();
    }

    // â”€â”€ the illegal ones: each would bill a customer from something they never agreed to â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("a REJECTED quote cannot be revived")
    void rejectedIsTerminal() {
        quote(QuoteStatus.REJECTED);
        assertThatThrownBy(() -> service.transition(11L, QuoteStatus.ACCEPTED, null))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class)
                .hasMessageContaining("cannot become");
    }

    @Test
    @DisplayName("a CONVERTED quote cannot be converted or re-sent — one offer, one invoice")
    void convertedIsTerminal() {
        quote(QuoteStatus.CONVERTED);
        assertThatThrownBy(() -> service.transition(11L, QuoteStatus.SENT, null))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class);
    }

    @Test
    @DisplayName("a DRAFT cannot jump straight to ACCEPTED — the customer never saw it")
    void draftCannotBeAccepted() {
        quote(QuoteStatus.DRAFT);
        assertThatThrownBy(() -> service.transition(11L, QuoteStatus.ACCEPTED, null))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class);
    }

    @Test
    @DisplayName("asking for the state it is already in is a no-op, not an error")
    void sameStateIsIdempotent() {
        quote(QuoteStatus.SENT);
        assertThatCode(() -> service.transition(11L, QuoteStatus.SENT, null)).doesNotThrowAnyException();
    }

    // â”€â”€ derived expiry: nothing else would notice if this broke â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("an open quote past validUntil reads EXPIRED with no job having run")
    void expiryIsDerived() {
        SalesQuote q = newQuote(QuoteStatus.SENT);
        q.setValidUntil(LocalDate.now().minusDays(1));
        assertThat(q.getEffectiveStatus()).isEqualTo(QuoteStatus.EXPIRED);
    }

    @Test
    @DisplayName("an expired quote cannot be accepted — the prices are no longer a promise")
    void expiredCannotBeAccepted() {
        SalesQuote q = quote(QuoteStatus.SENT);
        q.setValidUntil(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> service.transition(11L, QuoteStatus.ACCEPTED, null))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("a CONVERTED quote never reads EXPIRED, however old it is")
    void terminalStatesDoNotExpire() {
        SalesQuote q = newQuote(QuoteStatus.CONVERTED);
        q.setValidUntil(LocalDate.now().minusYears(1));
        assertThat(q.getEffectiveStatus()).as("a converted quote is history, not a lapsed offer")
                .isEqualTo(QuoteStatus.CONVERTED);
    }

    // â”€â”€ the internal approval gate â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("a discount OVER the threshold blocks sending until it is approved")
    void overThresholdBlocksSend() {
        when(settingsService.getDecimal(SalesQuoteService.SETTING_DISCOUNT_THRESHOLD, null))
                .thenReturn(new BigDecimal("10"));
        SalesQuote q = quote(QuoteStatus.DRAFT);
        q.setSubTotal(new BigDecimal("850.00"));      // gross 1000, discount 150 = 15% > 10%
        q.setTradeDiscount(new BigDecimal("150.00"));

        assertThatThrownBy(() -> service.transition(11L, QuoteStatus.SENT, null))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class)
                .hasMessageContaining("approval");
    }

    @Test
    @DisplayName("a discount UNDER the threshold sends without an approval step")
    void underThresholdSendsFreely() {
        when(settingsService.getDecimal(SalesQuoteService.SETTING_DISCOUNT_THRESHOLD, null))
                .thenReturn(new BigDecimal("10"));
        SalesQuote q = quote(QuoteStatus.DRAFT);
        q.setSubTotal(new BigDecimal("950.00"));      // gross 1000, discount 50 = 5% < 10%
        q.setTradeDiscount(new BigDecimal("50.00"));

        assertThat(service.transition(11L, QuoteStatus.SENT, null).getStatus()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("no threshold configured = no gate, so nothing changes for a shop that wants none")
    void noThresholdMeansNoGate() {
        SalesQuote q = quote(QuoteStatus.DRAFT);
        q.setSubTotal(new BigDecimal("100.00"));      // a 90% discount, and still no gate
        q.setTradeDiscount(new BigDecimal("900.00"));

        assertThat(service.transition(11L, QuoteStatus.SENT, null).getStatus()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("an unreadable threshold is treated as no gate, never as a block")
    void malformedThresholdDoesNotBlockSelling() {
        // The PARSE moved into SettingsService.getDecimal, which swallows NumberFormatException and returns
        // the fallback — so "ten percent" never reaches this service, a null threshold does. What is still
        // worth asserting HERE is the consequence: whatever made the value unreadable, a settings typo must
        // not stop a shop quoting. The parse itself is covered by SettingsServiceDecimalTest in
        // common-settings, which is where that behaviour now lives.
        when(settingsService.getDecimal(SalesQuoteService.SETTING_DISCOUNT_THRESHOLD, null)).thenReturn(null);
        SalesQuote q = quote(QuoteStatus.DRAFT);
        q.setSubTotal(new BigDecimal("500.00"));
        q.setTradeDiscount(new BigDecimal("500.00"));

        assertThat(service.transition(11L, QuoteStatus.SENT, null).getStatus()).isEqualTo(QuoteStatus.SENT);
    }

    // â”€â”€ anti-IDOR â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("a quote from another tenant is indistinguishable from one that does not exist")
    void foreignQuoteReadsAsMissing() {
        when(quoteRepo.findByIdScoped(99L, ORG, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transition(99L, QuoteStatus.SENT, null))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class)
                .hasMessageContaining("not found");
    }
}
