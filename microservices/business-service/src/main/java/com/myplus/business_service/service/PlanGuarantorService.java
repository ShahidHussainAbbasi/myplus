package com.myplus.business_service.service;

import com.myplus.business_service.dto.GuarantorDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.PlanGuarantor;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.PlanGuarantorRepo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R4 — the people who stand behind a financed sale.
 *
 * <h3>What this service is careful about</h3>
 * <ul>
 *   <li><b>The count is a tenant POLICY.</b> {@code installments.guarantorsRequired} defaults to <b>0</b>, so
 *       a deploy changes nothing for the 40 of 43 tenants that never asked for the rule. See
 *       {@code BusinessSettingsCatalog}.</li>
 *   <li><b>The identity is stamped</b>, never derived on read — the shop's evidence is what was signed.</li>
 *   <li><b>The party link is best-effort</b> and written after the row exists, so a party-service outage
 *       costs a cross-reference and never a guarantor.</li>
 * </ul>
 */
@Service
public class PlanGuarantorService {

    private static final Logger LOG = LoggerFactory.getLogger(PlanGuarantorService.class);

    /** The tenant setting that says how many a financed sale must name. Default 0 — see the catalog. */
    public static final String REQUIRED_KEY = "installments.guarantorsRequired";

    @Autowired private PlanGuarantorRepo repo;
    @Autowired private CustomerRepo customerRepo;
    @Autowired(required = false) private com.myplus.common.settings.SettingsService settingsService;

    // ── the rule ────────────────────────────────────────────────────────────────────────────────────

    /**
     * How many guarantors this shop requires. <b>Zero unless the shop said otherwise.</b>
     *
     * <p>Fails to 0 when settings cannot be read, deliberately: an unreadable setting must not start refusing
     * plans for a rule the tenant never set. The permissive direction is the one that leaves the shop
     * trading.
     */
    public int requiredCount(Long orgId) {
        if (settingsService == null) return 0;
        try {
            // The fallback is 0 in BOTH places on purpose: the catalog's default and this call site agree,
            // so a tenant that never set the key is never refused a plan by it.
            int n = settingsService.getInt(REQUIRED_KEY, 0);
            return Math.max(n, 0);
        } catch (Exception unreadable) {
            LOG.warn("guarantorsRequired unreadable for org {} — treating as 0", orgId, unreadable);
            return 0;
        }
    }

