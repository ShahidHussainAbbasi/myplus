package com.myplus.auth.service;

import com.myplus.auth.entity.Organization;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.UserRepository;
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
        int safeSize = size <= 0 ? 25 : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        // "%" means "everything" — one query serves both listing and searching, so the two can never drift
        // into different orderings. Lower-cased and wrapped HERE so the JPQL stays a plain LIKE: a
        // `:q IS NULL` branch cannot infer the parameter's type in Hibernate 6 and fails at runtime.
        String like = (q == null || q.isBlank()) ? "%" : "%" + q.trim().toLowerCase() + "%";

        Page<Organization> found = organizations.searchForOperator(
                like, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));

        List<Organization> rows = found.getContent();
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
            m.put("ownerEmail", ownerEmails.get(o.getOwnerUserId()));
            m.put("memberCount", memberCounts.getOrDefault(o.getId(), 0));
            out.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", found.getTotalElements());
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("rows", out);
        return result;
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

        org.setPlan(plan.code());
        // Moving OFF a trial clears its end date. Leaving a stale date behind would make a paying customer
        // read as a lapsed trial on the very screen an operator uses to decide who to chase.
        if (plan != Plan.TRIAL) org.setTrialEndsAt(null);
        organizations.save(org);
    }
}
