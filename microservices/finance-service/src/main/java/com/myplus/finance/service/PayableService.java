package com.myplus.finance.service;

import com.myplus.common.security.time.TenantClock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.finance.dto.PayableSnapshot;
import com.myplus.common.security.CurrentUser;
import com.myplus.finance.entity.PayableDoc;
import com.myplus.finance.repository.PayableDocRepository;

import lombok.RequiredArgsConstructor;

/**
 * FP-1 — finance's supplier payables subledger.
 *
 * <h3>Snapshots, version-guarded</h3>
 * A source reports what a document looks like NOW. {@link #upsert} stores it under (org, source, sourceRef) and
 * ignores a snapshot older than the one held — outboxes retry, and a backfill replays history, so the same
 * document arrives more than once and not always in order. The newest figures always win.
 *
 * <h3>Status from the figures, never from the caller</h3>
 * VOID when the source says voided; SETTLED when nothing is owed; otherwise OPEN. So the open total is a plain
 * sum over OPEN rows and an overpaid line can never subtract from what is owed elsewhere.
 */
@Service
@RequiredArgsConstructor
public class PayableService {

    private final PayableDocRepository repo;
    private final GlService gl;
    private final com.myplus.finance.repository.PayableNoteRepository notes;

    /** FP-4c — tells business each touched supplier's bills/advance; absent in unit tests (nothing to tell). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PayableBalanceService balances;

    @Transactional
    public int upsert(List<PayableSnapshot> snapshots) {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new IllegalArgumentException("A payable needs an organisation.");
        int applied = 0;
        for (PayableSnapshot s : snapshots == null ? List.<PayableSnapshot>of() : snapshots) {
            if (applyOne(org, s)) applied++;
        }
        // FP-4c — each supplier touched by this batch, once, in this same transaction
        if (balances != null && applied > 0) balances.enqueue(org, CurrentUser.userId(), PayableBalanceService.keyed(snapshots));
        return applied;
    }

    /** True when the snapshot was stored; false when it was older than what is held (ignored, not an error). */
    boolean applyOne(Long org, PayableSnapshot s) {
        if (s == null || s.getSource() == null || s.getSourceRef() == null || s.getAmount() == null)
            throw new IllegalArgumentException("A payable needs a source, a reference and an amount.");
        long version = s.getSourceVersion() == null ? 0L : s.getSourceVersion();
        PayableDoc d = repo.findByOrganizationIdAndSourceAndSourceRef(org, s.getSource(), s.getSourceRef()).orElse(null);
        if (d != null && d.getSourceVersion() != null && d.getSourceVersion() > version) return false;
        boolean created = d == null;
        if (created) {
            d = new PayableDoc();
            d.setOrganizationId(org);
            d.setSource(s.getSource());
            d.setSourceRef(s.getSourceRef());
            d.setCreatedAt(LocalDateTime.now());
        }
        d.setSourceVersion(version);
        d.setPartyType(s.getPartyType() == null ? "VENDOR" : s.getPartyType());
        d.setPartyId(s.getPartyId());
        d.setPartyName(s.getPartyName());
        d.setDocNo(s.getDocNo());
        d.setDocDate(s.getDocDate());
        d.setIssuedAmount(s.getIssuedAmount() == null ? null : s.getIssuedAmount().abs());
        d.setDueDate(s.getDueDate());
        d.setAmount(s.getAmount().abs());
        d.setPaid(s.getPaid() == null ? BigDecimal.ZERO : s.getPaid());
        d.setStatus(statusOf(s.isVoided(), d.getAmount(), d.getPaid()));
        d.setUpdatedAt(LocalDateTime.now());
        try {
            repo.saveAndFlush(d);
        } catch (DataIntegrityViolationException raced) {
            // a concurrent delivery created it first; the retry will update it under the version guard
            throw new IllegalStateException("Payable " + s.getSource() + ":" + s.getSourceRef() + " was written concurrently; retry.");
        }
        // FP-4a — the statement trail, replaced whole under the SAME version guard (an older snapshot never got here).
        // null = an older sender that does not know about notes: keep what is held.
        if (s.getNotes() != null) {
            notes.deleteByDoc(d.getId());
            for (com.myplus.finance.dto.PayableNote n : s.getNotes()) {
                if (n == null || n.getAmount() == null) continue;
                com.myplus.finance.entity.PayableNoteRow r = new com.myplus.finance.entity.PayableNoteRow();
                r.setOrganizationId(org);
                r.setPayableDocId(d.getId());
                r.setNoteNo(n.getNoteNo());
                r.setNoteDate(n.getNoteDate());
                r.setAmount(n.getAmount().abs());
                notes.save(r);
            }
        }
        return true;
    }

