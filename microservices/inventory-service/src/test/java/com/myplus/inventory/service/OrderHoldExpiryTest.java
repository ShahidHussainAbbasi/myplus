package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import com.myplus.commerce.contracts.dto.StockReservationRequest.HoldKind;
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
 * O7 D1c — a confirmed order's hold and a till's hold are not the same promise.
 *
 * <h3>The defect this exists to prevent</h3>
 * The pre-D1c hold is 30 minutes, commented <i>"long enough for a slow checkout"</i>. A distributor confirms an
 * order this afternoon and the van goes out tomorrow morning. Under that TTL the hold is swept overnight —
 * silently, working exactly as designed — and the order reaches dispatch with nothing reserved.
 *
 * <p>The feature would look implemented, pass an end-to-end gate written the obvious way (confirm, then assert
 * the stock is held), and do nothing on any order that waited more than half an hour. <b>Nothing observable at
 * confirm time distinguishes the two</b>, which is why this is asserted here, on the deadline itself, rather
 * than in a Cypress spec that would have to wait out the clock to notice.
 */
@ExtendWith(MockitoExtension.class)
class OrderHoldExpiryTest {

    @Mock private SettingsService settingsService;

    private ReservationPolicy policy;

    private static final Long ORG = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 14, 0);

    @BeforeEach
    void setUp() {
        policy = new ReservationPolicy();
        ReflectionTestUtils.setField(policy, "settingsService", settingsService);
    }

    private void configured(String key, int value) {
        when(settingsService.getIntFor(eq(ORG), eq(key), anyInt())).thenReturn(value);
    }

    // ── the point of the slice ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("THE CASE — an ORDER hold outlives a CHECKOUT hold by days, not minutes")
    void order_hold_far_outlives_a_checkout_hold() {
        configured(InventorySettingsCatalog.HOLD_MINUTES, 30);
        configured(InventorySettingsCatalog.ORDER_HOLD_MINUTES, 3 * 24 * 60);

        LocalDateTime checkout = policy.expiryFor(ORG, NOW, HoldKind.CHECKOUT);
        LocalDateTime order = policy.expiryFor(ORG, NOW, HoldKind.ORDER);

        assertThat(checkout).isEqualTo(NOW.plusMinutes(30));
        assertThat(order).isEqualTo(NOW.plusDays(3));

        // The assertion that actually matters: an order confirmed this afternoon must still be held when
        // tomorrow's van is loaded. A single shared TTL fails this, which is the whole reason for two.
        assertThat(order).isAfter(NOW.plusDays(1));
        assertThat(checkout).isBefore(NOW.plusDays(1));
    }

    @Test
    @DisplayName("POSITIVE CONTROL — the two are read from DIFFERENT settings, not one applied twice")
    void the_two_durations_are_independently_configurable() {
        /*
         * Without this, a bug that returned the checkout TTL for both would pass the case above whenever the
         * defaults happened to differ. Setting them to values that cannot be confused proves each kind reads
         * its own key.
         */
        configured(InventorySettingsCatalog.HOLD_MINUTES, 11);
        configured(InventorySettingsCatalog.ORDER_HOLD_MINUTES, 22);

        assertThat(policy.expiryFor(ORG, NOW, HoldKind.CHECKOUT)).isEqualTo(NOW.plusMinutes(11));
        assertThat(policy.expiryFor(ORG, NOW, HoldKind.ORDER)).isEqualTo(NOW.plusMinutes(22));
    }

    // ── the paths that must not change ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unqualified hold is still a CHECKOUT hold — every pre-D1c caller is untouched")
    void the_two_arg_overload_still_means_checkout() {
        // SagaSellService and every other existing caller uses the old signature. If that quietly started
        // taking order-length holds, an abandoned till would sterilise stock for three days.
        configured(InventorySettingsCatalog.HOLD_MINUTES, 30);

        assertThat(policy.expiryFor(ORG, NOW)).isEqualTo(NOW.plusMinutes(30));
    }

    @Test
    @DisplayName("zero still means never expires, for either kind")
    void zero_disables_expiry_for_both() {
        // The existing contract: 0 = "hold until someone investigates". D1c must not quietly re-enable a
        // deadline for a tenant that switched it off.
        configured(InventorySettingsCatalog.HOLD_MINUTES, 0);
        configured(InventorySettingsCatalog.ORDER_HOLD_MINUTES, 0);

        assertThat(policy.expiryFor(ORG, NOW, HoldKind.CHECKOUT)).isNull();
        assertThat(policy.expiryFor(ORG, NOW, HoldKind.ORDER)).isNull();
    }

    @Test
    @DisplayName("the ORDER default is days, so a tenant that configures nothing is still served")
    void order_default_is_days_not_minutes() {
        // The default is what almost every tenant runs on, so the default is what must be right. A defaulted
        // order hold measured in minutes is the silent failure this whole test class exists to prevent.
        assertThat(InventorySettingsCatalog.DEFAULT_ORDER_HOLD_MINUTES)
                .as("a confirmed order must survive until at least the next working day")
                .isGreaterThanOrEqualTo(24 * 60);
        assertThat(InventorySettingsCatalog.DEFAULT_ORDER_HOLD_MINUTES)
                .as("but not so long that a forgotten order sterilises stock for a week")
                .isLessThanOrEqualTo(7 * 24 * 60);
    }
}
