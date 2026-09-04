package com.myplus.auth.service;

import com.myplus.auth.entity.Organization;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.UserRepository;
import com.myplus.auth.config.JpaEntitlementSource;
import com.myplus.common.settings.OrganizationStatus;
import com.myplus.common.settings.Plan;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E2 — the platform operator's view of every tenant.
 *
 * <h3>This is the platform's first deliberate cross-tenant read, and it should feel unusual</h3>
 * {@code ARCHITECTURE-MULTITENANCY.md} says scope every read by {@code organization_id}. This service exists
 * to not. The only thing between it and a customer is {@code ROLE_ADMIN} on {@code OrgAdminController} — never
 * {@code ADMIN_PRIVILEGE}, which every tenant owner holds inside their own org and which would therefore hand
 * the list of every customer to every customer.
 *
 * <h3>What it deliberately does NOT expose</h3>
 * Account facts only: name, type, plan, trial state, owner, member count. <b>No trading data</b> — no orders,
 * no revenue, no "last sale". Shopify Partners draws that line and E2 draws it in the same place: how much a
 * tenant is trading is the tenant's business, and a console that shows it becomes a reporting screen on other
 * people's companies. Reaching real tenant data is E5's audited support session, deliberately not a shortcut
 * built here.
 */
@Service
@RequiredArgsConstructor
public class OrganizationAdminService {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final JpaEntitlementSource source;
    private final com.myplus.auth.repository.OrgSettingRepository orgSettings;
    /** ONB-3 — the memento written before a shape change clears anything. */
    private final com.myplus.auth.repository.OrgShapeHistoryRepository shapeHistoryRepo;
    private final com.myplus.common.settings.SettingsService settings;
    private final com.myplus.common.settings.CapabilityService capabilities;
    /** E4 — the control plane's record of its own decisions, written in the SAME transaction as each one. */
    private final ControlPlaneAuditService audit;

