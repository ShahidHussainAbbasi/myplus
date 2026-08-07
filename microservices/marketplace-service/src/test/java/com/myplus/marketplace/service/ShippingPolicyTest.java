package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import com.myplus.common.settings.SettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.myplus.marketplace.config.MarketplaceSettingsCatalog.EXPRESS_FEE;
import static com.myplus.marketplace.config.MarketplaceSettingsCatalog.FREE_OVER;
import static com.myplus.marketplace.config.MarketplaceSettingsCatalog.STANDARD_FEE;

/**
 * OMS O3 — per-org delivery pricing.
 *
 * <p>Pure logic: mocked settings, no Spring, no database, no Docker — runs on every {@code mvn test}, which
 * matters because the Testcontainers suites skip on the dev machine.
 *
 * <p>The cases that matter are the silent ones: a shop whose configured fee is ignored, a free-delivery
 * threshold that is off by one at the boundary, and a settings typo taking down checkout.
 */
@ExtendWith(MockitoExtension.class)
class ShippingPolicyTest {

    /** The store whose policy is being priced. Every stub below is bound to it ON PURPOSE — see the last test. */
    private static final Long ORG = 7L;

    @Mock private SettingsService settingsService;

    private ShippingPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ShippingPolicy();
        ReflectionTestUtils.setField(policy, "settingsService", settingsService);
        // Default: nothing overridden — every read returns the fallback the caller passes.
        lenient().when(settingsService.getDecimalFor(eq(ORG), any(), any())).thenAnswer(inv -> inv.getArgument(2));
    }


    private void set(String key, String value) {
        when(settingsService.getDecimalFor(eq(ORG), eq(key), any())).thenAnswer(inv ->
                value == null ? inv.getArgument(2) : new BigDecimal(value));
    }

    // ── defaults reproduce the old hardcoded behaviour ─────────────────────────────────────────────────

    @Test
    @DisplayName("with nothing configured, the fees are exactly the literals that used to be in the enum")
    void defaultsMatchThePreO3Literals() {
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("100"), ORG))
                .isEqualByComparingTo("5.00");
        assertThat(policy.feeFor(ShippingOption.EXPRESS, new BigDecimal("100"), ORG))
                .isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("collection is never charged — and does not even read a fee setting")
    void pickupIsAlwaysFree() {
        // Deliberately no stub: PICKUP returns before any setting is consulted, and a strict-stub run proves it.
        // Collection costs nothing to deliver, so there is no configuration that could make it cost something.
        assertThat(policy.feeFor(ShippingOption.PICKUP, new BigDecimal("10"), ORG)).isEqualByComparingTo("0");
    }

    // ── the point of the slice: a store can set its own ────────────────────────────────────────────────

    @Test
    @DisplayName("a store's own fee is honoured")
    void perOrgFeeIsUsed() {
        set(STANDARD_FEE, "250");
        set(EXPRESS_FEE, "600");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("1000"), ORG)).isEqualByComparingTo("250");
        assertThat(policy.feeFor(ShippingOption.EXPRESS, new BigDecimal("1000"), ORG)).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("a fee with minor units survives — this is why MONEY is not INT")
    void decimalFeesArePreserved() {
        set(STANDARD_FEE, "5.50");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("100"), ORG)).isEqualByComparingTo("5.50");
    }

    // ── free-over threshold, including the boundary ────────────────────────────────────────────────────

    @Test
    @DisplayName("an order AT the free threshold ships free — 'free over 5000' includes 5000")
    void freeAtTheBoundary() {
        set(FREE_OVER, "5000");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("5000"), ORG)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an order above the threshold ships free; below it still pays")
    void freeAboveAndPaidBelow() {
        set(FREE_OVER, "5000");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("5000.01"), ORG)).isEqualByComparingTo("0");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("4999.99"), ORG)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("the threshold applies to EXPRESS too — free delivery means free, whichever method")
    void freeOverAppliesToEveryPaidMethod() {
        set(FREE_OVER, "1000");
        assertThat(policy.feeFor(ShippingOption.EXPRESS, new BigDecimal("2000"), ORG)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a zero threshold means OFF, not 'everything is free'")
    void zeroThresholdIsOff() {
        set(FREE_OVER, "0");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("999999"), ORG))
                .as("0 disables the threshold — otherwise every order would ship free")
                .isEqualByComparingTo("5.00");
    }

    // ── failing soft ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a negative fee is treated as unset, not as paying the shopper to order")
    void negativeFeeFallsBack() {
        set(STANDARD_FEE, "-10");
        assertThat(policy.feeFor(ShippingOption.STANDARD, new BigDecimal("100"), ORG)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("with settings unwired entirely, checkout still prices — it does not throw")
    void unwiredSettingsStillPrices() {
        ShippingPolicy bare = new ShippingPolicy();   // no SettingsService injected
        assertThat(bare.feeFor(ShippingOption.STANDARD, new BigDecimal("100"), ORG)).isEqualByComparingTo("5.00");
        assertThat(bare.codEnabled(ORG)).as("COD stays available rather than silently switching off").isTrue();
    }

    @Test
    @DisplayName("a null subtotal does not break pricing")
    void nullSubtotalIsSafe() {
        set(FREE_OVER, "1000");
        assertThat(policy.feeFor(ShippingOption.STANDARD, null, ORG)).isEqualByComparingTo("5.00");
    }

    // ── the tenant is the one NAMED, not the one signed in ─────────────────────────────────────────────

    @Test
    @DisplayName("the policy is read for the store being priced, by id")
    void policyIsReadForTheNamedStore() {
        set(STANDARD_FEE, "250");
        policy.feeFor(ShippingOption.STANDARD, new BigDecimal("100"), ORG);
        // The org must travel INTO the settings read. The first version of this class resolved the tenant from
        // the security context instead, which is empty on the public storefront — so every anonymous shopper,
        // i.e. every real customer, silently got the catalog default while staff-authenticated tests passed.
        org.mockito.Mockito.verify(settingsService)
                .getDecimalFor(eq(ORG), eq(STANDARD_FEE), any());
        org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never())
                .getDecimal(any(), any());
    }

    @Test
    @DisplayName("two stores on the same platform price delivery differently")
    void twoStoresPriceIndependently() {
        Long other = 8L;
        when(settingsService.getDecimalFor(eq(ORG), eq(STANDARD_FEE), any())).thenReturn(new BigDecimal("250"));
        when(settingsService.getDecimalFor(eq(other), eq(STANDARD_FEE), any())).thenReturn(new BigDecimal("40"));
        lenient().when(settingsService.getDecimalFor(eq(other), eq(FREE_OVER), any()))
                .thenAnswer(inv -> inv.getArgument(2));

        BigDecimal sub = new BigDecimal("100");
        assertThat(policy.feeFor(ShippingOption.STANDARD, sub, ORG)).isEqualByComparingTo("250");
        assertThat(policy.feeFor(ShippingOption.STANDARD, sub, other)).isEqualByComparingTo("40");
    }
}
