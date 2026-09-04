package com.myplus.common.settings;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myplus.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared settings engine. Aggregates every service's {@link SettingsCatalogProvider}s into one catalog,
 * and reads/writes per-tenant overrides through the service's {@link SettingsStore}. Behaviour code calls
 * {@link #getBool(String)} — override if set, else the catalog default. All scoped to the caller's org.
 *
 * This class is registered by {@code CommonSettingsAutoConfiguration} (via @Import), so a consuming service
 * only needs a dependency + one {@link SettingsStore} bean + its catalog provider(s).
 */
@Service
public class SettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    private final SettingsStore store;
    private final Map<String, SettingEntry> catalog = new LinkedHashMap<>();

    /**
     * PERF-C1 — one tenant's whole override map, cached: {@code org -> { key -> storedValue }}.
     *
     * <p><b>Why the map and not the key.</b> Every typed accessor funnels into
     * {@link #effectiveFor(Long, String)}, and that used to issue one {@code SELECT} PER KEY.
     * {@code SellController} alone reads seventeen, eight of them consecutively just to assemble a
     * receipt letterhead — eight queries to print one address block, on data a shop changes perhaps
     * monthly. {@link SettingsStore#findAll(Long)} already fetches a tenant's overrides in ONE query, so
     * caching at that grain turns seventeen queries into one on a miss and none on a hit.
     *
     * <p><b>Why not {@code @Cacheable}.</b> It would never fire. Spring's cache annotations are
     * proxy-based and a call arriving from inside the same bean bypasses the proxy — and every read here
     * is exactly that: {@code getBool → effective → effectiveFor}, three internal self-calls. The
     * annotation would be present, reviewed, and inert; the same shape as {@code @EnableWebMvc} silently
     * making {@code spring.web.resources.cache.period} do nothing. So the cache is held here, where
     * nothing sits between the caller and the answer.
     *
     * <p><b>Keyed by organisation, and that is the load-bearing part.</b> A cache keyed without the org
     * would serve one tenant's configuration to another: silent, absent from the logs, and invisible to
     * every existing test because they all run as a single org. That is the property the gate asserts,
     * rather than asserting that a second read was faster — which would pass on a leaking cache.
     */
    private final Cache<Long, Map<String, String>> overridesByOrg;

    /**
     * E1 — the write rules, in declaration order. Empty in every service that registers none.
     *
     * <p><b>Why {@code ObjectProvider} and not {@code List}.</b> Constructor injection of a {@code List<T>}
     * with no {@code T} beans is an UNSATISFIED dependency in Spring, not an empty list — so a plain
     * {@code List<SettingWriteGuard>} would stop business, education, welfare and agriculture from booting the
     * moment this parameter appeared, none of which owns an entitlement store. {@code ObjectProvider} is the
     * idiomatic "zero or more beans" injection and is <b>not</b> the {@code required = false} anti-pattern the
     * {@code JpaSettingsStore} javadoc warns about: that one hides a MISSING collaborator behind a silently
     * inert guard, whereas this one is a stream over however many rules a service chose to publish.
     *
     * <p>And the guard chain is not the only enforcement. If auth's entitlement guard were ever unwired, the
     * READ ceiling in {@link CapabilityService} still resolves the capability off, so the write would be
     * accepted and then have no effect — visible and wrong rather than invisible and wrong. The gate asserts
     * the refusal message, which is what fails loudly if this is missing.
     */
    private final org.springframework.beans.factory.ObjectProvider<SettingWriteGuard> guards;

    /**
     * E4 — the reactions to a write that has already been applied. Empty in every service that registers none.
     *
     * <p>{@code ObjectProvider} for the same Spring reason as {@code guards}: constructor injection of a
     * {@code List<T>} with no {@code T} beans is an UNSATISFIED dependency, not an empty list, so a plain
     * {@code List} here would stop business, education, welfare and agriculture booting the moment this
     * parameter appeared — none of them registers a listener.
     */
    private final org.springframework.beans.factory.ObjectProvider<SettingWriteListener> listeners;

    /**
     * Backstop only; correctness comes from {@link #set(String, String)} invalidating on write.
     *
     * <p>It exists for one reason: if a service ever runs more than one replica, an eviction on instance
     * A never reaches instance B. Each service runs as a single container today, so this is insurance
     * against a future deployment change, not a fix for a present bug. A TTL as the PRIMARY mechanism
     * would be a guess that is at once too slow for the owner who just changed a setting and watched the
     * screen not change, and too fast for the overwhelming majority of reads where nothing changed.
     */
    public SettingsService(SettingsStore store,
                           List<SettingsCatalogProvider> providers,
                           org.springframework.beans.factory.ObjectProvider<SettingWriteGuard> guards,
                           org.springframework.beans.factory.ObjectProvider<SettingWriteListener> listeners,
                           @Value("${app.settings.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.store = store;
        this.guards = guards;
        this.listeners = listeners;
        // Injected through the CONSTRUCTOR, not a @Value field. Field injection happens AFTER the
        // constructor runs, so a field here would still be 0 while the cache was being built — the knob
        // would appear in the config, be documented, and do nothing. A setting that cannot change
        // behaviour is worse than no setting, because it stops anyone looking further.
        this.overridesByOrg = Caffeine.newBuilder()
                // One small map per tenant. The bound is less about memory than about never letting a
                // long-lived process accumulate entries for tenants it has not served in hours.
                .maximumSize(1_000)
                // A NEGATIVE value is nonsense and falls back to the default; ZERO is not — it is an
                // operator saying "expire immediately", which is how you switch this off to diagnose
                // something without a redeploy. Folding 0 into the default would quietly ignore them.
                .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds < 0 ? 60 : cacheTtlSeconds))
                .build();
        if (providers != null)
            for (SettingsCatalogProvider p : providers)
                for (SettingEntry e : p.entries())
                    catalog.putIfAbsent(e.key(), e);   // first registration wins; keys are globally unique
    }

    /** Effective boolean for the caller's org (override else catalog default; false if key unknown). */
    public boolean getBool(String key) {
        return "true".equalsIgnoreCase(effective(key));
    }

    /**
     * Effective whole number for the caller's org (override else catalog default).
     *
     * <p>Returns {@code fallback} when the key is unknown OR the stored value is not a number. A malformed
     * override must not throw: these are read on behaviour paths (a promotion pass mark, an attendance
     * threshold), and a settings typo bringing down the operation that reads it would be a worse failure
     * than quietly using the caller's stated default. The caller passes the fallback so the choice is
     * visible at the call site rather than buried here.
     */
    public int getInt(String key, int fallback) {
        String v = effective(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Effective DECIMAL for the caller's org (override else catalog default) — money and rates.
     *
     * <p>Same fail-soft contract as {@link #getInt}: an unparseable override returns {@code fallback} rather
     * than throwing, because these are read on live paths (a delivery fee at checkout, a discount threshold on
     * a quote) and a settings typo must not take down the operation that reads it.
     *
     * <p>Exists because {@code INT} loses minor units and money is the common case. Added when the SECOND
     * consumer appeared: B2B-P4b was parsing {@code new BigDecimal(getText(...))} locally, and OMS O3 needed the
     * same for shipping fees — so the parse moved here instead of being copied, per §5c of the programme plan.
     * 4b was switched onto this in the same change.
     */
    public java.math.BigDecimal getDecimal(String key, java.math.BigDecimal fallback) {
        String v = effective(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Effective value of a SELECT setting, lower-cased and validated against a known set.
     *
     * <p>Returns {@code fallback} when the key is unknown, unset, or holds a value outside {@code allowed}.
     * That last case is the point: a policy setting like {@code pos.sale.marginPolicy} drives a branch, and
     * an unrecognised value must resolve to the caller's stated safe default rather than silently falling
     * through every branch to "do nothing". Standard C3 — a safety flag fails ON.
     *
     * <p>An extension of this port rather than a second mechanism, for the same reason {@code getInt} is:
     * SELECT already exists in {@link SettingEntry.SettingType} and {@code settings-form.js} already
     * renders it; only a typed reader was missing.
     *
     * @param allowed the valid values, lower-case; {@code fallback} must be one of them
     */
    public String getChoice(String key, java.util.Set<String> allowed, String fallback) {
        String v = effective(key);
        if (v == null || v.isBlank()) return fallback;
        String norm = v.trim().toLowerCase(java.util.Locale.ROOT);
        return (allowed != null && allowed.contains(norm)) ? norm : fallback;
    }

    /**
     * A TEXT setting's effective value, or {@code null} when it is unknown, unset or blank.
     *
     * <p>Blank collapses to null on purpose: these back optional printed fields (a licence number, a second
     * address line), and the caller's question is always "is there a value to print?". Returning {@code ""}
     * would make every call site write the same emptiness check, and one of them would eventually forget and
     * print an empty label with a colon after it.
     */
    public String getText(String key) {
        String v = effective(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** Effective raw value for the caller's org (override else catalog default; null if key unknown). */
    public String effective(String key) {
        return effectiveFor(CurrentUser.organizationId(), key);
    }

    /**
     * Effective raw value for an EXPLICITLY NAMED org.
     *
     * <p>Exists because not every reader of a tenant policy is an authenticated member of that tenant. The
     * public storefront is the case that forced it: a shopper has no JWT, so {@link CurrentUser#organizationId()}
     * is null on the whole checkout path, and every {@code effective(key)} there silently resolved to the
     * CATALOG DEFAULT — a shop's configured delivery fee would have applied to staff-placed orders and to
     * nobody else. Anywhere the org is known from the request itself (a store id in the URL, an
     * {@code organizationId} in the body) must pass it in rather than hope one is in the security context.
     *
     * <p>This is a read of tenant CONFIGURATION, not of tenant data — it grants no access to another org's
     * rows, so it is not an IDOR route. Callers still scope their own data reads normally.
     */
    public String effectiveFor(Long org, String key) {
        if (org != null) {
            // The null-org branch below is deliberately NOT cached: a caller without a tenant never
            // touches the store at all, so there is nothing to cache and nothing to get wrong.
            String v = overridesFor(org).get(key);
            if (v != null) return v;
        }
        SettingEntry e = catalog.get(key);
        return e == null ? null : e.defaultValue();
    }

    /**
     * The tenant's EXPLICIT override for a key, or empty when they have never saved one.
     *
     * <h3>Why this is different from {@link #effectiveFor} and why C4 needs it</h3>
     * {@code effectiveFor} folds the catalog default in, so it cannot tell "the owner chose false" from "the
     * owner chose nothing and the default is false". Shape presets make that distinction load-bearing: a
     * preset must fill in for a tenant who has expressed no opinion, and must lose to one who has.
     *
     * <p>Without it, picking a shape would silently overwrite deliberate choices, and the only safe advice
     * would be "never change your profile" — which is not a setting, it is a trap. {@code pos.entry.preset}
     * already ships this exact rule, including the subtlety that <b>a saved value merely equal to the default
     * still counts as a choice</b>: the owner said it, so it wins.
     *
     * <p>Costs nothing extra — it reads the same cached override map every other accessor uses.
     */
    public java.util.Optional<String> overrideFor(Long org, String key) {
        if (org == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(overridesFor(org).get(key));
    }

    /**
     * This org's overrides, from cache or from one query.
     *
     * <p>Returns an EMPTY map for a tenant that has overridden nothing, and caches that too — otherwise
     * the commonest tenant of all, one running entirely on defaults, would be the only one that never
     * benefits and would re-query on every single read.
     */
    private Map<String, String> overridesFor(Long org) {
        return overridesByOrg.get(org, o -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (SettingsStore.Stored s : store.findAll(o)) m.put(s.key(), s.value());
            return m;
        });
    }

    /** Drop one tenant's cached overrides. Package-private so a test can prove the cache is real. */
    void invalidate(Long org) {
        if (org != null) overridesByOrg.invalidate(org);
    }

    /**
     * ONB-1 — evict a tenant's cached overrides after someone wrote {@code org_setting} rows directly.
     *
     * <h3>Why this escape hatch exists, and when NOT to use it</h3>
     * {@link #set(String, String)} is the one writer and invalidates its own cache, which is why the eviction
     * above is package-private. But two operations legitimately write rows for an org that is <b>not the
     * caller's own</b>, which {@code set} cannot express because it scopes to {@code CurrentUser}:
     * provisioning a tenant (writing its shape at creation) and an operator re-applying a shape to somebody
     * else's tenant.
     *
     * <p>Without this, those writes would sit behind a stale cache for up to the TTL — the operator changes a
     * business type, watches nothing happen, and reports it as broken.
     *
     * <p><b>Anything that writes settings for the CALLER's own org must still use {@code set}</b>, which
     * validates against the catalog. This method only drops a cache; it grants nothing and validates nothing.
     */
    public void evictOrganization(Long organizationId) {
        invalidate(organizationId);
    }

    /** {@link #getBool(String)} for an explicitly named org — see {@link #effectiveFor(Long, String)}. */
    public boolean getBoolFor(Long org, String key) {
        return "true".equalsIgnoreCase(effectiveFor(org, key));
    }

    /** {@link #getInt(String, int)} for an explicitly named org — see {@link #effectiveFor(Long, String)}. */
    public int getIntFor(Long org, String key, int fallback) {
        String v = effectiveFor(org, key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@link #getDecimal(String, java.math.BigDecimal)} for an explicitly named org — same fail-soft contract. */
    public java.math.BigDecimal getDecimalFor(Long org, String key, java.math.BigDecimal fallback) {
        String v = effectiveFor(org, key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return new java.math.BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The whole catalog with each entry's effective value + whether it is an org override — feeds the UI. */
    public List<Map<String, Object>> catalogForOrg() {
        Long org = CurrentUser.organizationId();
        // Same cached map the behaviour paths read. Querying separately here would let the settings
        // SCREEN and the code that obeys those settings disagree about what is configured.
        Map<String, String> overrides = (org == null) ? Map.of() : overridesFor(org);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SettingEntry e : catalog.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.key());
            m.put("label", e.label());
            m.put("help", e.help());
            m.put("type", e.type().name());
            m.put("group", e.group());
            m.put("value", overrides.getOrDefault(e.key(), e.defaultValue()));
            m.put("isDefault", !overrides.containsKey(e.key()));
            // The catalog IS the source of truth for what an owner may set, so the screen must receive the
            // choices — settings-form.js renders `(it.options || [])`, and without this every SELECT
            // rendered as an EMPTY dropdown: the policy existed, defaulted correctly and was enforced, but
            // could not be changed anywhere except the API. Emitted for all types (empty for non-SELECT),
            // because a client deciding by type is a second place for the contract to drift.
            m.put("options", e.options());
            // The declared default, distinct from `value` (the effective one). Without it a caller cannot
            // tell "warn because that is the default" from "warn because someone set it", and any test of
            // the default has to depend on no other actor having changed it.
            m.put("defaultValue", e.defaultValue());
            /*
             * E1 — would ENABLING this be refused? Rendered as a locked row rather than a control that fails
             * when the owner uses it.
             *
             * DERIVED BY ASKING THE GUARD CHAIN, never by a second rule that mirrors it. The lock the owner
             * sees and the refusal the server issues are then the same code, so they cannot drift — the
             * failure this codebase has already met as a hidden menu whose endpoint still answered.
             *
             * "true" is the probe because every guard here bounds the ENABLING direction: withdrawing a
             * capability must never freeze the switch that turns it off, or a tenant is left with a policy it
             * can neither use nor clear. A non-BOOL setting is probed the same way and simply is not refused.
             */
            String lockedReason = refusalFor(org, e.key(), "true");
            m.put("locked", lockedReason != null);
            m.put("lockedReason", lockedReason);
            out.add(m);
        }
        return out;
    }

    /**
     * Run every registered {@link SettingWriteGuard}. Throws the first refusal, unchanged.
     *
     * <p>The exception is relayed rather than wrapped so the guard's own owner-facing sentence reaches the
     * screen verbatim — standard 8d, the server's sentence wins.
     */
    private void runGuards(Long org, String key, String value) {
        guards.orderedStream().forEach(g -> g.check(org, key, value));
    }

    /**
     * E4 — tell every listener what was written. Never lets one of them undo it.
     *
     * <p>A listener runs inside the caller's transaction, so an exception escaping here would roll back the
     * very write it was reporting: a configuration change prevented by the machinery that exists to record it.
     * Auditing that can veto is worse than auditing that misses a row, so each is isolated and a failure is
     * logged rather than propagated — the audit producer's own outbox is what makes the row recoverable.
     */
    private void notifyListeners(Long org, String key, String before, String after) {
        listeners.orderedStream().forEach(l -> {
            try {
                l.applied(org, key, before, after);
            } catch (RuntimeException ex) {
                LOG.warn("settings write listener failed for {} on org {} — the write STANDS", key, org, ex);
            }
        });
    }

    /**
     * The reason this write would be refused, or null when it would be allowed.
     *
     * <p>Deliberately a "would this be allowed" question answered by RUNNING the rules, not by a parallel
     * predicate each guard would also have to implement and keep in step. Guards are pure checks over cached
     * state, so asking is cheap; the alternative is a second source of truth for every rule ever added.
     */
    private String refusalFor(Long org, String key, String value) {
        try {
            runGuards(org, key, value);
            return null;
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        } catch (RuntimeException unavailable) {
            // A guard that could not answer must not cost the owner the whole Configuration screen. The WRITE
            // path still refuses if it genuinely cannot tell — this one only decides how a row is painted.
            return null;
        }
    }

    /** Upsert an override for the caller's org. Rejects keys not in the catalog (no free-form settings). */
    public void set(String key, String value) {
        if (!catalog.containsKey(key))
            throw new IllegalArgumentException("Unknown setting: " + key);
        Long org = CurrentUser.organizationId();
        // E1 — every registered rule runs BEFORE the upsert, so a refusal cannot leave a half-applied state.
        // Inside the caller's transaction on purpose: the throw rolls back anything the caller had already
        // written in the same unit of work, rather than committing half a configuration change.
        runGuards(org, key, value);
        /*
         * E4 — read the PREVIOUS override before the upsert replaces it.
         *
         * Raw via overrideFor, never getBoolFor: the typed accessor folds the catalog default in and cannot
         * tell "the owner switched it off" from "the owner said nothing" — the same distinction
         * CapabilityService.resolve depends on. An audit event built from the folded value would report a
         * change from the default every time, including when there was none.
         */
        String before = overrideFor(org, key).orElse(null);
        try {
            store.upsert(org, CurrentUser.userId(), key, value);
        } finally {
            /*
             * INVALIDATE IN A finally, and AFTER the write.
             *
             * After, because evicting first would leave a window in which a concurrent reader repopulates
             * the cache from the pre-write row and then caches that stale answer indefinitely — the owner
             * saves a setting, the screen goes on showing the old one, and nothing looks broken.
             *
             * In a finally, because a write that throws may still have reached the database: the upsert
             * is inside the caller's transaction, and a later rollback is exactly the case where a cached
             * map would no longer match the row. Dropping it costs one query; keeping a wrong one costs
             * a support call nobody can reproduce.
             *
             * This eviction is EXACT rather than a TTL guess because settings have precisely one writer,
             * which is this method. Same rule the codebase already follows elsewhere: stamp at write,
             * do not derive on read.
             */
            invalidate(org);
        }
        /*
         * AFTER the write and AFTER the eviction, so a listener that reads the setting back sees the new value
         * rather than a cached old one. Outside the try/finally deliberately: a write that threw did not
         * happen, and a listener must not be told about a change that was rolled back — the ordering that
         * keeps refusals out of the audit trail entirely.
         */
        notifyListeners(org, key, before, value);
    }
}
