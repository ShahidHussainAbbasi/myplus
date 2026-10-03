package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.PayablesSource;
import com.myplus.business_service.repository.PayablesSourceRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * FP-4b — the per-tenant switch that decides where supplier screens read from (strangler fig, reversible).
 *
 * <h3>Who</h3>
 * A platform operator ({@code ROLE_ADMIN}) only — a console decision like a plan or an entitlement, never the owner's
 * (ruling 4). Everyone else gets 403.
 *
 * <h3>When it is refused</h3>
 * Switching TO FINANCE is refused unless business's sum of supplier balances equals finance's purchase net for that
 * tenant (ruling 5) — the screens would otherwise change figures under the owner's feet. The GL 2000 difference is
 * shown and kept on the row as evidence, never a block. Switching BACK is always allowed: it is the rollback.
 */
@Service
@RequiredArgsConstructor
public class PayablesSourceService {

    private final PayablesSourceRepo repo;
    private final VenderRepo venders;
    private final ObjectProvider<FinanceClient> finance;
    private final AuditService audit;

    /** FP-5b — mixed payments (purchases + bills in one Pay Supplier) pin a tenant to FINANCE (ruling 4). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BillApplicationOutboxService billApplications;

    /** The source this tenant's supplier screens read from. No row = BUSINESS. Cheap: one primary-key read. */
    @Transactional(readOnly = true)
    public String sourceOf(Long organizationId) {
        if (organizationId == null) return PayablesSource.BUSINESS;
        return repo.findById(organizationId).map(PayablesSource::getSource).orElse(PayablesSource.BUSINESS);
    }

    public boolean readsFromFinance(Long organizationId) {
        return PayablesSource.FINANCE.equals(sourceOf(organizationId));
    }

    /**
     * FP-4c — what this business owes a supplier, as the tenant's switch decides. On FINANCE (ruling 2: total exposure)
     * = purchases still owed ({@code due_amount}: synchronous, always current) + expense bills still owed (stamped by
     * finance; bills change only there). Deliberately NOT a stamped total: a stamp lags its event, and a credit limit
     * read from a lagging total could miss the purchase made a moment ago. On BUSINESS = {@code due_amount}, unchanged.
     */
    public static BigDecimal owedTo(com.myplus.business_service.entity.Vender v, boolean fromFinance) {
        if (v == null) return BigDecimal.ZERO;
        BigDecimal due = nz(v.getDueAmount());
        return fromFinance ? due.add(nz(v.getPayableOtherOpen())) : due;
    }

