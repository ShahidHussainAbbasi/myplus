package com.myplus.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Single, shared accessor for the caller identity that {@link HeaderAuthFilter} placed in the security
 * context (slice 33, Phase 4.5). Replaces the per-service {@code RequestUtil.getCurrentUser()} copies
 * (which had diverged 4×) with one source of truth every service can use for org-scoping.
 */
public final class CurrentUser {

    /** Request attribute {@link HeaderAuthFilter} mirrors the principal into; used as the fallback below. */
    public static final String REQUEST_ATTRIBUTE = "_authenticated_user";

    private CurrentUser() {}

    /** The authenticated caller, if the gateway propagated an identity for this request. */
    public static Optional<AuthenticatedUser> get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        // Fallback: HeaderAuthFilter also mirrors the principal into a request attribute, which survives
        // even if the security context was cleared mid-request. Preserves the old RequestUtil behaviour.
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra
                && sra.getRequest().getAttribute(REQUEST_ATTRIBUTE) instanceof AuthenticatedUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    /** Active tenant for this request (gateway X-Org-Id), or {@code null} when unauthenticated/no org. */
    public static Long organizationId() {
        return get().map(AuthenticatedUser::getOrganizationId).orElse(null);
    }

    /**
     * C3c — the tenant's enabled capabilities as resolved when the token was minted, or {@code null} when
     * they were not resolved for this request.
     *
     * <p><b>{@code null} and an empty set mean opposite things.</b> null = unresolved, so a caller must fall
     * back to its own settings store (pre-C3c behaviour). Empty = resolved and nothing is enabled. Anything
     * treating "no capabilities" as one case will either blank every screen for tenants on older tokens or
     * quietly permit everything for an all-off tenant.
     *
     * <p>Read {@code CapabilityService.isEnabledFor} rather than this directly: it applies the fallback and
     * the shape preset. This accessor exists for the resolver and for tests that need the raw answer.
     */
    public static java.util.Set<String> capabilities() {
        return get().map(AuthenticatedUser::getCapabilities).orElse(null);
    }

    /**
     * C6 — may the caller's tenant use this capability, judged from the token alone?
     *
     * <h3>When to use this instead of {@code CapabilityService}</h3>
     * {@code CapabilityService} is the real resolver: it consults the token AND falls back to the settings
     * store, and it applies the shape preset. Prefer it wherever it exists. But it lives in
     * {@code common-settings}, which is only on the classpath of services that own a {@code SettingsStore} —
     * catalog-service does not, and giving it a settings table purely to ask a question the token already
     * answers would be a schema added for nothing.
     *
     * <p>Since C3c the claim is authoritative for the caller's own tenant, so a service holding only
     * {@code common-security} can answer correctly from it.
     *
     * <h3>Permissive when it cannot tell, and that is a deliberate limit</h3>
     * Returns {@code true} when capabilities were never resolved ({@code null} — a token minted before C3c, or
     * auth unable to read its store). Refusing there would break tenants holding older tokens for a reason
     * they could neither see nor fix.
     *
     * <p>So this is <b>not</b> the equivalent of {@code assertEnabled}, which fails CLOSED and guards money.
     * It is for CONFIGURATION writes, where the cost of being wrong is a policy flag set by an admin that the
     * tills then decline to honour — visible, reversible, and self-correcting as soon as the token refreshes.
     * Do not reach for it to guard stock, ledger or tax.
     */
    public static boolean capabilityAllowed(String code) {
        if (code == null) return true;                 // nothing asked for, nothing to refuse
        java.util.Set<String> caps = capabilities();
        if (caps == null) return true;                 // unresolved — see the javadoc
        return caps.contains(code);
    }

