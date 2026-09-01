package com.myplus.common.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.myplus.common.security.CurrentUser;

/**
 * C1 — the one place that answers "may this tenant do this?".
 *
 * <h3>The gap this closes</h3>
 * Before this, a capability was hidden by a menu and nothing else. The API answered anyone who asked, so the
 * control was a suggestion: any caller who knew the URL got the behaviour whether the tenant had it or not.
 * <b>Hiding a menu is not security.</b> That was the only item in the capability review that was a defect
 * rather than an improvement, and it is what {@code assertEnabled} exists for.
 *
 * <h3>Reading is cheap on purpose</h3>
 * It delegates to {@link SettingsService}, which holds a tenant's whole override map behind a bounded
 * per-tenant Caffeine cache (PERF-C1). So a capability check on a hot path costs a map lookup, not a query —
 * which matters because the intended use is a guard on every write, not an occasional screen decision.
 *
 * <h3>Fail OPEN for visibility, CLOSED for money</h3>
 * A capability that cannot be resolved — no tenant on the request, settings unavailable — resolves to
 * {@code true}. A tenant losing a screen it used yesterday is a support call; the alternative default would
 * turn any settings hiccup into an outage. Callers guarding something that touches <b>stock, ledger or tax</b>
 * must not rely on that: they use {@link #assertEnabled}, which refuses when it cannot prove the tenant is
 * allowed.
 */
@Service
public class CapabilityService {

    private final SettingsService settings;

    /**
     * E1 — the entitlement CEILING over {@link #resolve}.
     *
     * <p>REQUIRED injection against a bean that {@code CommonSettingsAutoConfiguration} always publishes
     * ({@link EntitlementSource#PERMISSIVE} under {@code @ConditionalOnMissingBean}). Not optional, and the
     * distinction matters: an {@code @Autowired(required = false)} ceiling that silently disappeared would
     * leave a class that reads as protection and is not — the exact failure {@code JpaSettingsStore}'s javadoc
     * records from OMS O3.
     */
    private final EntitlementSource entitlements;

    public CapabilityService(SettingsService settings, EntitlementSource entitlements) {
        this.settings = settings;
        this.entitlements = entitlements;
    }

    /**
     * Is this capability on for the CALLER's tenant?
     *
     * <p>Fails open — see the class javadoc. Use for deciding what to render.
     */
    public boolean isEnabled(Capability capability) {
        return isEnabledFor(CurrentUser.organizationId(), capability);
    }

    /**
     * Is it on for an explicitly named tenant?
     *
     * <p>Exists for the same reason {@code SettingsService.effectiveFor} does: not every reader of a tenant
     * policy is an authenticated member of that tenant. A background scanner, or a service acting for a
     * storefront shopper, knows the org from the row it is working on rather than from a security context.
     */
    public boolean isEnabledFor(Long organizationId, Capability capability) {
        if (capability == null) return true;              // nothing asked for, nothing to refuse
        try {
            return resolveEffective(organizationId, capability);
        } catch (RuntimeException settingsUnavailable) {
            // Fail OPEN: a settings outage must not take away screens that worked a minute ago.
            return true;
        }
    }

