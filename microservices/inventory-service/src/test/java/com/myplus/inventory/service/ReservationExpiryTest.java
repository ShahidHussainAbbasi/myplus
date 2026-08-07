package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import com.myplus.common.settings.SettingsService;
import com.myplus.inventory.config.InventorySettingsCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OMS O5a — how long a stock hold lives.
 *
 * <p>Pure logic: mocked settings, no Spring, no database, no Docker — runs on every {@code mvn test}, which
 * matters here because the Testcontainers suites skip on the dev machine.
 *
 * <p>Every case is one where being wrong is silent: a hold that never expires is the OMS-6 leak itself; a hold
 * that expires too eagerly races its own sale; and a zero that means "immediately" rather than "never" would
 * release every hold on the platform the moment someone typed it.
 */
@ExtendWith(MockitoExtension.class)
class ReservationExpiryTest {

    private static final Long ORG = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 10, 0, 0);

    @Mock private SettingsService settingsService;

    private ReservationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ReservationPolicy();
        ReflectionTestUtils.setField(policy, "settingsService", settingsService);
        // Nothing configured: every read returns the fallback the caller passes.
        lenient().when(settingsService.getIntFor(eq(ORG), any(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private void set(int minutes) {
        when(settingsService.getIntFor(eq(ORG), eq(InventorySettingsCatalog.HOLD_MINUTES), anyInt()))
                .thenReturn(minutes);
    }

    // ── the default ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with nothing configured, a hold lasts the catalog default")
    void defaultHold() {
        assertThat(policy.holdMinutes(ORG)).isEqualTo(InventorySettingsCatalog.DEFAULT_HOLD_MINUTES);
        assertThat(policy.expiryFor(ORG, NOW))
                .isEqualTo(NOW.plusMinutes(InventorySettingsCatalog.DEFAULT_HOLD_MINUTES));
    }

    @Test
    @DisplayName("a store's own hold length is honoured")
    void perOrgHold() {
        set(5);
        assertThat(policy.expiryFor(ORG, NOW)).isEqualTo(NOW.plusMinutes(5));
    }

    // ── zero means NEVER ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0 means never expire — not 'expire immediately'")
    void zeroDisablesExpiry() {
        set(0);
        // Null is the honest representation: a far-future date would be a lie the sweeper's query could not
        // tell apart from a real deadline. And if 0 meant "now", typing it would release every hold at once.
        assertThat(policy.expiryFor(ORG, NOW))
                .as("a merchant who would rather investigate a stuck hold than have it vanish sets 0")
                .isNull();
    }

    @Test
    @DisplayName("a null deadline is never expired")
    void nullDeadlineNeverExpires() {
        // Covers both the opt-out above and every row written before V6 — a migration must not release months
        // of historical holds as a side effect of deploying.
        assertThat(policy.isExpired(null, NOW)).isFalse();
    }

    // ── the boundary ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a hold exactly at its deadline is still live — a 30-minute hold lasts 30 minutes")
    void notExpiredAtTheBoundary() {
        LocalDateTime deadline = NOW.plusMinutes(30);
        assertThat(policy.isExpired(deadline, deadline))
                .as("strictly after, so the last second of the hold is honoured")
                .isFalse();
    }

    @Test
    @DisplayName("one moment past the deadline is expired; one moment before is not")
    void expiredJustAfter() {
        LocalDateTime deadline = NOW.plusMinutes(30);
        assertThat(policy.isExpired(deadline, deadline.plusNanos(1_000))).isTrue();
        assertThat(policy.isExpired(deadline, deadline.minusSeconds(1))).isFalse();
    }

    // ── failing soft ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a negative setting falls back to the default rather than expiring holds on creation")
    void negativeFallsBack() {
        set(-10);
        // A negative TTL would stamp a deadline in the past, so every sale would race its own sweeper.
        assertThat(policy.holdMinutes(ORG)).isEqualTo(InventorySettingsCatalog.DEFAULT_HOLD_MINUTES);
        assertThat(policy.expiryFor(ORG, NOW)).isAfter(NOW);
    }

    @Test
    @DisplayName("with settings unwired entirely the policy still answers, and holds still expire")
    void unwiredSettingsStillExpires() {
        ReservationPolicy bare = new ReservationPolicy();   // no SettingsService injected
        assertThat(bare.holdMinutes(ORG)).isEqualTo(InventorySettingsCatalog.DEFAULT_HOLD_MINUTES);
        assertThat(bare.expiryFor(ORG, NOW)).isNotNull();
    }

    @Test
    @DisplayName("the deadline is read for the store being reserved for, by id")
    void policyIsReadForTheNamedStore() {
        set(5);
        policy.expiryFor(ORG, NOW);
        // The sweeper has NO security context, so an ambient CurrentUser lookup would resolve every tenant to
        // the platform default — the O3 storefront failure, repeated.
        org.mockito.Mockito.verify(settingsService)
                .getIntFor(eq(ORG), eq(InventorySettingsCatalog.HOLD_MINUTES), anyInt());
        org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never()).getInt(any(), anyInt());
    }

    @Test
    @DisplayName("two stores hold stock for different lengths of time")
    void twoStoresHoldIndependently() {
        Long other = 8L;
        when(settingsService.getIntFor(eq(ORG), eq(InventorySettingsCatalog.HOLD_MINUTES), anyInt())).thenReturn(5);
        when(settingsService.getIntFor(eq(other), eq(InventorySettingsCatalog.HOLD_MINUTES), anyInt())).thenReturn(120);

        assertThat(policy.expiryFor(ORG, NOW)).isEqualTo(NOW.plusMinutes(5));
        assertThat(policy.expiryFor(other, NOW)).isEqualTo(NOW.plusMinutes(120));
    }

    @Test
    @DisplayName("a null start time does not break the stamp")
    void nullStartIsSafe() {
        set(30);
        assertThat(policy.expiryFor(ORG, null)).isNotNull();
    }
}