    /** The operator's view: the current source and the reconciliation that decides whether it may change. */
    @Transactional(readOnly = true)
    public Map<String, Object> status(Long organizationId) {
        assertOperator();
        if (organizationId == null) throw new ValidationException("Choose a business.");
        Map<String, Object> out = reconciliation(organizationId);
        PayablesSource row = repo.findById(organizationId).orElse(null);
        out.put("source", row == null ? PayablesSource.BUSINESS : row.getSource());
        out.put("mixedPayments", billApplications == null ? 0 : billApplications.mixedPayments(organizationId));   // FP-5b
        if (row != null) {
            out.put("reason", row.getReason());
            out.put("switchedAt", row.getSwitchedAt());
            out.put("switchedBy", row.getSwitchedBy());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> switchTo(Long organizationId, String source, String reason) {
        assertOperator();
        if (organizationId == null) throw new ValidationException("Choose a business.");
        String to = source == null ? "" : source.trim().toUpperCase();
        if (!PayablesSource.BUSINESS.equals(to) && !PayablesSource.FINANCE.equals(to))
            throw new ValidationException("Supplier balances can be read from business or finance.");
        if (reason == null || reason.isBlank()) throw new ValidationException("Say why the source is being changed.");
        // FP-5b ruling 4 — business's own screens cannot show a payment that settled purchases AND expense bills
        // (its statement would put the bill share against purchases). Once one exists, the way back is closed.
        long mixed = billApplications == null ? 0 : billApplications.mixedPayments(organizationId);
        if (PayablesSource.BUSINESS.equals(to) && mixed > 0)
            throw new ValidationException("Not switched back: this business has " + mixed + " supplier payment share(s) that settled "
                    + "expense bills together with purchases. Business screens cannot show those correctly, so its supplier "
                    + "figures stay in finance.");
        Map<String, Object> rec = reconciliation(organizationId);
        if (PayablesSource.FINANCE.equals(to) && !Boolean.TRUE.equals(rec.get("canSwitch")))
            throw new ValidationException("Not switched: business and finance disagree by " + rec.get("difference")
                    + " on what this business owes its suppliers. They must agree exactly first.");
        PayablesSource row = repo.findById(organizationId).orElseGet(PayablesSource::new);
        String why = reason.trim();
        row.setOrganizationId(organizationId);
        row.setSource(to);
        row.setReason(why.length() > 255 ? why.substring(0, 255) : why);
        row.setSwitchedBy(CurrentUser.userId());
        row.setSwitchedAt(LocalDateTime.now());
        row.setBusinessDue((BigDecimal) rec.get("businessDue"));
        row.setFinancePurchaseNet((BigDecimal) rec.get("financePurchaseNet"));
        row.setGlDifference((BigDecimal) rec.get("glDifference"));
        repo.save(row);
        audit.record("PAYABLES_SOURCE_SWITCHED", "ORGANIZATION", String.valueOf(organizationId),
                (BigDecimal) rec.get("difference"), "to " + to + ": " + row.getReason());
        Map<String, Object> out = new LinkedHashMap<>(rec);
        out.put("source", to);
        out.put("reason", row.getReason());
        out.put("switchedAt", row.getSwitchedAt());
        out.put("switchedBy", row.getSwitchedBy());
        return out;
    }

    /**
     * business's sum of supplier balances vs finance's purchase net (blocks), and finance's subledger vs GL 2000
     * (warns). Finance is asked AS THAT TENANT — the operator's own org would answer with the platform's figures.
     */
    Map<String, Object> reconciliation(Long org) {
        BigDecimal businessDue = venders.sumDueByOrg(org);
        AtomicReference<Map<String, Object>> summary = new AtomicReference<>();
        AtomicReference<Map<String, Object>> recon = new AtomicReference<>();
        FinanceClient f = finance.getIfAvailable();
        if (f == null) throw new ValidationException("Finance is not reachable right now; nothing was changed.");
        try {
            GatewayIdentityForwarding.runAs(CurrentUser.userId(), org, () -> {
                summary.set(f.payablesSummary());
                recon.set(f.payablesReconciliation());
            });
        } catch (Exception e) {
            throw new ValidationException("Finance is not reachable right now; nothing was changed.");
        }
        BigDecimal financeNet = purchaseNet(summary.get());
        BigDecimal difference = nz(businessDue).subtract(financeNet);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organizationId", org);
        out.put("businessDue", nz(businessDue));
        out.put("financePurchaseNet", financeNet);
        out.put("difference", difference);
        out.put("canSwitch", difference.signum() == 0);
        Map<String, Object> r = recon.get();
        out.put("financeOpenAll", num(r == null ? null : r.get("subledgerOpen")));
        out.put("glAccountsPayable", num(r == null ? null : r.get("glAccountsPayable")));
        out.put("glDifference", num(r == null ? null : r.get("difference")));
        return out;
    }

    @SuppressWarnings("unchecked")
    static BigDecimal purchaseNet(Map<String, Object> summary) {
        if (summary == null) return BigDecimal.ZERO;
        Object bySource = summary.get("bySource");
        if (!(bySource instanceof Map)) return BigDecimal.ZERO;
        Object purchase = ((Map<String, Object>) bySource).get("PURCHASE");
        if (!(purchase instanceof Map)) return BigDecimal.ZERO;
        return num(((Map<String, Object>) purchase).get("netOwed"));
    }

    static BigDecimal num(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(o));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static void assertOperator() {
        if (!CurrentUser.isPlatformOperator())
            throw new AccessDeniedException("Only MaxTheService can change where supplier balances are read from.");
    }
}
