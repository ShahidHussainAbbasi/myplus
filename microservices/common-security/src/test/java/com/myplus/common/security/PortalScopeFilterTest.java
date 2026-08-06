package com.myplus.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    // ── who is a portal principal ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no roles header, or a staff one, is not a portal principal — staff behaviour is untouched")
    void staff_is_not_portal() {
        assertFalse(PortalScopeFilter.isPortalPrincipal(null));
        assertFalse(PortalScopeFilter.isPortalPrincipal(""));
        assertFalse(PortalScopeFilter.isPortalPrincipal("ROLE_OWNER,WRITE_PRIVILEGE,LOGIN_PRIVILEGE"));
    }

    @Test
    @DisplayName("the portal role is recognised among other roles, with or without spaces")
    void portal_role_is_recognised() {
        assertTrue(PortalScopeFilter.isPortalPrincipal("ROLE_PORTAL"));
        assertTrue(PortalScopeFilter.isPortalPrincipal("LOGIN_PRIVILEGE,ROLE_PORTAL"));
        assertTrue(PortalScopeFilter.isPortalPrincipal("LOGIN_PRIVILEGE, ROLE_PORTAL ,GUARDIAN"));
    }

    @Test
    @DisplayName("matching is an exact token, not a substring — ROLE_PORTAL_ADMIN is a DIFFERENT role and "
            + "must not inherit the portal's restrictions by accident")
    void near_miss_role_names_do_not_match() {
        assertFalse(PortalScopeFilter.isPortalPrincipal("ROLE_PORTAL_ADMIN"));
        assertFalse(PortalScopeFilter.isPortalPrincipal("NOT_ROLE_PORTAL"));
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