    /**
     * C3c — the token first, the local store second. <b>One method, so the render side and the refusal side
     * cannot answer differently.</b>
     *
     * <h3>Why the token wins</h3>
     * {@code org_setting} is per-SERVICE but a capability is per-TENANT, which gave N answers to one question:
     * an owner switched {@code rxRequired} off, the row landed in business-service's table, pharma read its own
     * table, found nothing and defaulted to ON. auth-service now resolves capabilities once at mint and every
     * service reads that same answer — with no remote call on any hot path, which V44 settled as a hard
     * requirement when it refused a cross-service check on the sale path.
     *
     * <h3>Only for the CALLER'S OWN tenant, and this guard is not optional</h3>
     * The claim describes the tenant the token was minted for. Applying it to a question about a DIFFERENT org
     * would let one tenant's capabilities decide another's — a cross-tenant leak in the one component whose
     * job is to refuse things. Background scanners and storefront readers legitimately ask about an org they
     * are not "in"; those fall through to the local store, exactly as before.
     *
     * <h3>A null claim is not an empty one</h3>
     * null means unresolved (an older token, or auth could not read its store) and falls back. An empty set
     * means resolved-with-nothing-enabled and is authoritative. Merging the two would blank every screen for
     * every tenant still holding a pre-C3c token.
     */
    private boolean resolveEffective(Long organizationId, Capability capability) {
        java.util.Set<String> fromToken = CurrentUser.capabilities();
        if (fromToken != null
                && organizationId != null
                && organizationId.equals(CurrentUser.organizationId())) {
            return fromToken.contains(capability.code());
        }
        return resolve(organizationId, capability);
    }

    /**
     * C4 — the resolution order, in one place so the rendering side and the refusal side cannot disagree.
     *
     * <pre>
     *   1. explicit tenant override   what this tenant actually chose   ← WINS
     *   2. shape preset               what this KIND of business uses
     * </pre>
     *
     * <h3>Why the override has to be read RAW</h3>
     * {@code getBoolFor} folds the catalog default in, so it cannot tell "the owner switched this off" from
     * "the owner said nothing and the default is off". A preset must fill the second case and lose the first.
     * Reading the raw row is what separates them — and a saved value that merely equals the default still
     * counts as a choice, because the owner said it.
     *
     * <p>Without that, picking a shape would silently wipe out deliberate settings and the only safe advice
     * would be "never change your profile", which is a trap rather than a setting.
     *
     * <h3>Why this deploy changes nothing</h3>
     * A tenant with no {@code org.shape} row resolves to {@link Shape#GENERAL}, whose preset is every
     * capability. So step 2 returns true for everything, which is exactly the behaviour before C4. A tenant
     * narrows only by explicitly choosing a shape.
     */
    private boolean resolve(Long organizationId, Capability capability) {
        /*
         * E1 — the CEILING, applied first and able only to SUBTRACT.
         *
         *     effective = NOT REVOKED (the platform withdrew it) AND ENABLED (what the owner chose)
         *
         * ⭐ It asks `revoked`, NOT "is this in the plan". That distinction is the whole correction, and
         * getting it wrong is what broke capability-shapes.cy.js: every legacy tenant carries
         * `plan = FREE` from @Builder.Default — a value nothing had ever read for capability — so measuring a
         * read against the plan silently stripped ten capabilities from tenants that were trading fine.
         *
         * The rule that replaced it: only POSITIVE EVIDENCE of a decision may subtract. An operator's
         * SUSPENDED row, or a grant that has run out. Silence is not evidence. The commercial bound lives on
         * the WRITE path (EntitlementWriteGuard → grantable), where a refusal reaches a person who can be told
         * which plan they are on — rather than on the read path, where being wrong takes a shop's screens away
         * with no message at all.
         *
         * Placed HERE rather than at each enforcement point so the six `assertEnabled` guards, the
         * `[data-capability]` hiding and the JWT `caps` claim all inherit it with no edit. In every service
         * except auth this is the permissive default, and correctly so: those services read the ceiling's
         * RESULT from the token and are not the authority on the question.
         */
        if (entitlements.revoked(organizationId, capability)) return false;

        java.util.Optional<String> chosen = settings.overrideFor(organizationId, capability.settingKey());
        if (chosen.isPresent()) {
            return "true".equalsIgnoreCase(chosen.get().trim());
        }
        return shapeFor(organizationId).includes(capability);
    }

    /** The caller's tenant's shape. {@link Shape#GENERAL} when none has been chosen. */
    public Shape shape() {
        return shapeFor(CurrentUser.organizationId());
    }

