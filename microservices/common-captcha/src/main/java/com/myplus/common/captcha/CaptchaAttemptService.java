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

    /**
     * Prune once the map passes this many keys.
     *
     * <p>Not a cap on how many clients may be tracked — a threshold at which expired entries are swept.
     * Nothing is ever dropped because the map is "full"; see {@link #pruneExpired}. Matches the gateway's
     * {@code RateLimitGlobalFilter}, which solved the same problem the same way.
     */
    private static final int PRUNE_THRESHOLD = 10_000;

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
        pruneExpired(now);
    }

    /**
     * Drop entries whose 4-hour window has already passed, once the map is large enough to be worth it.
     *
     * <h3>The leak this closes</h3>
     * Entries were removed only when a key was READ again — on {@link #succeeded} or a later
     * {@link #isBlocked}. A key that fails once and is never retried was never read again, so it stayed for
     * the life of the process. Under credential stuffing that is thousands of distinct usernames, one
     * failure each, none of them ever revisited: the map grows without a ceiling on the login path.
     *
     * <h3>Why this prunes by EXPIRY and never by size</h3>
     * The obvious fix — bound the map and evict the least-recently-used — would be a security hole. This is
     * not a cache: for a cache a miss is merely slow, but dropping a live entry here FORGIVES FAILED LOGIN
     * ATTEMPTS, and an attacker who can grow the map can evict their own block and keep going. So the
     * threshold decides only WHEN to sweep; what gets swept is decided purely by the clock.
     *
     * <p>An expired entry carries no information — {@link #isBlocked} already treats it as unblocked, and
     * {@link #failed} already restarts the count past it. Removing it changes nothing a caller could
     * observe, which is exactly what makes it safe.
     */
    private void pruneExpired(final long now) {
        if (attempts.size() <= PRUNE_THRESHOLD) {
            return;
        }
        attempts.entrySet().removeIf(e -> now - e.getValue()[1] > WINDOW_MS);
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
