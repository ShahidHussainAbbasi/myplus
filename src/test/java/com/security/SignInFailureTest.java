package com.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-10-14 — sign-in failures are CODES mapped to message KEYS; nothing is matched on, or shown as, raw text. */
class SignInFailureTest {

    private static final String[] LOCALES = { "", "_ar", "_es", "_fr", "_hi", "_ur" };

    @Test
    @DisplayName("every failure's message key exists in all six bundles — a missing one would show the raw key")
    void everyKeyIsTranslated() throws Exception {
        for (String locale : LOCALES) {
            Properties bundle = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/messages" + locale + ".properties")) {
                bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (SignInFailure f : SignInFailure.values()) {
                String text = bundle.getProperty(f.messageKey());
                assertTrue(text != null && !text.isBlank(), "messages" + locale + " lacks " + f.messageKey());
            }
        }
    }

    @Test
    @DisplayName("auth-service's codes map by NAME; a missing or unknown code is a plain refusal, never an error")
    void refusalCodes() {
        assertEquals(SignInFailure.ACCESS_REMOVED, SignInFailure.fromRefusal("ACCESS_REMOVED"));
        assertEquals(SignInFailure.ACCOUNT_LOCKED, SignInFailure.fromRefusal("ACCOUNT_LOCKED"));
        assertEquals(SignInFailure.BAD_CREDENTIALS, SignInFailure.fromRefusal(null));
        assertEquals(SignInFailure.BAD_CREDENTIALS, SignInFailure.fromRefusal("A_CODE_FROM_A_NEWER_AUTH"));
    }

    @Test
    @DisplayName("⭐ CONTRACT: every code auth-service can send has a SignInFailure — else it reads 'Invalid Credentials'")
    void everyAuthCodeIsKnownHere() throws Exception {
        java.nio.file.Path auth = java.nio.file.Paths.get(
                "microservices/auth-service/src/main/java/com/myplus/auth/exception/SignInRefusal.java");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s{4}([A-Z][A-Z0-9_]+)\\(HttpStatus")
                .matcher(java.nio.file.Files.readString(auth));
        int codes = 0;
        while (m.find()) {
            codes++;
            String code = m.group(1);
            assertTrue(java.util.Arrays.stream(SignInFailure.values()).anyMatch(f -> f.name().equals(code)),
                    "auth-service sends " + code + " but SignInFailure has no such failure");
        }
        assertTrue(codes >= 6, "read " + codes + " codes from " + auth + " — the pattern no longer matches the file");
    }
}
