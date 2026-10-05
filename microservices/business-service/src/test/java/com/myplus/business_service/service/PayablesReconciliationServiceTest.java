package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.myplus.business_service.entity.PayablesReconDay;
import com.myplus.business_service.repository.PayablesReconDayRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.commerce.contracts.client.FinanceClient;

/** FP-6a — the automatic payables reconciliation's decisions, per tenant per day. */
class PayablesReconciliationServiceTest {

    private PurchaseRepo purchases;
    private PayablesReconDayRepo days;
    private PayableOutboxService outbox;
    private FinanceClient finance;
    private PayablesReconciliationService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        purchases = mock(PurchaseRepo.class);
        days = mock(PayablesReconDayRepo.class);
        outbox = mock(PayableOutboxService.class);
        finance = mock(FinanceClient.class);
        ObjectProvider<FinanceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(finance);
        when(provider.getObject()).thenReturn(finance);
        when(days.findByOrganizationIdAndReconDay(any(), any())).thenReturn(Optional.empty());
        when(days.save(any())).thenAnswer(i -> i.getArgument(0));
        when(purchases.anyUserOfOrg(any())).thenReturn(5L);
        svc = new PayablesReconciliationService(purchases, days, outbox, provider);
    }

    private static Map<String, Object> rec(String purchaseNet, String net, String gl) {
        return Map.of("purchaseNet", new BigDecimal(purchaseNet), "subledgerOpen", new BigDecimal(net),
                "glAccountsPayable", new BigDecimal(gl), "difference", new BigDecimal(gl).subtract(new BigDecimal(net)));
    }

    @Test
    @DisplayName("everything agrees → clean, nothing re-sent, ledger untouched")
    void clean() {
        when(purchases.sumSupplierDueByOrg(9L)).thenReturn(new BigDecimal("-100"));   // owes 100
        when(finance.payablesReconciliation()).thenReturn(rec("100", "150", "150"));
        PayablesReconDay d = svc.runOrg(9L);
        assertThat(d.getClean()).isTrue();
        verify(outbox, never()).backfillOrg(any());
        verify(finance, never()).alignPayablesLedger(anyString());
    }

    @Test
    @DisplayName("documents agree, ledger does not → ledger aligned ONCE; the day is NOT clean (a repair was needed)")
    void ledgerOnly() {
        when(purchases.sumSupplierDueByOrg(41L)).thenReturn(new BigDecimal("25200"));   // an advance of 25,200
        when(finance.payablesReconciliation()).thenReturn(rec("-25200", "-25200", "288000"));
        when(finance.alignPayablesLedger(anyString())).thenReturn(Map.of("posted", new BigDecimal("313200")));
        PayablesReconDay d = svc.runOrg(41L);
        assertThat(d.getClean()).isFalse();
        assertThat(d.getLedgerDiff()).isEqualByComparingTo("313200");
        assertThat(d.getLedgerAligned()).isEqualByComparingTo("313200");
        verify(finance).alignPayablesLedger(org.mockito.ArgumentMatchers.startsWith("AP-REC-41-"));
    }

    @Test
    @DisplayName("⭐ documents still disagree after re-sending → the ledger is NOT touched (never align to a wrong ledger)")
    void neverAlignWhileDocumentsDisagree() {
        when(purchases.sumSupplierDueByOrg(6L)).thenReturn(new BigDecimal("-500"));
        when(finance.payablesReconciliation()).thenReturn(rec("300", "300", "900"));   // 500 vs 300, both times
        when(outbox.backfillOrg(6L)).thenReturn(12);
        PayablesReconDay d = svc.runOrg(6L);
        assertThat(d.getDocsResent()).isEqualTo(12);
        assertThat(d.getClean()).isFalse();
        verify(finance, never()).alignPayablesLedger(anyString());
    }

    @Test
    @DisplayName("documents fixed by re-sending, ledger then differs → aligned in the same run")
    void resendThenAlign() {
        when(purchases.sumSupplierDueByOrg(6L)).thenReturn(new BigDecimal("-500"));
        when(finance.payablesReconciliation()).thenReturn(rec("300", "300", "900"), rec("500", "500", "900"));
        when(outbox.backfillOrg(6L)).thenReturn(3);
        when(finance.alignPayablesLedger(anyString())).thenReturn(Map.of("posted", new BigDecimal("400")));
        PayablesReconDay d = svc.runOrg(6L);
        assertThat(d.getDocsResent()).isEqualTo(3);
        assertThat(d.getLedgerAligned()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("a failure is recorded as NOT clean — an unknown never counts toward the 28 days")
    void failureIsNotClean() {
        when(purchases.sumSupplierDueByOrg(7L)).thenReturn(BigDecimal.ZERO);
        when(finance.payablesReconciliation()).thenThrow(new IllegalStateException("finance down"));
        PayablesReconDay d = svc.runOrg(7L);
        assertThat(d.getClean()).isFalse();
        assertThat(d.getError()).contains("finance down");
    }

    @Test
    @DisplayName("⭐ a second run the same day finds nothing to repair — the day STAYS unclean, the repair stays on record")
    void repairedDayStaysUnclean() {
        PayablesReconDay earlier = new PayablesReconDay();
        earlier.setId(1L);
        earlier.setOrganizationId(41L);
        earlier.setClean(false);
        earlier.setLedgerDiff(new BigDecimal("313200"));
        earlier.setLedgerAligned(new BigDecimal("313200"));
        when(days.findByOrganizationIdAndReconDay(any(), any())).thenReturn(Optional.of(earlier));
        when(purchases.sumSupplierDueByOrg(41L)).thenReturn(new BigDecimal("25200"));
        when(finance.payablesReconciliation()).thenReturn(rec("-25200", "-25200", "-25200"));   // now aligned
        PayablesReconDay d = svc.runOrg(41L);
        assertThat(d.getClean()).as("repaired earlier today").isFalse();
        assertThat(d.getLedgerDiff()).as("the difference the day was found with").isEqualByComparingTo("313200");
        assertThat(d.getLedgerAligned()).isEqualByComparingTo("313200");
        verify(finance, never()).alignPayablesLedger(anyString());
    }

    private static PayablesReconDay day(LocalDate d, boolean clean) {
        PayablesReconDay r = new PayablesReconDay();
        r.setReconDay(d);
        r.setClean(clean);
        return r;
    }

    @Test
    @DisplayName("clean streak: consecutive clean days ending today or yesterday; a gap or a repair day ends it")
    void streak() {
        LocalDate t = LocalDate.of(2026, 10, 4);
        assertThat(PayablesReconciliationService.cleanStreak(List.of(day(t, true), day(t.minusDays(1), true), day(t.minusDays(2), false)), t)).isEqualTo(2);
        assertThat(PayablesReconciliationService.cleanStreak(List.of(day(t.minusDays(1), true), day(t.minusDays(2), true)), t))
                .as("before today's run, yesterday still counts").isEqualTo(2);
        assertThat(PayablesReconciliationService.cleanStreak(List.of(day(t, true), day(t.minusDays(2), true)), t))
                .as("a missing day is not a clean day").isEqualTo(1);
        assertThat(PayablesReconciliationService.cleanStreak(List.of(day(t.minusDays(3), true)), t)).as("stale").isZero();
        assertThat(PayablesReconciliationService.cleanStreak(List.of(), t)).isZero();
    }
}