    static String statusOf(boolean voided, BigDecimal amount, BigDecimal paid) {
        if (voided) return PayableDoc.VOID;
        return amount.subtract(paid == null ? BigDecimal.ZERO : paid).signum() > 0 ? PayableDoc.OPEN : PayableDoc.SETTLED;
    }

    /** Σ owed and the per-supplier breakdown — what FP-4 will serve to the supplier screens. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Long org = CurrentUser.organizationId();
        Map<Long, Map<String, Object>> byParty = new LinkedHashMap<>();
        for (PayableDoc d : repo.findOpen(org)) {
            Map<String, Object> row = byParty.computeIfAbsent(d.getPartyId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("partyType", d.getPartyType());
                m.put("partyId", d.getPartyId());
                m.put("partyName", d.getPartyName());
                m.put("open", BigDecimal.ZERO);
                m.put("documents", 0);
                return m;
            });
            row.put("open", ((BigDecimal) row.get("open")).add(d.open()));
            row.put("documents", (Integer) row.get("documents") + 1);
        }
        // Net per supplier: what is owed after overpayments on other bills are taken into account. Positive nets
        // add up to what business shows as the supplier balance (it floors each supplier at zero); negative nets
        // are SUPPLIER ADVANCES — money paid ahead, a debit balance business hides by flooring. Both are shown.
        BigDecimal netOwed = BigDecimal.ZERO, advances = BigDecimal.ZERO;
        List<Map<String, Object>> advanceRows = new ArrayList<>();
        for (Object[] r : repo.netBySupplier(org)) {
            BigDecimal net = r[2] == null ? BigDecimal.ZERO : (BigDecimal) r[2];
            if (net.signum() > 0) netOwed = netOwed.add(net);
            else if (net.signum() < 0) {
                advances = advances.add(net.negate());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("partyId", r[0]);
                m.put("partyName", r[1]);
                m.put("advance", net.negate());
                advanceRows.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openTotal", repo.sumOpen(org));          // Σ open documents (gross)
        out.put("netOwed", netOwed);                       // Σ per-supplier net, floored at 0 — business's figure
        out.put("supplierAdvances", advances);             // Σ suppliers paid ahead (debit balances)
        out.put("bySupplier", new ArrayList<>(byParty.values()));
        out.put("advancesBySupplier", advanceRows);
        // FP-3 — the same net, per feeding source. business's supplier balances cover PURCHASE only, so that is the
        // figure they reconcile to; EXPENSE_BILL is what expense-service's bills still owe.
        Map<String, Map<String, BigDecimal>> bySource = new LinkedHashMap<>();
        for (Object[] r : repo.netBySourceAndSupplier(org)) {
            BigDecimal net = r[2] == null ? BigDecimal.ZERO : (BigDecimal) r[2];
            Map<String, BigDecimal> m = bySource.computeIfAbsent((String) r[0], k -> {
                Map<String, BigDecimal> x = new LinkedHashMap<>();
                x.put("netOwed", BigDecimal.ZERO);
                x.put("supplierAdvances", BigDecimal.ZERO);
                return x;
            });
            if (net.signum() > 0) m.merge("netOwed", net, BigDecimal::add);
            else if (net.signum() < 0) m.merge("supplierAdvances", net.negate(), BigDecimal::add);
        }
        out.put("bySource", bySource);
        return out;
    }

    /**
     * The standing control: subledger open vs the GL's 2000 Accounts Payable. Reported, not enforced — a
     * difference that predates the subledger is a finding to investigate, never something to paper over here.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reconciliation() {
        // FP-6a: NET of advances (an overpaid bill is part of what GL 2000 holds) — see PayableDocRepository#sumNet
        BigDecimal open = repo.sumNet(CurrentUser.organizationId(), null);
        BigDecimal ap = BigDecimal.ZERO;
        @SuppressWarnings("unchecked")
        List<com.myplus.finance.dto.TrialBalanceRow> rows =
                (List<com.myplus.finance.dto.TrialBalanceRow>) gl.trialBalance(TenantClock.today()).get("rows");
        for (com.myplus.finance.dto.TrialBalanceRow r : rows == null ? List.<com.myplus.finance.dto.TrialBalanceRow>of() : rows) {
            if ("2000".equals(r.getCode())) {
                ap = (r.getCredit() == null ? BigDecimal.ZERO : r.getCredit())
                        .subtract(r.getDebit() == null ? BigDecimal.ZERO : r.getDebit());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subledgerOpen", open);
        out.put("purchaseNet", repo.sumNet(CurrentUser.organizationId(), "PURCHASE"));   // what business's purchases mirror
        out.put("expenseBillNet", repo.sumNet(CurrentUser.organizationId(), "EXPENSE_BILL"));   // E11 — what expense's bills mirror
        out.put("glAccountsPayable", ap);
        out.put("difference", ap.subtract(open));
        return out;
    }
}