    /**
     * A named tenant's shape, or {@link Shape#GENERAL}.
     *
     * <p>Reads the raw override for the same reason {@link #resolve} does: the catalog default IS
     * {@code general}, so folding it in would be harmless here — but going through one accessor keeps the
     * two paths from drifting the day the default changes.
     */
    public Shape shapeFor(Long organizationId) {
        return Shape.byCode(settings.overrideFor(organizationId, Shape.settingKey()).orElse(null));
    }

    /**
     * Refuse unless this capability is on. <b>The server-side half of the control.</b>
     *
     * <p>Fails CLOSED, unlike {@link #isEnabled}: a caller reaching this has decided the operation is one that
     * must not happen without the capability — recording a serial against stock, dispensing against a
     * prescription, posting a field collection. For those, "we could not tell" has to mean no.
     *
     * <p><b>Worded as a refusal of the ACTION, never as a report on the tenant's configuration.</b> A message
     * naming which capabilities an org has is an information disclosure to anyone probing endpoints; this says
     * what cannot be done and stops there. Same rule as the anti-IDOR reads, where "not yours" and "not there"
     * are deliberately indistinguishable.
     *
     * @throws com.myplus.common.web.exception.ValidationException when the capability is off
     */
    public void assertEnabled(Capability capability) {
        if (capability == null) return;
        Long org = CurrentUser.organizationId();
        // No tenant identity => cannot prove the capability => refuse. This is the CLOSED half.
        //
        // Goes through the SAME resolver the rendering side uses (C4). Two code paths reading the same
        // question two ways is how a screen ends up hidden while its endpoint still answers — the precise
        // defect this service exists to close. The difference between the halves is what they do when they
        // cannot tell, never how they work it out.
        boolean allowed = org != null && resolveEffective(org, capability);
        if (!allowed) {
            throw new com.myplus.common.web.exception.ValidationException(
                    "This is not switched on for your business.");
        }
    }

    /**
     * Every capability and whether it is on, for the caller's tenant — the payload a screen needs.
     *
     * <p>One call rather than one per capability: the dashboard decides visibility for ~31 sections at load,
     * and a round trip each would be its own performance problem. Keyed by short code, because that is what
     * {@code [data-capability]} carries in the markup.
     */
    public Map<String, Boolean> enabledMap() {
        return enabledMapFor(CurrentUser.organizationId());
    }

    /**
     * The same map for an explicitly named tenant.
     *
     * <p>C3c needs this because the most important caller has no security context at all: auth-service resolves
     * a tenant's capabilities while MINTING its token, before there is a {@code CurrentUser} to read. Same
     * reason {@link #isEnabledFor} and {@code SettingsService.effectiveFor} take an org parameter.
     */
    public Map<String, Boolean> enabledMapFor(Long organizationId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Capability c : Capability.values()) {
            out.put(c.code(), isEnabledFor(organizationId, c));
        }
        return out;
    }

    /**
     * The enabled capabilities as the compact wire form the JWT claim and {@code X-Org-Caps} header carry.
     *
     * <p>Only the ENABLED codes travel, comma-separated, because that is the short list in every realistic
     * tenant and tokens are sent on every request.
     *
     * <p><b>{@link #NONE_SENTINEL} when a tenant has nothing enabled</b>, and that is not fussiness. An empty
     * string cannot survive the trip: HTTP stacks routinely drop a header with an empty value, so "resolved,
     * nothing enabled" would arrive indistinguishable from "never resolved" — and those two must mean opposite
     * things. Absent = fall back to the local store; present = authoritative.
     */
    public String encodeFor(Long organizationId) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : enabledMapFor(organizationId).entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                if (sb.length() > 0) sb.append(',');
                sb.append(e.getKey());
            }
        }
        return sb.length() == 0 ? NONE_SENTINEL : sb.toString();
    }

    /**
     * Marks "resolved: this tenant has no capabilities enabled", as distinct from an absent claim.
     *
     * <p>A single {@code -} rather than the empty string, so the value survives header transport. See
     * {@link #encodeFor}.
     */
    public static final String NONE_SENTINEL = "-";
}
