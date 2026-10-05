package com.myplus.business_service.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myplus.common.settings.SettingsService;

/**
 * PR-2 — precedence (product % over the business's), the guards, and what an unreadable or unset rule does.
 * Settings are answered the way SettingsService really answers: getChoice lower-cases and validates against the
 * allowed set, so an upper-case constant here would fail exactly as it would in production.
 */
@ExtendWith(MockitoExtension.class)
class MarkupPolicyTest {

    @Mock private SettingsService settings;
    @InjectMocks private MarkupPolicy policy;

    private static BigDecimal d(String v) { return v == null ? null : new BigDecimal(v); }

    /** doAnswer, not when(mock.call()): re-stubbing with when() INVOKES the previous stub (with null arguments). */
    private void stored(Map<String, String> v) {
        lenient().doAnswer(i -> {
            String raw = v.get((String) i.getArgument(0));
            Set<String> allowed = i.getArgument(1);
            String norm = raw == null ? null : raw.trim().toLowerCase(java.util.Locale.ROOT);
            return norm != null && allowed.contains(norm) ? norm : i.getArgument(2);
        }).when(settings).getChoice(any(), any(), any());
        lenient().doAnswer(i -> v.containsKey(MarkupPolicy.PCT_KEY) ? d(v.get(MarkupPolicy.PCT_KEY)) : i.getArgument(1))
                .when(settings).getDecimal(eq(MarkupPolicy.PCT_KEY), any());
        lenient().doReturn(!"false".equals(v.getOrDefault(MarkupPolicy.NEVER_LOWER_KEY, "true")))
                .when(settings).getBool(MarkupPolicy.NEVER_LOWER_KEY);
        lenient().doReturn(Integer.parseInt(v.getOrDefault(MarkupPolicy.MAX_RISE_KEY, "0")))
                .when(settings).getInt(eq(MarkupPolicy.MAX_RISE_KEY), anyInt());
    }

    @Test
    @DisplayName("defaults: suggest, but NO % → no price (decision 4: nothing until the owner sets one)")
    void default_has_no_rule() {
        stored(Map.of());
        MarkupPolicy.Suggestion s = policy.suggest(d("100"), null, d("120"));
        assertThat(s.mode()).isEqualTo("suggest");
        assertThat(s.price()).isNull();
        assertThat(s.autoApplies()).isFalse();
    }

    @Test
    @DisplayName("business 14.5% markup → 114.50; the product's own 30% wins over it → 130.00")
    void precedence() {
        stored(Map.of(MarkupPolicy.PCT_KEY, "14.5"));
        MarkupPolicy.Suggestion biz = policy.suggest(d("100"), null, d("100"));
        assertThat(biz.price()).isEqualByComparingTo("114.50");
        assertThat(biz.pctSource()).isEqualTo("BUSINESS");
        MarkupPolicy.Suggestion own = policy.suggest(d("100"), d("30"), d("100"));
        assertThat(own.price()).isEqualByComparingTo("130.00");
        assertThat(own.pctSource()).isEqualTo("PRODUCT");
    }

    @Test
    @DisplayName("PR-2b precedence: product 30% > category 20% > business 14.5%; source names which")
    void category_precedence() {
        stored(Map.of(MarkupPolicy.PCT_KEY, "14.5"));
        MarkupPolicy.Suggestion cat = policy.suggest(d("100"), null, d("20"), d("100"));
        assertThat(cat.price()).isEqualByComparingTo("120.00");
        assertThat(cat.pctSource()).isEqualTo("CATEGORY");
        MarkupPolicy.Suggestion own = policy.suggest(d("100"), d("30"), d("20"), d("100"));
        assertThat(own.price()).isEqualByComparingTo("130.00");
        assertThat(own.pctSource()).isEqualTo("PRODUCT");
        MarkupPolicy.Suggestion biz = policy.suggest(d("100"), null, d("0"), d("100"));   // 0 on the category = not set
        assertThat(biz.price()).isEqualByComparingTo("114.50");
        assertThat(biz.pctSource()).isEqualTo("BUSINESS");
    }

