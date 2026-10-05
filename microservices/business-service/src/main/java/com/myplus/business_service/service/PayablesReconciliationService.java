package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.myplus.business_service.entity.PayablesReconDay;
import com.myplus.business_service.repository.PayablesReconDayRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.security.time.TenantClock;

/**
 * FP-6a — the automatic payables reconciliation: every tenant, every day, and shortly after every deploy. No operator
 * action is ever needed (user ruling 2026-10-04). Design: microservices/docs/slices/fp-6-retire-business-source.md.
 *
 * <h3>Per tenant, in this order</h3>
 * <ol>
 *   <li><b>Measure</b> business's supplier purchases, finance's documents, finance's whole ledger and GL 2000.</li>
 *   <li><b>Documents differ</b> → business re-reports its supplier purchases (the FP-2 intake is idempotent; nothing
 *       moves money). Measured again.</li>
 *   <li><b>Documents agree, ledger does not</b> → finance aligns GL 2000 to the ledger it now provably mirrors (one
 *       append-only journal against 2990, idempotent per ledger state; finance computes the amount itself).</li>
 *   <li><b>Record</b> the day: clean only if NO repair was needed. Anything that fails is recorded as not clean.</li>
 * </ol>
 * The ledger is never touched while the documents disagree: aligning GL to a ledger that is itself wrong would move
 * the error, not fix it.
 */
