package com.myplus.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The caller identity propagated by the gateway (via X-User-* / X-Org-Id / X-Location-* headers) and
 * rebuilt by {@link HeaderAuthFilter}. Shared across all servlet services so the type is identical
 * everywhere a controller reads {@code authentication.getPrincipal()}.
 */
@Data
@AllArgsConstructor
public class AuthenticatedUser {
    private Long userId;
    private String email;

    /**
     * The person's NAME, for documents that a customer reads.
     *
     * <h3>Why the email was not good enough</h3>
     * {@code CustomerHistory.bookedByName} — a column with "name" in its own title — was stamped with
     * {@code user.getEmail()}, so every sales receipt printed an address where the counter hand's name
     * belongs. A 29-character gmail address on an 80mm slip is not a signature.
     *
     * <h3>Why it travels in the token rather than being looked up</h3>
     * The name lives in auth-service, and the sale path may not make a remote call to render a document —
     * the standing rule that V44 states for the serial check and the sale path follows everywhere else.
     * So it rides the JWT beside {@code activeOrgId} and {@code caps}, costs nothing at the till, and is
     * stamped onto the sale AT WRITE (never resolved at print, so an old invoice keeps the name of whoever
     * actually made the sale).
     *
     * <p>Null for an identity the gateway did not stamp, and for any token minted before this shipped —
     * so every reader must fall back to the email rather than print a blank.
     */
    private String displayName;

    private List<SimpleGrantedAuthority> authorities;
    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). May be null. */
    private Long organizationId;

    // ── Multi-location (Stores/Branches). All null/empty until locations + grants exist (P2+), in which
    //    case a service treats the request as single-location and behaves exactly as before. ──────────
    /** The active store/branch for this request (gateway X-Location-Id). Null = single-location/unset. */
    private Long activeLocationId;
    /** Every store/branch this caller may access in the active org (gateway X-Location-Ids). Empty = unset. */
    private Set<Long> accessibleLocationIds;
    /** The caller's role at the active location: OWNER | ADMIN | USER (gateway X-Loc-Role). May be null. */
    private String roleAtLocation;

    /**
     * C3c — the tenant's enabled capabilities, resolved by auth-service when the token was minted
     * (gateway {@code X-Org-Caps}).
     *
     * <h3>null and empty mean OPPOSITE things, deliberately</h3>
     * <ul>
     *   <li><b>{@code null}</b> — not resolved. The token predates C3c, or auth could not read the settings
     *       store. Callers must fall back to their own settings store, which is the behaviour before this
     *       existed. Failing open here is why a settings hiccup cannot take a tenant's screens away.</li>
     *   <li><b>empty set</b> — resolved, and this tenant has nothing enabled. Authoritative.</li>
     * </ul>
     * Collapsing the two would either hide every screen for tenants on older tokens, or make an all-off
     * tenant silently permissive. The {@code "-"} sentinel on the wire exists to keep them apart, because an
     * empty header value does not reliably survive transport.
     */
    private Set<String> capabilities;

    /**
     * E5 — the tenant this caller holds an OPEN SUPPORT SESSION over, or null for the overwhelming majority
     * of requests, which are a tenant acting on itself.
     *
     * <p>This is the only thing in the platform that widens a caller past their own organization. It is set
     * from a header the gateway strips and re-stamps from the token, never from anything a client can send.
     */
    private Long supportOrgId;

    /** When that session ends. Carried so a callee can answer "still valid?" without calling auth-service. */
    private java.time.LocalDateTime supportUntil;

    /** Whether the CUSTOMER has allowed this session to change their records (E5 ruling D-2). */
    private boolean supportWrite;

    /**
     * Legacy constructor (pre multi-location). Keeps existing call sites working; the location fields
     * default to unset, so behaviour is unchanged until the gateway stamps X-Location-* headers.
     */
    public AuthenticatedUser(Long userId, String email, List<SimpleGrantedAuthority> authorities, Long organizationId) {
        // capabilities = NULL, not an empty set: an identity built without the gateway's header has not
        // resolved them, and must fall back rather than assert that nothing is enabled. See the field javadoc.
        // supportOrgId = null / supportWrite = false: an identity the gateway did not stamp holds NO support
        // session. E5 widens a caller past their own org and that must never be a default (see the field).
        // displayName = null: an identity built without the gateway's header has no name to offer, and a
        // caller must fall back to the email rather than print an empty signature line.
        this(userId, email, null, authorities, organizationId, null, Collections.emptySet(), null, null,
                null, null, false);
    }
}
