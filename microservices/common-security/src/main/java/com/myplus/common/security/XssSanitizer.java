package com.myplus.common.security;

import java.util.regex.Pattern;

/**
 * Conservative, dependency-free input sanitizer used as defense-in-depth against stored XSS.
 *
 * The PRIMARY defense is output-encoding at render time (the front-end escapes user data before
 * injecting it into the DOM). This sanitizer is the server-side belt-and-suspenders layer: it
 * strips HTML/script constructs from incoming request values so payloads such as
 * {@code <script>alert(1)</script>} or {@code <img src=x onerror=alert(1)>} never persist.
 *
 * It strips tags rather than HTML-encoding, so it does NOT double-encode with the output layer
 * (a name like "A & B" is left intact). Fields here are plain text (names, codes, descriptions),
 * so no HTML is ever legitimate.
 */
public final class XssSanitizer {

    private XssSanitizer() {
    }

    // <script ...>...</script> including its content (DOTALL + case-insensitive).
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Any remaining HTML/XML tag.
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    // Dangerous URI schemes.
    private static final Pattern SCRIPT_SCHEME = Pattern.compile("(?i)(javascript|vbscript|data)\\s*:");
    // Inline event handlers (onerror=, onclick=, ...).
    private static final Pattern EVENT_HANDLER = Pattern.compile("(?i)\\bon[a-z]+\\s*=");

    /**
     * Returns a sanitized copy of {@code value}: null/blank pass through unchanged; otherwise
     * script blocks, tags, dangerous schemes and inline handlers are removed.
     */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String v = value.replace("\0", "");
        v = SCRIPT_BLOCK.matcher(v).replaceAll("");
        v = HTML_TAG.matcher(v).replaceAll("");
        v = SCRIPT_SCHEME.matcher(v).replaceAll("");
        v = EVENT_HANDLER.matcher(v).replaceAll("");
        return v;
    }
}