    /** Bound the page size whatever the caller asks for — an operator typo must not become a table scan. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * One page of tenants, newest first, optionally filtered by name.
     *
     * <h3>Why the owner email and member count are gathered in bulk</h3>
     * A per-row lookup would be the N+1 this platform has already been burned by. The page is fetched, then
     * its owners and memberships are read in <b>two</b> further queries for the whole page regardless of size.
     * At 25 rows that is 3 queries instead of 51.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(String q, int page, int size) {
        return search(q, page, size, false);
    }

    /**
     * ONB-2 — the same page, optionally narrowed to tenants that still need a business type.
     *
     * <h3>Filtered in JAVA, and stated plainly rather than hidden</h3>
     * "Needs a type" is {@code shapeSet == false || shape == general}, and both halves live in
     * {@code org_setting} — a different table, keyed by a string, with no row at all for the common case. A
     * SQL predicate over that is an outer join on a magic key returning a magic value: harder to read than the
     * rule it implements, and no faster at this size.
     *
     * <p><b>The cost is honest and bounded.</b> The filter reads every match and pages in memory, so its total
     * is a real count an operator can work down to zero rather than a page-local number that shrinks as they
     * fix things without ever finishing. At 41 tenants that is one extra pass. If this ever runs at thousands
     * the answer is a materialised {@code shape} column on {@code organizations} — a migration, not a cleverer
     * query here.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(String q, int page, int size, boolean needsTypeOnly) {
        int safeSize = size <= 0 ? 25 : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        // "%" means "everything" — one query serves both listing and searching, so the two can never drift
        // into different orderings. Lower-cased and wrapped HERE so the JPQL stays a plain LIKE: a
        // `:q IS NULL` branch cannot infer the parameter's type in Hibernate 6 and fails at runtime.
        String like = (q == null || q.isBlank()) ? "%" : "%" + q.trim().toLowerCase() + "%";

        List<Organization> rows;
        long total;
        if (needsTypeOnly) {
            List<Organization> matching = organizations.searchForOperator(
                            like, PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id")))
                    .getContent().stream()
                    .filter(this::needsBusinessType)
                    .toList();
            total = matching.size();
            int from = Math.min(safePage * safeSize, matching.size());
            rows = matching.subList(from, Math.min(from + safeSize, matching.size()));
        } else {
            Page<Organization> found = organizations.searchForOperator(
                    like, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
            rows = found.getContent();
            total = found.getTotalElements();
        }
        Map<Long, String> ownerEmails = ownerEmailsFor(rows);
        Map<Long, Integer> memberCounts = memberCountsFor(rows);
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Organization o : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("name", o.getName());
            m.put("type", o.getType());
            m.put("plan", Plan.byCode(o.getPlan()).code());
            m.put("trialEndsAt", o.getTrialEndsAt() == null ? null : o.getTrialEndsAt().toString());
            /*
             * COMPUTED HERE, never left to the browser to derive from a date.
             *
             * The operator and the entitlement resolver must agree about what "lapsed" means, and
             * JpaEntitlementSource already owns that comparison — a second one in JavaScript would be a
             * second source of truth for a fact the customer is judged on. 14 of 20 trials are lapsed right
             * now and nothing surfaces it.
             */
            m.put("trialLapsed", isTrialLapsed(o, now));
            m.put("status", o.getStatus());
            // ONB-1 — so the console can show what kind of business this is without a second call.
            m.put("shape", capabilities.shapeFor(o.getId()).code());
            /*
             * ⭐ RAW, not effective — the two answer different questions and only the raw one is a worklist.
             *
             * `shapeFor` returns GENERAL for a tenant that has never been asked, exactly as it does for one
             * that deliberately chose "General business". An operator remediating 37 unset tenants needs to
             * tell those apart, and the effective answer cannot. Same distinction C4 drew between
             * `overrideFor` (what was chosen) and `getBoolFor` (what applies).
             */
            m.put("shapeSet", settings
                    .overrideFor(o.getId(), com.myplus.common.settings.Shape.settingKey())
                    .isPresent());
            m.put("ownerEmail", ownerEmails.get(o.getOwnerUserId()));
            m.put("memberCount", memberCounts.getOrDefault(o.getId(), 0));
            out.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("rows", out);
        return result;
    }

    /**
     * ONB-2 — does this tenant still need a business type?
     *
     * <p>True when nobody has ever chosen one, <b>and also when the choice was {@code general}</b>. The second
     * half is the owner's ruling and not an oversight: {@code general} is the honest answer for a genuinely
     * general trader AND it is how a tenant ends up shown every vertical at once. The two are
     * indistinguishable in the data, so both go on the worklist and a person decides.
     */
    private boolean needsBusinessType(Organization o) {
        java.util.Optional<String> chosen =
                settings.overrideFor(o.getId(), com.myplus.common.settings.Shape.settingKey());
        return chosen.isEmpty()
                || com.myplus.common.settings.Shape.GENERAL.code().equalsIgnoreCase(chosen.get().trim());
    }

    /**
     * Is this tenant on a TRIAL that has run out?
     *
     * <p>Only meaningful for {@code TRIAL}: a {@code PRO} tenant with a stale {@code trial_ends_at} from a
     * previous life is not lapsed, and badging it would send an operator chasing a customer who is paying.
     */
    private boolean isTrialLapsed(Organization o, LocalDateTime now) {
        return Plan.byCode(o.getPlan()) == Plan.TRIAL
                && o.getTrialEndsAt() != null
                && o.getTrialEndsAt().isBefore(now);
    }

    /** Owner emails for a whole page in one query. */
    private Map<Long, String> ownerEmailsFor(List<Organization> rows) {
        List<Long> ownerIds = rows.stream().map(Organization::getOwnerUserId).filter(java.util.Objects::nonNull).toList();
        Map<Long, String> byId = new HashMap<>();
        if (ownerIds.isEmpty()) return byId;
        for (User u : users.findAllById(ownerIds)) byId.put(u.getId(), u.getEmail());
        return byId;
    }

    /**
     * Member counts for a whole page, in ONE query.
     *
     * <p>Counted in the database rather than by loading each org's memberships and calling {@code size()}:
     * that would be 25 queries per page and every membership row fetched to be thrown away. An org with no
     * members simply has no tuple, which is why the caller reads this map with a default of 0.
     */
    private Map<Long, Integer> memberCountsFor(List<Organization> rows) {
        Map<Long, Integer> counts = new HashMap<>();
        List<Long> ids = rows.stream().map(Organization::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return counts;
        for (Object[] tuple : memberships.countByOrganizationIds(ids)) {
            counts.put(((Number) tuple[0]).longValue(), ((Number) tuple[1]).intValue());
        }
        return counts;
    }

    /**
     * Change a tenant's plan. <b>The only place an operator writes {@code organizations.plan}</b>, so it is
     * where the {@link Plan} enum is enforced.
     *
     * <p>Closes finding F2: the column is free text, written by {@code createTenant} from a String, and
     * compared with {@code "TRIAL".equals(...)} in two places. Validating here means an operator cannot
     * produce a value that {@code Plan.byCode} will silently resolve to {@code FREE} — which would quietly
     * narrow what the customer may switch on, with nothing anywhere saying why.
     *
     * <p><b>{@code reason} is required.</b> Not decoration: a plan change is a commercial act, and E4 audits
     * these writes. A field the API does not enforce is a field half the callers will omit.
     */
    @Transactional
    public void changePlan(Long organizationId, String planCode, String reason, Long actorUserId) {
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required for a plan change.");

        // byCode falls back to FREE for anything unrecognised, which is right for a READ and wrong here:
        // an operator typing "PLATINUM" must be told, not silently given FREE. So match explicitly.
        Plan plan = null;
        for (Plan p : Plan.values()) {
            if (p.code().equalsIgnoreCase(planCode == null ? null : planCode.trim())) plan = p;
        }
        if (plan == null) throw new IllegalArgumentException("Unknown plan: " + planCode);

        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("No such organization: " + organizationId));

        // E4 — captured before the setter, or the event records PRO -> PRO and shows no change at all.
        String before = org.getPlan();

        org.setPlan(plan.code());
        // Moving OFF a trial clears its end date. Leaving a stale date behind would make a paying customer
        // read as a lapsed trial on the very screen an operator uses to decide who to chase.
        if (plan != Plan.TRIAL) org.setTrialEndsAt(null);
        organizations.save(org);

        audit.operatorAction(ControlPlaneAuditService.PLAN_CHANGE,
                ControlPlaneAuditService.ENTITY_ORGANIZATION, String.valueOf(organizationId),
                organizationId, before, plan.code(), reason, actorUserId, null);
    }

    /**
     * E3 — start or stop a tenant trading. <b>The most destructive action in the operator console.</b>
     *
     * <p>Guarded three ways, and each guards a different mistake:
     * <ol>
     *   <li>{@code ROLE_ADMIN} at the controller — a customer cannot reach it at all;</li>
     *   <li>an unknown status is REFUSED rather than stored — {@code status} is free text on the column, and
     *       an operator typing {@code SUSPEND} must be told, not left believing a customer is stopped while
     *       they carry on selling;</li>
     *   <li>the operator cannot suspend <b>their own</b> tenant — a console that locks its own operator out
     *       of the console that would undo it is a foot-gun with no undo.</li>
     * </ol>
     *
     * <p>The third is belt-and-braces: {@code AuthService} also exempts {@code ROLE_ADMIN} at the door. Two
     * independent guards, because there is no way back from this one without a DBA.
     *
     * <p><b>Reactivation is the same call</b>, deliberately. A lever that only goes one way is an accident
     * waiting to happen, and a wrong suspension stops a real business trading.
     *
     * @param actorOrgId the operator's OWN organization, so self-suspension can be refused
     */
    @Transactional
    public void changeStatus(Long organizationId, String statusCode, String reason,
                             Long actorUserId, Long actorOrgId) {
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required for a status change.");

        // parse(), not byCode(): reads fall back permissively so a bad value can never shut a shop, while a
        // WRITE must refuse what it does not recognise. One method doing both is how a silent fallback ends
        // up applied to an operator's typo.
        OrganizationStatus status = OrganizationStatus.parse(statusCode);
        if (status == null) throw new IllegalArgumentException("Unknown status: " + statusCode);

        if (status != OrganizationStatus.ACTIVE
                && actorOrgId != null && actorOrgId.equals(organizationId)) {
            throw new IllegalArgumentException(
                    "You cannot suspend or close your own organization.");
        }

        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("No such organization: " + organizationId));
        // E4 — before the setter. A suspension and a re-suspension must not read identically.
        String before = org.getStatus();
        org.setStatus(status.code());
        organizations.save(org);

        /*
         * Recorded AFTER the self-suspension guard above, so a refused attempt leaves no trace of a change
         * that did not happen. The attempt itself is not nothing — but it belongs in a security log, not in
         * the customer's own history of what was done to their account.
         */
        audit.operatorAction(ControlPlaneAuditService.STATUS_CHANGE,
                ControlPlaneAuditService.ENTITY_ORGANIZATION, String.valueOf(organizationId),
                organizationId, before, status.code(), reason, actorUserId, null);
        // No cache to invalidate: the status is read from the Organization row on the login/refresh path,
        // which loads it fresh every time. Deliberately NOT cached — this is a cold path, and a cached
        // suspension would be the one kind of staleness that lets a stopped tenant keep trading.
    }

    /**
     * ONB-1 — change a tenant's business type, RE-APPLYING that shape's defaults.
     *
     * <h3>This deliberately reverses a C4 rule, and the confirmation is why it is safe</h3>
     * {@code Shape}'s javadoc says a shape "never has the last word — an explicit tenant override always
     * wins", so that picking a profile could never <i>silently</i> destroy a deliberate choice. The whole
     * objection was that word. The console now names what will change before it changes it, so the trap C4
     * feared is closed — while the trap C4 <i>created</i>, a shape change that appears to do nothing, is the
     * one an owner actually hit: a pesticide dealer picked "Pharmacy" and went on seeing installments.
     *
     * <h3>Re-apply means CLEAR the overrides, not write thirteen rows</h3>
     * Deleting every {@code org.cap.*} row hands the decision back to the preset through the resolution order
     * exactly as documented — {@code overrideFor} returns empty for a missing row, so
     * {@code resolve} falls through to {@code shape.includes(capability)}. Writing the preset out as explicit
     * rows would reach the same answer today and leave every capability an override for ever, so the next
     * shape change would have to clear them anyway.
     *
     * <h3>The entitlement ceiling still wins</h3>
     * Clearing rows GRANTS nothing: {@code resolve} consults {@code revoked} first, so a capability the
     * platform withdrew stays off whatever the new shape's preset includes. That is asserted by the gate,
     * because a "re-apply" that could out-rank a revocation would be a back door around E1.
     */
    @Transactional
    public void changeShape(Long organizationId, String shapeCode, String reason, Long actorUserId) {
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required");
        // The OPERATOR path records why. The tenant changing its OWN type does not — see changeOwnShape.
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required for a business-type change.");
        applyShape(organizationId, shapeCode, actorUserId, reason);
    }

    /**
     * ONB-1 — a tenant changing its OWN business type, from its Configuration screen.
     *
     * <h3>Why this exists rather than routing the Configuration screen through {@code SettingsService.set}</h3>
     * {@code set} upserts one row. It would change the FALLBACK and leave every {@code org.cap.*} override in
     * place — so an owner picking "Pharmacy" would watch nothing happen, which is the exact complaint that
     * started this slice. Re-applying is the point, and it is more than one row.
     *
     * <h3>No reason required, deliberately</h3>
     * A reason is an audit artefact for an action taken on somebody ELSE'S tenant. Demanding one from an owner
     * describing their own business is bureaucracy that teaches people to type "x".
     *
     * <p>Scoped to {@code CurrentUser}: an owner can only ever change their own organization, so there is no
     * id parameter to tamper with.
     */
    @Transactional
    public void changeOwnShape(String shapeCode) {
        Long org = com.myplus.common.security.CurrentUser.organizationId();
        if (org == null) throw new IllegalArgumentException("No active organization");
        // A tenant changing its own type gives no reason (see the javadoc); the memento records that plainly
        // rather than inventing one, so a reader can tell an owner's change from an operator's.
        applyShape(org, shapeCode, com.myplus.common.security.CurrentUser.userId(),
                "Changed by the business itself");
    }

    /** The shared core: validate, clear the overrides, state the shape, evict. */
    private void applyShape(Long organizationId, String shapeCode, Long actorUserId, String reason) {
        // Validated here rather than through Shape.byCode, which falls back permissively to GENERAL. That
        // fallback is right for a READ — an unreadable stored value must never strip a working tenant's
        // screens — and wrong at a WRITE, where it would turn a typo into "show this customer everything".
        com.myplus.common.settings.Shape shape = null;
        for (com.myplus.common.settings.Shape candidate : com.myplus.common.settings.Shape.values()) {
            if (candidate.code().equalsIgnoreCase(shapeCode == null ? null : shapeCode.trim())) shape = candidate;
        }
        if (shape == null) throw new IllegalArgumentException("Unknown business type: " + shapeCode);

        organizations.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("No such organization: " + organizationId));

        List<com.myplus.auth.entity.OrgSetting> overrides =
                orgSettings.findByOrganizationIdAndSettingKeyStartingWith(organizationId, "org.cap.");

        /*
         * ONB-3 — RECORD BEFORE CLEARING, in the same transaction.
         *
         * A shape change either records what it destroyed or does not destroy it. Without this the tenant's
         * own switches are gone silently: switching back restores capabilities (the shape is just a settings
         * row) but applies the OTHER preset, not the choices the owner personally made. That was the one
         * irreversible part of a business-type change, and the only part nothing showed anyone.
         *
         * Ordered first for that reason, not for readability — a write that happens after the delete is a
         * write that a rollback between them turns into a lie.
         */
        String previousShape = settings
                .overrideFor(organizationId, com.myplus.common.settings.Shape.settingKey())
                .orElse(null);
        com.myplus.auth.entity.OrgShapeHistory memento = shapeHistoryRepo.save(
                com.myplus.auth.entity.OrgShapeHistory.builder()
                .organizationId(organizationId)
                .changedAt(LocalDateTime.now())
                .changedBy(actorUserId)
                .previousShape(previousShape)
                .newShape(shape.code())
                .previousOverrides(asJson(overrides))
                .reason(reason)
                .build());

        if (overrides != null && !overrides.isEmpty()) orgSettings.deleteAll(overrides);

        com.myplus.auth.entity.OrgSetting shapeRow = orgSettings
                .findByOrganizationIdAndSettingKey(organizationId, com.myplus.common.settings.Shape.settingKey())
                .orElseGet(() -> com.myplus.auth.entity.OrgSetting.builder()
                        .organizationId(organizationId)
                        .settingKey(com.myplus.common.settings.Shape.settingKey())
                        .build());
        shapeRow.setSettingValue(shape.code());
        shapeRow.setUserId(actorUserId);
        shapeRow.setUpdated(LocalDateTime.now());
        orgSettings.save(shapeRow);

        // The rows were written outside SettingsService.set, so nothing has evicted its cache. Without this
        // the operator changes a business type, watches nothing happen, and reports it as broken.
        settings.evictOrganization(organizationId);

        /*
         * E4 — ONE event, whichever door was used.
         *
         * This method serves BOTH an operator changing somebody else's business type and an owner changing
         * their own, so the actor type is the one place it must be DERIVED rather than stated: the emitter
         * compares the actor's org with the subject's, which is true by construction on both paths. Stating
         * it here would mean stating it twice, and the second one would eventually be wrong.
         *
         * The event points at the ONB-3 memento rather than repeating it (ruling D-3). They answer different
         * questions — the memento is the state an undo will read, this is the trail — and the count is
         * carried because "11 switches cleared" is what makes an operator look at the memento at all.
         */
        int cleared = overrides == null ? 0 : overrides.size();
        audit.shapeAction(String.valueOf(memento.getId()), organizationId,
                previousShape, shape.code(), reason, actorUserId,
                cleared == 0 ? null : cleared + " capability overrides cleared");
    }

    /**
     * ONB-1 — what a shape change would DO, so the confirmation can name it instead of asking "are you sure?".
     *
     * <p>Computed from the tenant's CURRENT effective map against the target preset, so the two lists are true
     * for this tenant rather than generic prose. A dialog that lists nothing when nothing would change is far
     * more useful than one that always warns.
     *
     * @return {@code {turningOn: [...], turningOff: [...]}}, capability labels
     */
    @Transactional(readOnly = true)
    public Map<String, Object> previewShape(Long organizationId, String shapeCode) {
        com.myplus.common.settings.Shape target = com.myplus.common.settings.Shape.byCode(shapeCode);
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (com.myplus.common.settings.Capability c : com.myplus.common.settings.Capability.values()) {
            boolean now = capabilities.isEnabledFor(organizationId, c);
            // What the preset alone would give, bounded by what the platform still allows: a capability the
            // tenant is not entitled to must never be advertised as "turning on".
            boolean next = target.includes(c) && !entitlementBlocks(organizationId, c);
            if (next && !now) on.add(c.label());
            if (!next && now) off.add(c.label());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", target.code());
        out.put("turningOn", on);
        out.put("turningOff", off);
        return out;
    }

    /** True when the platform has withdrawn this capability, whatever a preset says. */
    private boolean entitlementBlocks(Long organizationId, com.myplus.common.settings.Capability c) {
        return source.revoked(organizationId, c);
    }

    /**
     * The cleared overrides as a JSON object, so an undo has something to restore.
     *
     * <p>Hand-built rather than through Jackson: the payload is a flat map of two short strings per entry, the
     * keys are a closed set the platform generates ({@code org.cap.*}), and adding a mapper dependency to this
     * service for one snapshot is more moving parts than the problem has. Values are escaped for quotes and
     * backslashes — the only characters a settings value can hold that would break the shape.
     *
     * <p>Empty object rather than null when nothing was cleared, so a reader can tell "nothing to restore"
     * from "we did not record".
     */
    private String asJson(List<com.myplus.auth.entity.OrgSetting> rows) {
        StringBuilder sb = new StringBuilder("{");
        if (rows != null) {
            boolean first = true;
            for (com.myplus.auth.entity.OrgSetting r : rows) {
                if (r.getSettingValue() == null) continue;   // a cleared row has nothing to put back
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(r.getSettingKey())).append('"')
                  .append(':')
                  .append('"').append(escape(r.getSettingValue())).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** ONB-3 — one tenant's business-type changes, newest first. Feeds the operator's history view. */
    @Transactional(readOnly = true)
    public Map<String, Object> shapeHistory(Long organizationId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.myplus.auth.entity.OrgShapeHistory h
                : shapeHistoryRepo.findByOrganizationIdOrderByChangedAtDesc(organizationId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("changedAt", h.getChangedAt() == null ? null : h.getChangedAt().toString());
            m.put("changedBy", h.getChangedBy());
            m.put("previousShape", h.getPreviousShape());
            m.put("newShape", h.getNewShape());
            m.put("previousOverrides", h.getPreviousOverrides());
            m.put("reason", h.getReason());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organizationId", organizationId);
        out.put("rows", rows);
        return out;
    }
}
