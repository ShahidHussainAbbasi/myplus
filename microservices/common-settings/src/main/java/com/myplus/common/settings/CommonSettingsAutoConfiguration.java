package com.myplus.common.settings;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Wires the shared settings engine + API into any servlet service that (a) has this module on its classpath
 * and (b) supplies a {@link SettingsStore} bean (its own table-backed impl). Without a store, nothing is
 * registered — so the module is inert until a service opts in. Registered via
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports (not component-scanned),
 * so services need no @ComponentScan/@EntityScan change.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(SettingsStore.class)
/*
 * C3: the capability beans join the same @Import. They carry @Service/@RestController/@Component, but this
 * module is deliberately NOT component-scanned (consuming services scan only their own package), so an
 * annotation alone registered NOTHING — which is why C1's CapabilityService existed as a correct, tested,
 * unreachable bean.
 *
 * CapabilityCatalog is the one that bites hardest if forgotten, and it was: without it the capability keys
 * are absent from the catalog, so `SettingsService.set` refuses every one of them with "Unknown setting:
 * org.cap.*" — an owner cannot switch a capability off at all.
 *
 * That failure is INVISIBLE FROM THE READ PATH, which is why it survived a first pass. `isEnabledFor`
 * catches the lookup failure and fails OPEN, so /capabilities happily returned every capability as true and
 * the dashboard rendered correctly. Only a WRITE revealed it. A test that asserts "everything defaults ON"
 * therefore passes just as well when the catalog is missing entirely — see the gate, which now asserts the
 * keys are really in the catalog rather than inferring it from a default.
 *
 * NOTE: LocaleSettingsCatalog is in this package too and is likewise unregistered — deliberately left alone
 * here, since importing it would add language settings to every consuming service's Configuration screen,
 * which is a visible change nobody asked for. Flagged, not silently changed.
 */
@Import({ SettingsService.class, SettingsController.class,
          CapabilityService.class, CapabilityController.class })
public class CommonSettingsAutoConfiguration {

    /**
     * E1 — the entitlement ceiling's default, so {@link CapabilityService}'s injection can stay REQUIRED.
     *
     * <h3>Why a published default rather than an optional injection</h3>
     * Only auth-service owns an entitlement store; every other service reads the ceiling's result from the JWT
     * {@code caps} claim. The obvious shortcut is {@code @Autowired(required = false)} and a null check — and
     * that is exactly the shape {@code JpaSettingsStore}'s javadoc records as a defect: OMS O3 shipped a
     * resolver with optional injection and no store, and it silently did nothing for a whole slice. <b>A guard
     * that disables itself when a bean is missing is worse than no guard, because it reads as protection.</b>
     * With a real bean here the injection is required, a wiring mistake fails at startup, and the fallback
     * behaviour is a deliberate declaration rather than an accident of the classpath.
     *
     * <p>{@code @ConditionalOnMissingBean} so auth-service's {@code JpaEntitlementSource} simply replaces it.
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(EntitlementSource.class)
    public EntitlementSource permissiveEntitlementSource() {
        return EntitlementSource.PERMISSIVE;
    }

    /**
     * C3c — the capability CATALOG is published by exactly one service: whoever owns the store of record.
     *
     * <h3>Why it cannot be registered everywhere any more</h3>
     * The catalog is what {@code SettingsService.set} validates against, so registering it in a service makes
     * that service accept capability WRITES into its own {@code org_setting} table. That is precisely the
     * defect C3c exists to remove: an owner switched {@code rxRequired} off, the row landed in
     * business-service's table, pharma read its own, found nothing and never refused.
     *
     * <p>With this conditional, only auth-service accepts those writes; everywhere else
     * {@code set("org.cap.…")} is refused as an unknown setting, which is the correct answer — that service
     * is not the owner. Reads are unaffected: the resolver works from the JWT claim, and its fallback reads
     * raw overrides and the shape preset, neither of which needs a catalog entry.
     *
     * <p>Enabled with {@code app.capabilities.owner=true}, set in auth-service alone. A property rather than a
     * marker bean so the ownership is visible in configuration rather than buried in a classpath accident —
     * this module has already been bitten twice by beans that silently were, or were not, registered.
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.capabilities.owner", havingValue = "true")
    @Import(CapabilityCatalog.class)
    static class CapabilityOwnerConfiguration {
    }
}