    @Test
    @DisplayName("basis margin + rounding near5, stored as the settings screen may save them (MARGIN, NEAR5)")
    void basis_and_rounding_any_case() {
        stored(Map.of(MarkupPolicy.PCT_KEY, "14.5", MarkupPolicy.BASIS_KEY, "MARGIN", MarkupPolicy.ROUNDING_KEY, "Near5"));
        MarkupPolicy.Suggestion s = policy.suggest(d("100"), null, d("100"));
        assertThat(s.raw()).isEqualByComparingTo("116.96");
        assertThat(s.price()).isEqualByComparingTo("115.00");
    }

    @Test
    @DisplayName("Auto + never lower (default): a cheaper purchase is held back — guard NEVER_LOWER, nothing applied")
    void never_lower() {
        stored(Map.of(MarkupPolicy.MODE_KEY, "auto", MarkupPolicy.PCT_KEY, "10"));
        MarkupPolicy.Suggestion s = policy.suggest(d("100"), null, d("150"));   // rule says 110, price is 150
        assertThat(s.price()).isEqualByComparingTo("110.00");
        assertThat(s.guard()).isEqualTo(MarkupPolicy.GUARD_NEVER_LOWER);
        assertThat(s.autoApplies()).isFalse();
    }

    @Test
    @DisplayName("never lower switched off → Auto may lower")
    void may_lower_when_allowed() {
        stored(Map.of(MarkupPolicy.MODE_KEY, "auto", MarkupPolicy.PCT_KEY, "10", MarkupPolicy.NEVER_LOWER_KEY, "false"));
        assertThat(policy.suggest(d("100"), null, d("150")).autoApplies()).isTrue();
    }

    @Test
    @DisplayName("Auto + rise cap 10%: 100 → 125 is held back (MAX_RISE); 100 → 110 applies (on the cap)")
    void rise_cap() {
        stored(Map.of(MarkupPolicy.MODE_KEY, "auto", MarkupPolicy.PCT_KEY, "25", MarkupPolicy.MAX_RISE_KEY, "10"));
        MarkupPolicy.Suggestion big = policy.suggest(d("100"), null, d("100"));
        assertThat(big.guard()).isEqualTo(MarkupPolicy.GUARD_MAX_RISE);
        assertThat(big.autoApplies()).isFalse();
        MarkupPolicy.Suggestion onCap = policy.suggest(d("100"), d("10"), d("100"));
        assertThat(onCap.guard()).isNull();
        assertThat(onCap.autoApplies()).isTrue();
    }

    @Test
    @DisplayName("mode off → no price even with a % set; Suggest never 'applies'")
    void off_and_suggest() {
        stored(Map.of(MarkupPolicy.MODE_KEY, "off", MarkupPolicy.PCT_KEY, "14.5"));
        assertThat(policy.suggest(d("100"), null, d("100")).price()).isNull();
        stored(Map.of(MarkupPolicy.MODE_KEY, "suggest", MarkupPolicy.PCT_KEY, "14.5"));
        MarkupPolicy.Suggestion s = policy.suggest(d("100"), null, d("100"));
        assertThat(s.price()).isEqualByComparingTo("114.50");
        assertThat(s.autoApplies()).isFalse();
    }

    @Test
    @DisplayName("a margin of 100% → no price, no exception reaching the purchase")
    void impossible_margin_is_no_price() {
        stored(Map.of(MarkupPolicy.PCT_KEY, "100", MarkupPolicy.BASIS_KEY, "margin"));
        assertThat(policy.suggest(d("100"), null, d("100")).price()).isNull();
    }

    @Test
    @DisplayName("settings unreadable → OFF: an outage never re-prices a product")
    void outage_is_off() {
        when(settings.getChoice(any(), any(), any())).thenThrow(new RuntimeException("settings down"));
        MarkupPolicy.Suggestion s = policy.suggest(d("100"), d("30"), d("100"));
        assertThat(s.mode()).isEqualTo("off");
        assertThat(s.price()).isNull();
        assertThat(policy.mode()).isEqualTo("off");
    }
}
