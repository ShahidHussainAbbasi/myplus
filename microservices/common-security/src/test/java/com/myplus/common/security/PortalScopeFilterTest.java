package com.myplus.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 3.1b — the deny rule, tested with no Spring, no servlet container and no DB.
 *
 * <p>This is the highest-consequence pure logic in the education programme: if {@code allowed()} returns
 * true when it should not, a guardian reads the whole school's records. Every case below is therefore
 * written as "what must a portal session NOT reach".
 */
class PortalScopeFilterTest {

    private static final List<String> EDU = List.of("/portal/**");
    private static final List<String> CONFINED = List.of("ROLE_GUARDIAN");

    // ── what the REFUSAL actually looks like on the wire ────────────────────────────────────────────

    /**
     * The refusal must be a 404 the filter commits ITSELF, and the request must never reach the chain.
     *
     * <p>Written after the refusal came back as **403** in a live run. The filter used
     * {@code response.sendError(404)}, which asks the container to run its ERROR dispatch; that dispatch
     * re-enters the security chain for {@code /error} carrying no authentication (this filter short-circuits
     * *before* the chain), education's {@code .anyRequest().authenticated()} refuses it, and the caller sees
     * 403 — **the exact status D4 exists to prevent**, because it confirms the endpoint is real.
     *
     * <p>No pure test could have caught that: it is container behaviour. What IS pinnable, and is pinned
     * here, is the contract the fix has to keep — status 404, empty body, chain never invoked.
     */
    @Test
    @DisplayName("a confined, non-allowlisted request is refused 404 with no body, and never reaches the chain")
    void refusal_is_a_committed_404_and_the_chain_is_not_invoked() throws Exception {
        PortalScopeFilter filter = new PortalScopeFilter(EDU, CONFINED);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/getUserStudent");
        request.addHeader("X-User-Roles", "[ROLE_GUARDIAN]");        // the real gateway form
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(404, response.getStatus(), "refusals are 404 — never 403, see D4");
        assertEquals("", response.getContentAsString(), "and carry no body: a prober learns nothing");
        assertFalse(chainCalled.get(), "the request must not reach the application at all");
    }

