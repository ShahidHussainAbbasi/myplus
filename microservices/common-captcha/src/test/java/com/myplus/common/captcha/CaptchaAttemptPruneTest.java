package com.myplus.common.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The failed-attempt map must not grow without a ceiling — and pruning it must never forgive a live block.
 *
 * <h3>The leak</h3>
 * Entries were removed only when a key was READ again: on {@code succeeded}, or on a later {@code isBlocked}
 * once its window had passed. A key that fails once and is never retried is never read again, so it stayed
 * for the life of the process. Under credential stuffing — thousands of distinct usernames, one failure
 * each, none revisited — the map grows without limit on the login path.
 *
 * <h3>Why the fix is pruning and not eviction</h3>
 * This is a security counter, not a cache. For a cache a miss is merely slow; here, dropping a LIVE entry
 * forgives failed attempts, and an attacker able to grow the map could evict their own block and continue.
 * So the size threshold decides only <em>when</em> to sweep; what is swept is decided purely by the clock.
 *
 * <p>That is the property this file exists to pin, and it is the one a well-meaning "bound the map with an
 * LRU" change would break while looking like an improvement.
 */
class CaptchaAttemptPruneTest {

    private static final long WINDOW_MS = Duration.ofHours(4).toMillis();

    /** The internal map — the only way to observe residency, which is the whole subject here. */
    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, long[]> mapOf(CaptchaAttemptService svc) throws Exception {
        Field f = CaptchaAttemptService.class.getDeclaredField("attempts");
        f.setAccessible(true);
        return (ConcurrentMap<String, long[]>) f.get(svc);
    }

    /** Backdate an entry so its window has already passed, without waiting four hours. */
    private static void expire(ConcurrentMap<String, long[]> map, String key) {
        long[] v = map.get(key);
        v[1] = System.currentTimeMillis() - WINDOW_MS - 1_000;
    }

    // ── the property that must not break ────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — a LIVE block survives a prune, however full the map is")
    void a_live_block_is_never_pruned() throws Exception {
        CaptchaAttemptService svc = new CaptchaAttemptService();

        // Somebody who has genuinely earned a block.
        for (int i = 0; i < CaptchaAttemptService.MAX_ATTEMPT; i++) svc.failed("attacker");
        assertThat(svc.isBlocked("attacker")).isTrue();

        // Now flood past the prune threshold with unrelated keys, and expire them so the sweep has work.
        ConcurrentMap<String, long[]> map = mapOf(svc);
        for (int i = 0; i < 10_050; i++) {
            svc.failed("noise-" + i);
            expire(map, "noise-" + i);
        }
        svc.failed("one-more-to-trigger-the-sweep");

        assertThat(svc.isBlocked("attacker"))
                .as("pruning must never hand a blocked client a fresh start")
                .isTrue();
    }

    @Test
    @DisplayName("…and the sweep actually happened — expired entries are gone")
    void expired_entries_are_removed() throws Exception {
        /*
         * POSITIVE CONTROL for the case above. If nothing were ever pruned, "the live block survived" would
         * be trivially true and this file would prove nothing at all.
         */
        CaptchaAttemptService svc = new CaptchaAttemptService();
        ConcurrentMap<String, long[]> map = mapOf(svc);

        // 30,000 keys that each fail once and are never retried — the credential-stuffing shape. Every one
        // is backdated past its window as it is created, so all of them are sweepable.
        final int pushed = 30_000;
        for (int i = 0; i < pushed; i++) {
            svc.failed("stale-" + i);
            expire(map, "stale-" + i);
        }

        /*
         * The sweep fires DURING the loop, not after it — pruneExpired runs on every failure once the map is
         * over the threshold. A first draft of this case measured the size afterwards and asserted it had
         * exceeded 10,000; it never does, because the map is swept before it gets the chance. That is the
         * feature working, and the assertion was simply describing the wrong mechanism.
         *
         * What matters is the ceiling: 30,000 distinct failures must not leave 30,000 entries resident.
         */
        assertThat(map.size())
                .as("%d one-off failures must not all stay resident", pushed)
                .isLessThan(pushed / 2);
    }

    // ── nothing else may change ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("below the threshold nothing is swept — pruning is not free, so it is not constant")
    void small_maps_are_left_alone() throws Exception {
        CaptchaAttemptService svc = new CaptchaAttemptService();
        ConcurrentMap<String, long[]> map = mapOf(svc);

        svc.failed("a");
        expire(map, "a");
        svc.failed("b");

        assertThat(map).as("a sweep on every failure would walk the map on every login attempt")
                .containsKey("a");
    }

    @Test
    @DisplayName("the counting rules are unchanged")
    void blocking_behaviour_is_untouched() {
        CaptchaAttemptService svc = new CaptchaAttemptService();

        for (int i = 0; i < CaptchaAttemptService.MAX_ATTEMPT - 1; i++) svc.failed("user");
        assertThat(svc.isBlocked("user")).as("one short of the limit is not blocked").isFalse();

        svc.failed("user");
        assertThat(svc.isBlocked("user")).as("at the limit, blocked").isTrue();

        svc.succeeded("user");
        assertThat(svc.isBlocked("user")).as("a success clears it").isFalse();
    }

    @Test
    @DisplayName("an expired block reads as unblocked, as it always did")
    void an_expired_block_lapses() throws Exception {
        CaptchaAttemptService svc = new CaptchaAttemptService();
        for (int i = 0; i < CaptchaAttemptService.MAX_ATTEMPT; i++) svc.failed("lapsed");
        assertThat(svc.isBlocked("lapsed")).isTrue();

        expire(mapOf(svc), "lapsed");

        assertThat(svc.isBlocked("lapsed"))
                .as("the block lapses by the clock — which is why sweeping it changes nothing observable")
                .isFalse();
    }
}
