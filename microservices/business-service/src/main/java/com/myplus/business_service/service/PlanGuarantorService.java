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
     * The outcome of looking over what a sale entered: the rows to KEEP, and what to tell the shop.
     *
     * <p>There is no "refused" state, deliberately — see {@link #review}.
     */
    public static final class GuarantorReview {
        private final List<GuarantorDTO> accepted;
        private final List<String> notes;

        GuarantorReview(List<GuarantorDTO> accepted, List<String> notes) {
            this.accepted = accepted;
            this.notes = notes;
        }

        /** The rows worth saving — everything entered, minus any dropped row named in {@link #notes()}. */
        public List<GuarantorDTO> accepted() { return accepted; }

        /** Operator-readable remarks. Empty when there is nothing to say. */
        public List<String> notes() { return notes; }

        /** The remarks as one sentence for the plan message, or {@code null} when there are none. */
        public String message() {
            return notes.isEmpty() ? null : String.join(" ", notes);
        }
    }

    /**
     * Look over the guarantors a sale is carrying. <b>Never refuses anything.</b>
     *
     * <h3>⭐ R4b — a guarantor is ASKED FOR, never demanded</h3>
     * This was {@code validate()}, and it returned a refusal that stopped the plan while {@code main.js}
     * stopped the SALE outright — the only plan rule that did, where unsound terms and an uncollected
     * deposit both let the sale complete with a message. A shop with a customer at the counter and no
     * guarantor present simply could not sell.
     *
     * <p>So {@code installments.guarantorsRequired} is now how many the screen ASKS for, and no guarantor
     * problem can refuse a sale or a plan. That is what makes "optional" true: a count that still blocked on
     * a duplicate would be optional-with-exceptions, which is not what was asked for.
     *
     * <h3>The two integrity checks survive as DROPS, not refusals</h3>
     * They exist to stop a shop believing it holds two guarantors when it holds one, and a note naming the
     * dropped row prevents that just as well as a refusal did — without a counter that will not sell.
     * <ul>
     *   <li><b>The same person twice.</b> The second row is dropped; one guarantor is one guarantor.</li>
     *   <li><b>The buyer guaranteeing himself.</b> Worth precisely nothing, so it is not recorded as if it
     *       were worth something.</li>
     * </ul>
     *
     * @return what to save and what to say — never {@code null}
     */
    public GuarantorReview review(Long orgId, Long buyerCustomerId, List<GuarantorDTO> submitted) {
        List<GuarantorDTO> named = namedOnly(submitted);
        List<GuarantorDTO> accepted = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Customer buyer = buyerCustomerId == null ? null : customerRepo.findById(buyerCustomerId).orElse(null);
        String buyerCnic = buyer == null ? null : normaliseCnic(buyer.getCnic());
        String buyerPhone = buyer == null ? null : normalisePhone(buyer.getContact());

        Set<String> seen = new LinkedHashSet<>();
        for (GuarantorDTO g : named) {
            // Keyed on CNIC where there is one, otherwise on name+contact — a shop that records guarantors
            // by name alone still must not record the same one twice.
            if (!seen.add(identityKey(g))) {
                notes.add("The same guarantor was entered twice (" + trim(g.getName())
                        + "); the duplicate was not recorded.");
                continue;
            }

            /*
             * The buyer standing behind his own debt.
             *
             * ⚠ MATCHED ON THREE SIGNALS, because CNIC alone is dead code in practice. The first cut
             * compared CNICs only and the gate caught it immediately: the sale path does not persist
             * `customer.cnic`, so a buyer created during the sale has none — and platform-wide only 10 of
             * 2,545 customers carry one. A guard that fires for 0.4% of customers is not a guard.
             *
             * In order of how certain each signal is:
             *   1. customerId — the cashier recalled the buyer's own record. Definitive.
             *   2. CNIC       — the same national identifier. Definitive when both sides have one.
             *   3. contact    — the same phone. Not proof of one person, but a guarantor reachable ONLY on
             *                   the debtor's own number cannot be contacted independently of him, which is
             *                   the one thing a guarantor has to be. Phone is also NOT NULL on Customer, so
             *                   this is the signal that actually fires.
             *
             * The note names WHICH matched, so a shopkeeper whose customer and guarantor genuinely share a
             * household phone knows to put the guarantor's own number in.
             */
            String self = selfGuaranteeReason(g, buyerCustomerId, buyerCnic, buyerPhone);
            if (self != null) {
                notes.add(self);
                continue;
            }
            accepted.add(g);
        }

        /*
         * The shortfall — a REMARK now, never a refusal. Still worth saying: a shop that asked to be
         * prompted for two wants to notice when it recorded one, and the plan message is where it will.
         */
        int required = requiredCount(orgId);
        if (required > 0 && accepted.size() < required) {
            notes.add("Recorded " + accepted.size() + " of " + required + " guarantor"
                    + (required == 1 ? "" : "s")
                    + " — the plan stands; add the rest on the plan when you have them.");
        }
        return new GuarantorReview(accepted, notes);
    }

    /** Why this row is the buyer, or {@code null} when it is somebody else. See the note in {@link #review}. */
    private String selfGuaranteeReason(GuarantorDTO g, Long buyerCustomerId, String buyerCnic,
            String buyerPhone) {
        String who = trim(g.getName());
        if (buyerCustomerId != null && buyerCustomerId.equals(g.getCustomerId())) {
            return "The customer buying cannot also be the guarantor (" + who
                    + "); that entry was not recorded.";
        }
        if (buyerCnic != null && buyerCnic.equals(normaliseCnic(g.getCnic()))) {
            return "The customer buying cannot also be the guarantor (" + who
                    + ") — that is their own CNIC; that entry was not recorded.";
        }
        if (buyerPhone != null && buyerPhone.equals(normalisePhone(g.getContact()))) {
            return "That is the buyer's own mobile number (" + who + "), so a guarantor could not be "
                    + "reached independently of the customer; that entry was not recorded.";
        }
        return null;
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Stamp the guarantors onto a plan.
     *
     * <p>Called with {@link GuarantorReview#accepted()} once the plan exists — the caller passes the rows
     * that survived review, so a dropped duplicate cannot be written by a caller that forgot. Everything
     * the shop relies on is written here, locally: the party link is attached separately and may never arrive.
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