    /**
     * Is the caller a MaxTheService platform operator?
     *
     * <h3>Keys on the {@code ROLE_ADMIN} role and never on {@code ADMIN_PRIVILEGE}</h3>
     * Every tenant owner holds {@code ADMIN_PRIVILEGE} inside their own organization, so keying on it would
     * hand every tenant the operator's cross-tenant reach — the same distinction {@code AuthService} and
     * {@code OrgAdminController} already draw, restated here because downstream services now need it too.
     */
    public static boolean isPlatformOperator() {
        return get().map(u -> u.getAuthorities() != null && u.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
                .orElse(false);
    }

    /**
     * ONB-3 — the organization a read or write should be scoped to, given one the caller ASKED for.
     *
     * <h3>A caller-supplied organization id is honoured only for a platform operator</h3>
     * Some endpoints legitimately answer about another tenant: an operator previewing what a business-type
     * change will cost is asking about somebody else's products and receivables on purpose. Every other
     * caller's request parameter is <b>ignored, not rejected</b> — it silently resolves to their own org, so a
     * tenant probing with {@code ?organizationId=13} learns nothing, not even whether 13 exists.
     *
     * <p><b>This is the whole anti-IDOR rule for those endpoints, in one place.</b> A service that writes the
     * {@code requested != null ? requested : own} ternary itself has written a cross-tenant read; the point of
     * naming it here is that the unsafe form stops being the shorter one to type.
     */
    public static Long organizationIdFor(Long requestedOrganizationId) {
        if (requestedOrganizationId != null && supportSessionCovers(requestedOrganizationId))
            return requestedOrganizationId;
        return organizationId();
    }

    /**
     * E5 — is there an OPEN support session for this tenant?
     *
     * <h3>What this replaced, and why the change is the whole slice</h3>
     * Until E5 the question above was {@code isPlatformOperator()}: a yes handed over any tenant, for any
     * reason, for ever. Nothing bounded it in time, nothing recorded why, and the customer could not see it.
     * A session answers all three, and this method is where the standing grant stops being the answer.
     *
     * <p>{@code isPlatformOperator()} is deliberately still here and still used — it remains the right
     * question for the operator's own console endpoints (the tenant list, entitlements, plans). What changed
     * is only the question asked about <b>somebody else's data</b>.
     *
     * <h3>The expiry is checked here, not trusted</h3>
     * The scope arrives in a claim, so a token minted before a session ended still carries it. Checking the
     * clock at the point of use is what keeps "time-boxed" true rather than decorative.
     */
    public static boolean supportSessionCovers(Long organizationId) {
        if (organizationId == null) return false;
        return get().map(u -> organizationId.equals(u.getSupportOrgId())
                        && u.getSupportUntil() != null
                        && u.getSupportUntil().isAfter(java.time.LocalDateTime.now()))
                .orElse(false);
    }

    /**
     * E5 — may this caller CHANGE that tenant's records?
     *
     * <p>An open session is not enough: the customer has to have allowed it (ruling D-2). Reading a shop's
     * figures to answer their question and altering their records are different asks, and only the second is
     * irreversible from where they are standing.
     */
    public static boolean supportSessionMayWrite(Long organizationId) {
        return supportSessionCovers(organizationId)
                && get().map(AuthenticatedUser::isSupportWrite).orElse(false);
    }

    /**
     * The user id to pass alongside {@link #organizationIdFor} for the NULL-org fallback clause.
     *
     * <h3>Null when the caller is reading somebody ELSE'S tenant, and that is the point</h3>
     * Scoped queries read {@code (organizationId = :orgId OR (organizationId IS NULL AND userId = :userId))} —
     * the second clause exists so a tenant's own pre-tenancy rows stay visible to them. Handing an operator's
     * own user id to a query about tenant 44 would fold the OPERATOR's orphan rows into that tenant's answer.
     * {@code null} never matches, which is exactly the intent: another tenant's legacy rows are not the
     * operator's to see, and the operator's are not that tenant's.
     */
    public static Long scopeUserIdFor(Long organizationIdBeingRead) {
        Long own = organizationId();
        return (own != null && own.equals(organizationIdBeingRead)) ? userId() : null;
    }

    /** Caller user id (audit + NULL-fallback scoping), or {@code null} when unauthenticated. */
    public static Long userId() {
        return get().map(AuthenticatedUser::getUserId).orElse(null);
    }

    /**
     * Caller email, or {@code null} when unauthenticated.
     *
     * <p>For <b>stamping an audit record at write time</b>, which is the only reason a service should want a
     * name rather than an id. An audit trail must still be readable after the person has left and their user
     * row is gone, so the name is written with the record instead of resolved when it is read — the rule
     * {@code CustomerHistory.bookedByName} already follows.
     */
    public static String email() {
        return get().map(AuthenticatedUser::getEmail).orElse(null);
    }

    /** The caller, or fail fast — for write paths that must be attributable to a tenant/user. */
    public static AuthenticatedUser require() {
        return get().orElseThrow(() -> new IllegalStateException("No authenticated user in security context"));
    }
}