@Service
public class PayablesReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(PayablesReconciliationService.class);

    /** FP-6b may retire business as a tenant's source only after this many clean days in a row (ruling 2026-10-04). */
    public static final int CLEAN_DAYS_REQUIRED = 28;

    private final PurchaseRepo purchases;
    private final PayablesReconDayRepo days;
    private final PayableOutboxService outbox;
    private final ObjectProvider<FinanceClient> finance;

    public PayablesReconciliationService(PurchaseRepo purchases, PayablesReconDayRepo days, PayableOutboxService outbox,
                                         ObjectProvider<FinanceClient> finance) {
        this.purchases = purchases;
        this.days = days;
        this.outbox = outbox;
        this.finance = finance;
    }

    /** Daily, at 03:30 Pakistan time (quiet hours). */
    @Scheduled(cron = "${payables.recon.cron:0 30 3 * * *}", zone = "Asia/Karachi")
    public void daily() {
        runAll();
    }

    /** Shortly after every start — so a deploy repairs itself without waiting for the night. Then never again. */
    @Scheduled(initialDelayString = "${payables.recon.initial-delay-ms:600000}", fixedDelay = Long.MAX_VALUE)
    public void afterStart() {
        runAll();
    }

    public List<PayablesReconDay> runAll() {
        List<PayablesReconDay> out = new ArrayList<>();
        if (finance.getIfAvailable() == null) return out;
        Set<Long> orgs = new LinkedHashSet<>(purchases.findOrgsWithSupplierPurchases());
        for (Long org : orgs) {
            if (org == null) continue;
            out.add(runOrg(org));
        }
        LOG.info("FP-6a payables reconciliation: {} tenants checked, {} clean", out.size(),
                out.stream().filter(d -> Boolean.TRUE.equals(d.getClean())).count());
        return out;
    }

    /** One tenant, today. Re-running the same day updates that day's row (one row per tenant per day). */
    public PayablesReconDay runOrg(Long org) {
        LocalDate today = TenantClock.today();
        PayablesReconDay d = days.findByOrganizationIdAndReconDay(org, today).orElseGet(PayablesReconDay::new);
        // A day that already needed a repair STAYS unclean: a later run the same day sees no difference precisely
        // because the first one repaired it, and must not erase that evidence or count the day toward the 28.
        boolean repairedEarlierToday = d.getId() != null && !Boolean.TRUE.equals(d.getClean());
        int resentBefore = d.getDocsResent();
        BigDecimal alignedBefore = d.getLedgerAligned() == null ? BigDecimal.ZERO : d.getLedgerAligned();
        d.setOrganizationId(org);
        d.setReconDay(today);
        d.setRanAt(LocalDateTime.now());
        d.setError(null);
        d.setClean(false);
        Long user = purchases.anyUserOfOrg(org);
        try {
            Map<String, Object> rec = measure(org, user);
            BigDecimal owed = purchases.sumSupplierDueByOrg(org).negate();
            BigDecimal shadow = owed.subtract(num(rec.get("purchaseNet")));
            BigDecimal ledger = num(rec.get("difference"));
            d.setBusinessOwed(owed);
            d.setFinancePurchase(num(rec.get("purchaseNet")));
            d.setFinanceNet(num(rec.get("subledgerOpen")));
            d.setGlPayable(num(rec.get("glAccountsPayable")));
            if (!repairedEarlierToday) {        // keep the differences the day was FOUND with, not the repaired zeros
                d.setShadowDiff(shadow);
                d.setLedgerDiff(ledger);
            }
            boolean clean = shadow.signum() == 0 && ledger.signum() == 0 && !repairedEarlierToday;
            d.setDocsResent(resentBefore);
            d.setLedgerAligned(alignedBefore);

            if (shadow.signum() != 0) {                                   // 2 — the documents first
                d.setDocsResent(resentBefore + outbox.backfillOrg(org));
                rec = measure(org, user);
                shadow = owed.subtract(num(rec.get("purchaseNet")));
                ledger = num(rec.get("difference"));
            }
            if (shadow.signum() == 0 && ledger.signum() != 0) {           // 3 — then, and only then, the ledger
                String runKey = "AP-REC-" + org + "-" + today;
                AtomicReference<Map<String, Object>> res = new AtomicReference<>();
                GatewayIdentityForwarding.runAs(user, org, () -> res.set(finance.getObject().alignPayablesLedger(runKey)));
                d.setLedgerAligned(alignedBefore.add(num(res.get() == null ? null : res.get().get("posted"))));
            }
            d.setClean(clean);
            if (!clean) LOG.warn("FP-6a: org {} not clean on {} — shadow {}, ledger {}; resent {}, aligned {}",
                    org, today, d.getShadowDiff(), d.getLedgerDiff(), d.getDocsResent(), d.getLedgerAligned());
        } catch (Exception e) {
            d.setClean(false);                                            // unknown is never clean
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            d.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            LOG.warn("FP-6a: reconciliation for org {} failed; recorded as not clean", org, e);
        }
        return days.save(d);
    }

    private Map<String, Object> measure(Long org, Long user) {
        AtomicReference<Map<String, Object>> rec = new AtomicReference<>();
        GatewayIdentityForwarding.runAs(user, org, () -> rec.set(finance.getObject().payablesReconciliation()));
        if (rec.get() == null) throw new IllegalStateException("finance returned no reconciliation");
        return rec.get();
    }

    /** The history the operator panel shows, newest first, with the clean-days-in-a-row count FP-6b is gated on. */
    public Map<String, Object> history(Long org) {
        List<PayablesReconDay> rows = days.findTop90ByOrganizationIdOrderByReconDayDesc(org);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleanStreak", cleanStreak(rows, TenantClock.today()));
        out.put("required", CLEAN_DAYS_REQUIRED);
        out.put("days", rows);
        return out;
    }

    /**
     * Consecutive clean days ending today (or yesterday, before today's run). A missing day breaks the streak: a day
     * nobody checked is not a clean day.
     */
    static int cleanStreak(List<PayablesReconDay> newestFirst, LocalDate today) {
        int streak = 0;
        LocalDate expect = null;
        for (PayablesReconDay r : newestFirst) {
            if (expect == null) {
                if (r.getReconDay().isBefore(today.minusDays(1))) return 0;
                expect = r.getReconDay();
            }
            if (!r.getReconDay().equals(expect) || !Boolean.TRUE.equals(r.getClean())) return streak;
            streak++;
            expect = expect.minusDays(1);
        }
        return streak;
    }

    private static BigDecimal num(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(v));
    }
}
