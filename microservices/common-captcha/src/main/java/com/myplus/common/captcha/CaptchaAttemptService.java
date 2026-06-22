package com.myplus.common.captcha;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-client throttle for failed captcha attempts (slice 33, Phase 9). Mirrors the monolith's
 * Guava-backed ReCaptchaAttemptService but with no external dependency: a client is blocked after
 * {@value #MAX_ATTEMPT} failures within a rolling {@code 4h} window. In-memory and per-instance, which
 * matches the original (defence-in-depth, not a hard security boundary).
 */
public class CaptchaAttemptService {

    static final int MAX_ATTEMPT = 4;
    private static final long WINDOW_MS = Duration.ofHours(4).toMillis();

    /** key -> [failureCount, windowStartEpochMillis] */
    private final ConcurrentMap<String, long[]> attempts = new ConcurrentHashMap<>();

    public void succeeded(final String key) {
        if (key != null) {
            attempts.remove(key);
        }
    }

    public void failed(final String key) {
        if (key == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        attempts.compute(key, (k, v) -> {
            if (v == null || now - v[1] > WINDOW_MS) {
                return new long[] { 1, now };
            }
            v[0]++;
            return v;
        });
    }

    public boolean isBlocked(final String key) {
        if (key == null) {
            return false;
        }
        final long[] v = attempts.get(key);
        if (v == null) {
            return false;
        }
        if (System.currentTimeMillis() - v[1] > WINDOW_MS) {
            attempts.remove(key);
            return false;
        }
        return v[0] >= MAX_ATTEMPT;
    }
}
