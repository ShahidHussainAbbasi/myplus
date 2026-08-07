package com.myplus.common.settings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SettingsService#getDecimal} — the fail-soft contract of the shared decimal reader.
 *
 * <p>THE COVERAGE THIS RESTORES: the "a malformed threshold must not block selling" case used to live in
 * business-service's {@code SalesQuoteTransitionTest}, back when that service did its own
 * {@code new BigDecimal(getText(...))}. When the parse moved here (B2B-P4b + OMS O3 needed the same for
 * shipping fees, §5c: move it, do not copy it) the assertion did not move with it — the service test
 * could only mock this method's RESULT, so nothing anywhere proved that {@code "ten percent"} returns the
 * fallback instead of throwing.
 *
 * <p>Why that matters: these are read on live paths — a delivery fee at checkout, a discount threshold on
 * a quote. A {@link NumberFormatException} escaping here would turn one tenant's settings typo into a
 * failed checkout. The value must degrade to the caller's stated default, never blow up the operation.
 *
 * <p>Pure — no Spring, no DB. With no security context the org is null, so every read resolves to the
 * catalog's declared default and the parse is exercised directly. Runs on {@code mvn test}.
 */
class SettingsServiceDecimalTest {

    private static final BigDecimal FALLBACK = new BigDecimal("99");

    /** A catalog whose TEXT defaults are the raw strings under test; no overrides, so defaults win. */
    private static SettingsService serviceWith(String rawDefault) {
        SettingsStore emptyStore = new SettingsStore() {
            @Override public Optional<String> find(Long organizationId, String key) { return Optional.empty(); }
            @Override public List<Stored> findAll(Long organizationId) { return List.of(); }
            @Override public void upsert(Long organizationId, Long userId, String key, String value) { }
        };
        SettingsCatalogProvider provider = () ->
                List.of(SettingEntry.text("test.decimal", "Decimal", "under test", rawDefault, "Test"));
        return new SettingsService(emptyStore, List.of(provider));
    }

    @Test
    @DisplayName("a well-formed value parses, keeping its scale")
    void parsesAWellFormedValue() {
        assertEquals(new BigDecimal("10.50"),
                serviceWith("10.50").getDecimal("test.decimal", FALLBACK),
                "minor units must survive - this is money, which is why it is not getInt");
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated, not treated as malformed")
    void trimsBeforeParsing() {
        assertEquals(new BigDecimal("7"), serviceWith("  7  ").getDecimal("test.decimal", FALLBACK),
                "a stray space typed into a settings box is not a reason to fail");
    }

    @Test
    @DisplayName("an UNPARSEABLE value returns the fallback rather than throwing")
    void malformedValueFallsBackInsteadOfThrowing() {
        // The case that lost its home when the parse moved out of SalesQuoteService.
        assertEquals(FALLBACK, serviceWith("ten percent").getDecimal("test.decimal", FALLBACK),
                "a settings typo must not take down the live path that reads it");
    }

    @Test
    @DisplayName("blank is 'not set', not 'zero'")
    void blankIsTreatedAsUnset() {
        assertEquals(FALLBACK, serviceWith("   ").getDecimal("test.decimal", FALLBACK),
                "a cleared box means 'no value'; reading it as 0 would silently apply a 0% threshold");
    }

    @Test
    @DisplayName("an unknown key returns the fallback")
    void unknownKeyFallsBack() {
        assertEquals(FALLBACK, serviceWith("10").getDecimal("test.nosuchkey", FALLBACK));
    }

    @Test
    @DisplayName("a null fallback is honoured - callers use it to mean 'no gate configured'")
    void nullFallbackIsReturnedAsNull() {
        // SalesQuoteService.discountThreshold() passes null precisely so that "unset" and "unreadable"
        // both resolve to "no approval gate". Substituting a zero here would gate every quote.
        assertNull(serviceWith("ten percent").getDecimal("test.decimal", null),
                "unset/unreadable must stay distinguishable from a real 0");
    }
}