    @Test
    @DisplayName("an allowlisted portal path is passed straight through, untouched")
    void allowlisted_path_reaches_the_chain() throws Exception {
        PortalScopeFilter filter = new PortalScopeFilter(EDU, CONFINED);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portal/children");
        request.addHeader("X-User-Roles", "[ROLE_GUARDIAN]");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get(), "the portal's own surface must still work");
        assertEquals(200, response.getStatus());
    }

    // ── THE WIRE FORMAT — the cases whose absence let this filter fail open ─────────────────────────

    /**
     * THE REGRESSION. Every other case in this class fed a format the gateway does not send.
     *
     * <p>`JwtAuthenticationFilter` stamps the header from the JWT claim with `List.toString()`, so the real
     * value is `[ROLE_GUARDIAN]` — brackets included. This filter split on "," without stripping them,
     * compared `"[ROLE_GUARDIAN]"` to `"ROLE_GUARDIAN"`, never matched, and let a portal session reach the
     * whole staff read surface. **Twelve pure tests were green throughout**, because each one was written
     * from the design's idea of the header rather than from a captured one.
     *
     * <p>Measured 2026-08-06 against a live token; kept as literals so the format is pinned, not assumed.
     */
    @Test
    @DisplayName("the BRACKETED form the gateway actually stamps is recognised — the fail-open regression")
    void gateway_wire_format_is_confined() {
        assertTrue(PortalScopeFilter.isConfined("[ROLE_GUARDIAN]", CONFINED), "single role, as List.toString()");
        assertTrue(PortalScopeFilter.isConfined("[ROLE_GUARDIAN, ROLE_OTHER]", CONFINED), "first of several");
        assertTrue(PortalScopeFilter.isConfined("[ROLE_OTHER, ROLE_GUARDIAN]", CONFINED), "last of several");
        assertTrue(PortalScopeFilter.isConfined("[\"ROLE_GUARDIAN\"]", CONFINED), "quoted rendering");
    }

    @Test
    @DisplayName("the BARE form the monolith's legacy direct path stamps still works — both are live in prod")
    void legacy_wire_format_is_confined() {
        // auth.getAuthorities() joined with "," — no brackets. The deny rule worked here and ONLY here,
        // which is why the defect presented as "the filter works sometimes".
        assertTrue(PortalScopeFilter.isConfined("LOGIN_PRIVILEGE,CHANGE_PASSWORD_PRIVILEGE,ROLE_GUARDIAN",
                CONFINED));
    }

    @Test
    @DisplayName("brackets do not turn a NEAR-MISS into a match either — stripping must not widen the rule")
    void wire_format_stripping_does_not_widen_the_match() {
        assertFalse(PortalScopeFilter.isConfined("[ROLE_GUARDIAN_ADMIN]", CONFINED));
        assertFalse(PortalScopeFilter.isConfined("[NOT_ROLE_GUARDIAN]", CONFINED));
        assertFalse(PortalScopeFilter.isConfined("[ROLE_OWNER, LOGIN_PRIVILEGE]", CONFINED));
    }

    // ── who is a portal principal ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no roles header, or a staff one, is not a portal principal — staff behaviour is untouched")
    void staff_is_not_portal() {
        assertFalse(PortalScopeFilter.isConfined(null, CONFINED));
        assertFalse(PortalScopeFilter.isConfined("", CONFINED));
        assertFalse(PortalScopeFilter.isConfined("ROLE_OWNER,WRITE_PRIVILEGE,LOGIN_PRIVILEGE", CONFINED));
    }

    @Test
    @DisplayName("the guardian role is recognised among other roles, with or without spaces")
    void portal_role_is_recognised() {
        assertTrue(PortalScopeFilter.isConfined("ROLE_GUARDIAN", CONFINED));
        assertTrue(PortalScopeFilter.isConfined("LOGIN_PRIVILEGE,ROLE_GUARDIAN", CONFINED));
        assertTrue(PortalScopeFilter.isConfined("LOGIN_PRIVILEGE, ROLE_GUARDIAN ,GUARDIAN", CONFINED));
    }

    @Test
    @DisplayName("matching is an exact token, not a substring — ROLE_GUARDIAN_ADMIN is a DIFFERENT role and "
            + "must not inherit the portal's restrictions by accident")
    void near_miss_role_names_do_not_match() {
        assertFalse(PortalScopeFilter.isConfined("ROLE_GUARDIAN_ADMIN", CONFINED));
        assertFalse(PortalScopeFilter.isConfined("NOT_ROLE_GUARDIAN", CONFINED));
    }

    @Test
    @DisplayName("an EMPTY confined set confines nobody — the correct default for the twelve services "
            + "that have no portal, and the reason they need no configuration at all")
    void empty_confined_set_confines_nobody() {
        assertFalse(PortalScopeFilter.isConfined("ROLE_GUARDIAN", List.of()));
        assertFalse(PortalScopeFilter.isConfined("ROLE_GUARDIAN", null));
    }

    @Test
    @DisplayName("the confined set is CONFIGURATION — 3.3 adds ROLE_STUDENT and the filter needs no change")
    void confined_set_is_extensible() {
        List<String> withStudent = List.of("ROLE_GUARDIAN", "ROLE_STUDENT");
        assertTrue(PortalScopeFilter.isConfined("ROLE_STUDENT", withStudent));
        assertTrue(PortalScopeFilter.isConfined("ROLE_GUARDIAN", withStudent));
        assertFalse(PortalScopeFilter.isConfined("ROLE_STUDENT", CONFINED), "not confined until it is listed");
    }

    // ── what a portal principal may reach ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the portal's own paths are allowed")
    void portal_paths_allowed() {
        assertTrue(PortalScopeFilter.allowed("/portal/children", EDU));
        assertTrue(PortalScopeFilter.allowed("/portal/results", EDU));
        assertTrue(PortalScopeFilter.allowed("/portal/homework", EDU));
    }

    @Test
    @DisplayName("THE CASE THIS FILTER EXISTS FOR: staff reads are refused. Without this, a guardian "
            + "session reads every student, mark and fee record in the school")
    void staff_reads_are_refused() {
        assertFalse(PortalScopeFilter.allowed("/getUserStudent", EDU));
        assertFalse(PortalScopeFilter.allowed("/getUserGuardian", EDU));
        assertFalse(PortalScopeFilter.allowed("/getMarksSheet", EDU));
        assertFalse(PortalScopeFilter.allowed("/getUserFc", EDU));
        assertFalse(PortalScopeFilter.allowed("/saveBehaviourNote", EDU));
    }

    @Test
    @DisplayName("an empty allowlist denies everything — a service that has never heard of the portal "
            + "fails CLOSED by doing nothing")
    void empty_allowlist_denies_everything() {
        assertFalse(PortalScopeFilter.allowed("/portal/children", List.of()));
        assertFalse(PortalScopeFilter.allowed("/anything", List.of()));
        assertFalse(PortalScopeFilter.allowed("/portal/children", null));
    }

    @Test
    @DisplayName("path traversal is normalised BEFORE matching — /portal/../getUserStudent is a staff read "
            + "wearing a portal prefix, and matching before cleaning is how an allowlist gets walked past")
    void traversal_is_refused() {
        assertFalse(PortalScopeFilter.allowed("/portal/../getUserStudent", EDU));
        assertFalse(PortalScopeFilter.allowed("/portal/./../../getUserStudent", EDU));
        assertFalse(PortalScopeFilter.allowed("/portal/sub/../../getUserFc", EDU));
    }

    @Test
    @DisplayName("a path that merely STARTS with the word portal is not inside it")
    void prefix_lookalikes_are_refused() {
        assertFalse(PortalScopeFilter.allowed("/portalX/children", EDU));
        assertFalse(PortalScopeFilter.allowed("/portal-admin/children", EDU));
    }

    @Test
    @DisplayName("a null path is refused rather than crashing the filter")
    void null_path_is_refused() {
        assertFalse(PortalScopeFilter.allowed(null, EDU));
    }

    // ── the property parser ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank or missing property parses to an EMPTY allowlist, which denies everything")
    void blank_property_parses_to_deny_all() {
        assertTrue(PortalScopeFilter.parseAllowlist(null).isEmpty());
        assertTrue(PortalScopeFilter.parseAllowlist("").isEmpty());
        assertTrue(PortalScopeFilter.parseAllowlist("   ").isEmpty());
    }

    @Test
    @DisplayName("multiple patterns parse and trim")
    void csv_parses() {
        List<String> parsed = PortalScopeFilter.parseAllowlist("/portal/**, /public/health ");
        assertEquals(List.of("/portal/**", "/public/health"), parsed);
        assertTrue(PortalScopeFilter.allowed("/public/health", parsed));
        assertFalse(PortalScopeFilter.allowed("/getUserStudent", parsed));
    }
}