    /**
     * Check the guarantors a sale is carrying, BEFORE any plan is created.
     *
     * @return an operator-readable refusal, or {@code null} when the sale may proceed
     *
     * <h3>The refusal names the number, because "invalid" is not actionable</h3>
     * A cashier who is told "this sale needs 2 guarantors; 1 has been entered" knows what to do. One who is
     * told the request was invalid does not.
     *
     * <h3>Two slips this refuses, and why each matters</h3>
     * <ul>
     *   <li><b>The same person twice.</b> Two rows, one guarantor — the shop believes it has two people and
     *       has one.</li>
     *   <li><b>The buyer guaranteeing himself.</b> A plan guaranteed by its own debtor is worth precisely
     *       nothing, and it is the easiest mistake the form can make.</li>
     * </ul>
     */
    public String validate(Long orgId, Long buyerCustomerId, List<GuarantorDTO> submitted) {
        List<GuarantorDTO> named = namedOnly(submitted);
        int required = requiredCount(orgId);

        if (named.size() < required) {
            return "this sale needs " + required + " guarantor" + (required == 1 ? "" : "s")
                    + "; " + named.size() + " " + (named.size() == 1 ? "has" : "have") + " been entered.";
        }
        if (named.isEmpty()) return null;   // nothing more to check, and nothing was required

        // The same person twice. Keyed on CNIC where there is one, otherwise on name+contact — a shop that
        // records guarantors by name alone still must not record the same one twice.
        Set<String> seen = new LinkedHashSet<>();
        for (GuarantorDTO g : named) {
            String key = identityKey(g);
            if (!seen.add(key)) {
                return "the same guarantor has been entered twice (" + trim(g.getName()) + ").";
            }
        }

        /*
         * The buyer standing behind his own debt.
         *
         * ⚠ MATCHED ON THREE SIGNALS, because CNIC alone is dead code in practice. The first cut compared
         * CNICs only and the gate caught it immediately: the sale path does not persist `customer.cnic`, so
         * a buyer created during the sale has none — and platform-wide only 10 of 2,545 customers carry one.
         * A guard that fires for 0.4% of customers is not a guard.
         *
         * So, in order of how certain each signal is:
         *   1. customerId — the cashier recalled the buyer's own record. Definitive.
         *   2. CNIC       — the same national identifier. Definitive when both sides have one.
         *   3. contact    — the same phone. Not proof of one person, but a guarantor reachable ONLY on the
         *                   debtor's own number cannot be contacted independently of him, which is the one
         *                   thing a guarantor has to be. Phone is also NOT NULL on Customer, so this is the
         *                   signal that actually fires.
         *
         * The message names WHICH matched, so a shopkeeper whose customer and guarantor genuinely share a
         * household phone knows to put the guarantor's own number in rather than guessing at a refusal.
         */
        Customer buyer = buyerCustomerId == null ? null : customerRepo.findById(buyerCustomerId).orElse(null);
        if (buyer != null) {
            String buyerCnic = normaliseCnic(buyer.getCnic());
            String buyerPhone = normalisePhone(buyer.getContact());
            for (GuarantorDTO g : named) {
                if (g.getCustomerId() != null && g.getCustomerId().equals(buyerCustomerId)) {
                    return "the customer buying cannot also be the guarantor. A guarantor must be somebody else.";
                }
                if (buyerCnic != null && buyerCnic.equals(normaliseCnic(g.getCnic()))) {
                    return "the customer buying cannot also be the guarantor — that is their own CNIC. "
                            + "A guarantor must be somebody else.";
                }
                if (buyerPhone != null && buyerPhone.equals(normalisePhone(g.getContact()))) {
                    return "that is the buyer's own mobile number. A guarantor needs a number they can be "
                            + "reached on independently of the customer.";
                }
            }
        }
        return null;
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Stamp the guarantors onto a plan.
     *
     * <p>Called after {@link #validate} has passed and the plan exists. Everything the shop relies on is
     * written here, locally: the party link is attached separately and may never arrive.
     */
    @Transactional
    public List<PlanGuarantor> save(Long orgId, Long planId, Long userId, List<GuarantorDTO> submitted) {
        List<PlanGuarantor> saved = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (GuarantorDTO g : namedOnly(submitted)) {
            PlanGuarantor row = new PlanGuarantor();
            row.setOrganizationId(orgId);
            row.setPlanId(planId);
            row.setRole(PlanGuarantor.WITNESS.equalsIgnoreCase(trim(g.getRole()))
                    ? PlanGuarantor.WITNESS : PlanGuarantor.GUARANTOR);
            row.setName(trim(g.getName()));
            // Stored EXACTLY as typed. An identifier that is not CNIC-shaped is still somebody's identifier,
            // and this product ships in six languages.
            row.setCnic(trim(g.getCnic()));
            row.setContact(trim(g.getContact()));
            row.setAddress(trim(g.getAddress()));
            row.setCustomerId(g.getCustomerId());
            row.setCreatedAt(now);
            row.setCreatedBy(userId);
            saved.add(repo.save(row));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PlanGuarantor> forPlan(Long orgId, Long planId) {
        if (orgId == null || planId == null) return List.of();
        return repo.findByOrganizationIdAndPlanIdOrderByIdAsc(orgId, planId);
    }

    @Transactional
    public boolean delete(Long orgId, Long id) {
        PlanGuarantor row = id == null ? null : repo.findById(id).orElse(null);
        // Scoped before deleting: an id off the wire is not an id followed from a row the caller could see.
        if (row == null || !orgId.equals(row.getOrganizationId())) return false;
        repo.delete(row);
        return true;
    }

    // ── recall ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * R4 — recall a guarantor this shop has used before, by their complete CNIC.
     *
     * <h3>⚠ Exact match, never a prefix</h3>
     * A prefix search would let a member of staff type {@code 352} and walk a list of national identifiers.
     * A complete match cannot be walked — the caller already has to be holding the card. Anything shorter
     * than {@link #MIN_RECALL_DIGITS} digits recalls nobody, and the answer is always scoped to the caller's
     * own organisation.
     */
    public static final int MIN_RECALL_DIGITS = 13;

    @Transactional(readOnly = true)
    public Map<String, Object> recall(Long orgId, String cnic) {
        String norm = normaliseCnic(cnic);
        if (orgId == null || norm == null || norm.length() < MIN_RECALL_DIGITS) return Map.of();

        // Matched on DIGITS, so a card typed 3520112345678 finds a row saved as 35201-1234567-8 and the
        // other way round. A shop does not type its punctuation the same way twice, and a recall that
        // depended on it would look broken for the exact person it was built to find.
        for (PlanGuarantor g : repo.recallByNormalisedCnic(orgId, norm)) {
            return asMap(g);
        }
        return Map.of();
    }

    /** The people this shop uses most — the one-tap recall chips. Bounded: a chip row is not a report. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(Long orgId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (orgId == null) return out;
        for (Object[] r : repo.recentForOrg(orgId)) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r[0]);
            m.put("cnic", r[1]);
            m.put("contact", r[2]);
            m.put("address", r[3]);
            m.put("uses", r[4]);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> asMap(PlanGuarantor g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("planId", g.getPlanId());
        m.put("role", g.getRole());
        m.put("name", g.getName());
        m.put("cnic", g.getCnic());
        m.put("contact", g.getContact());
        m.put("address", g.getAddress());
        m.put("customerId", g.getCustomerId());
        m.put("partyId", g.getPartyId());
        m.put("createdAt", g.getCreatedAt() == null ? null : g.getCreatedAt().toString());
        return m;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** Rows with no name are not guarantors — an empty block a cashier tabbed through is not an entry. */
    private List<GuarantorDTO> namedOnly(List<GuarantorDTO> submitted) {
        List<GuarantorDTO> out = new ArrayList<>();
        if (submitted == null) return out;
        for (GuarantorDTO g : submitted) {
            if (g != null && trim(g.getName()) != null) out.add(g);
        }
        return out;
    }

    private String identityKey(GuarantorDTO g) {
        String c = normaliseCnic(g.getCnic());
        if (c != null) return "C:" + c;
        return "N:" + String.valueOf(trim(g.getName())).toLowerCase()
                + "|" + String.valueOf(trim(g.getContact()));
    }

    /**
     * Digits only, keeping the last 10 — so 0300-1234567, 03001234567 and +92 300 1234567 are one number.
     *
     * <p>Ten because a Pakistani mobile is 10 digits after the country or trunk prefix, and a shop types it
     * whichever way it feels like. Fewer than that is not a number worth comparing.
     */
    static String normalisePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 10) return null;
        return digits.substring(digits.length() - 10);
    }

    /** Digits only, so 35201-1234567-8 and 3520112345678 are the same person. Null when there is nothing. */
    static String normaliseCnic(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String trim(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
