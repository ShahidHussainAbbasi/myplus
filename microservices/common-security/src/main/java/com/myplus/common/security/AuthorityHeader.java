package com.myplus.common.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The ONE parser for the identity headers the gateway stamps ({@code X-User-Roles},
 * {@code X-User-Privileges}).
 *
 * <h3>Why this class exists — a security control failed open because there were two parsers</h3>
 *
 * The gateway builds those headers straight from the JWT claim:
 *
 * <pre>
 *   Object rolesObj = claims.get("roles");          // a java.util.List
 *   String roles = rolesObj.toString();             // "[ROLE_GUARDIAN]"  ← BRACKETS
 *   .header("X-User-Roles", roles)
 * </pre>
 *
 * So the value on the wire is {@code [ROLE_GUARDIAN]}, or {@code [ROLE_OWNER, ROLE_EDUCATION_USER]} for
 * several — <b>not</b> the bare comma-separated list the name suggests.
 *
 * <p>{@link HeaderAuthFilter} always knew that and stripped the brackets. {@link PortalScopeFilter}, added
 * later by slice 3.1b, did its own {@code split(",")} without stripping — so it compared
 * {@code "[ROLE_GUARDIAN]"} against {@code "ROLE_GUARDIAN"}, never matched, and <b>waved every portal
 * session through to the staff read surface.</b> Its twelve pure unit tests all passed, because they fed it
 * the clean format the design assumed rather than the bracketed one the gateway actually sends.
 *
 * <p><b>The rule this earns: a wire format gets exactly one parser.</b> Two readers of one header is two
 * chances to disagree, and the one that disagrees in the deny direction fails open silently. Anything that
 * needs to read these headers calls {@link #tokens(String)} — it does not split the string itself.
 *
 * <p>Tolerant on input by design: it accepts the bracketed form, the bare comma-separated form (which the
 * monolith's legacy direct-call path stamps from {@code auth.getAuthorities()}), quoted values, and any
 * spacing. Both forms reach services in production, which is precisely why the deny rule worked in one mode
 * and not the other.
 */
public final class AuthorityHeader {

    private AuthorityHeader() {
    }

    /**
     * PURE. The individual authority names in a stamped header, in order, with no empties.
     *
     * <p>Returns an empty list for null/blank input — an absent header is "no authorities", never a wildcard.
     */
    public static List<String> tokens(String header) {
        if (header == null || header.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        // Strip the artefacts of List.toString() and of any JSON-quoted rendering, then split.
        for (String s : Arrays.asList(header.replaceAll("[\\[\\]\"]", "").split(","))) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * PURE. Does this header carry {@code wanted}, as a whole token?
     *
     * <p>Exact, case-sensitive match on a parsed token — deliberately not {@code contains()} on the raw
     * string, which would also match a near-miss like {@code ROLE_GUARDIAN_ADMIN} and hand a different role
     * another one's treatment.
     */
    public static boolean has(String header, String wanted) {
        return wanted != null && tokens(header).contains(wanted);
    }
}
