package com.security;

import java.util.regex.Pattern;

/**
 * Conservative, dependency-free input sanitizer (server-side defense-in-depth against stored XSS).
 *
 * The PRIMARY defense is output-encoding at render time (dashboard JS escapes user data via
 * escHtml before injecting it into the DOM). This sanitizer is the belt-and-suspenders layer: it
 * strips HTML/script constructs from incoming request values so payloads such as
 * {@code <script>alert(1)</script>} or {@code <img src=x onerror=alert(1)>} never persist.
 *
 * Tags are stripped (not HTML-encoded), so it does NOT double-encode with the output layer.
 * Mirrors {@code com.myplus.common.security.XssSanitizer} used by the microservices.
 */
public final class XssSanitizer {

    private XssSanitizer() {
    }

    private static final Pattern SCRIPT_BLOCK = Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    private static final Pattern SCRIPT_SCHEME = Pattern.compile("(?i)(javascript|vbscript|data)\\s*:");
    private static final Pattern EVENT_HANDLER = Pattern.compile("(?i)\\bon[a-z]+\\s*=");

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
