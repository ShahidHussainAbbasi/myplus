package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.settings.Capability;
import com.myplus.common.settings.CapabilityService;
import com.myplus.common.settings.SettingsService;

/**
 * U15 Slice A — what {@code /looseInfo} lets the till OFFER.
 *
 * <p>Design: {@code microservices/docs/slices/u15-pack-loose-ux.md} §3.
 *
 * <p>Two gaps, one endpoint. <b>A1:</b> this answered from the product's {@code allowLoose} alone while
 * {@link com.myplus.business_service.service.SagaSellService} asserts {@code LOOSE_SELLING} at submit — so a
 * tenant carrying the flag without the capability was shown the Pack|Piece toggle and lost the WHOLE BASKET to
 * a refusal at Complete Sale. <b>A3:</b> {@code defaultSellUnit} was stored by the product form and returned to
 * nobody, so "Sales start as pieces" was a setting that did nothing.
 *
 * <p>Pure Mockito — no Spring, no database.
 */
class LooseInfoOfferTest {

    private static final long PRODUCT = 6948L;

    private final CatalogClient catalog = mock(CatalogClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private SellController controller;

    /** The reported product: a box of 40 tablets at 311.60, splittable. */
    private static ProductRef box(boolean allowLoose, String defaultSellUnit) {
        return ProductRef.builder().id(PRODUCT).name("test").sellingPrice(new BigDecimal("311.60"))
                .packSize(40).allowLoose(allowLoose).looseUnit("tablet").looseUnitPlural("tablets")
                .defaultSellUnit(defaultSellUnit).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answer() {
        Object body = controller.looseInfo(PRODUCT).getObject();
        return (Map<String, Object>) body;
    }

    @BeforeEach
    void setUp() {
        controller = new SellController();
        ReflectionTestUtils.setField(controller, "catalogClient", catalog);
        ReflectionTestUtils.setField(controller, "settingsService", settings);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilities);
        when(settings.getDecimal(eq("pos.sale.looseMarkupPct"), any())).thenReturn(BigDecimal.ZERO);
        when(capabilities.isEnabled(Capability.LOOSE_SELLING)).thenReturn(true);
        when(catalog.getProduct(PRODUCT)).thenReturn(box(true, "PACK"));
    }

    // ── A1: the offer and the refusal must agree ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ the capability is OFF: the till is not offered a loose sale the saga would refuse")
    void withoutTheCapabilityTheTillIsNotOffered() {
        when(capabilities.isEnabled(Capability.LOOSE_SELLING)).thenReturn(false);

        Map<String, Object> out = answer();

        assertThat(out.get("allowLoose")).as("the product says yes; the TENANT may not").isEqualTo(false);
        // The plain answer carries nothing else — the till hides its toggle and the screen is today's.
        assertThat(out).doesNotContainKeys("packSize", "looseRate", "defaultSellUnit");
    }

    @Test
    @DisplayName("⭐ the capability is ON and the product allows it: the full answer, with the per-piece rate")
    void withTheCapabilityTheOfferStands() {
        Map<String, Object> out = answer();

        assertThat(out.get("allowLoose")).isEqualTo(true);
        assertThat(out.get("packSize")).isEqualTo(40);
        assertThat(out.get("looseUnitPlural")).isEqualTo("tablets");
        assertThat((BigDecimal) out.get("looseRate")).isEqualByComparingTo("7.79");
    }

    @Test
    @DisplayName("a sealed product is refused even with the capability — the two rules are independent")
    void aSealedProductIsStillSealed() {
        when(catalog.getProduct(PRODUCT)).thenReturn(box(false, "PACK"));

        assertThat(answer().get("allowLoose")).isEqualTo(false);
    }

    // ── A3: the keystroke that disappears ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ \"Sales start as pieces\" reaches the till")
    void theDefaultSellUnitIsAnswered() {
        when(catalog.getProduct(PRODUCT)).thenReturn(box(true, "LOOSE"));

        assertThat(answer().get("defaultSellUnit")).isEqualTo("LOOSE");
    }

    @Test
    @DisplayName("a product that starts whole says so explicitly, rather than leaving the till to assume")
    void aPackDefaultIsStated() {
        assertThat(answer().get("defaultSellUnit")).isEqualTo("PACK");
    }

    @Test
    @DisplayName("⭐ an unset default is PACK — never LOOSE by accident on an older row")
    void aMissingDefaultIsPack() {
        when(catalog.getProduct(PRODUCT)).thenReturn(box(true, null));

        assertThat(answer().get("defaultSellUnit")).isEqualTo("PACK");
    }

    // ── U15-B1: pricing a pack price the owner is still typing ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> preview(String packRate, Integer packSize) {
        return (Map<String, Object>) controller.looseRatePreview(
                packRate == null ? null : new BigDecimal(packRate), packSize).getObject();
    }

    @Test
    @DisplayName("⭐⭐ the product form is told what ONE PIECE costs — from the till's rule, not a browser divide")
    void thePreviewPricesOnePiece() {
        Map<String, Object> out = preview("311.60", 40);

        assertThat((BigDecimal) out.get("looseRate")).isEqualByComparingTo("7.79");
        assertThat((BigDecimal) out.get("packRate")).isEqualByComparingTo("311.60");
        assertThat(out.get("packSize")).isEqualTo(40);
    }

    @Test
    @DisplayName("⭐ the shop's broken-pack markup is applied, and rounded UP to the paisa")
    void thePreviewAppliesTheMarkup() {
        when(settings.getDecimal(eq("pos.sale.looseMarkupPct"), any())).thenReturn(new BigDecimal("10"));

        // 311.60 × 1.10 ÷ 40 = 8.569 → 8.57. Rounding DOWN would lose money on every broken pack.
        assertThat((BigDecimal) preview("311.60", 40).get("looseRate")).isEqualByComparingTo("8.57");
    }

    @Test
    @DisplayName("⭐ the preview MATCHES what the till will charge — one rule, or the form lies")
    void thePreviewAgreesWithTheTill() {
        Map<String, Object> formSays = preview("311.60", 40);
        Map<String, Object> tillSays = answer();          // /looseInfo, the till's own source

        assertThat((BigDecimal) formSays.get("looseRate"))
                .isEqualByComparingTo((BigDecimal) tillSays.get("looseRate"));
    }

    @Test
    @DisplayName("mid-keystroke figures answer with nothing to show, never an error the form must handle")
    void anIncompleteFormIsNotAnError() {
        // The owner has typed a price but no pack size yet, or a pack of one, or is still on the first digit.
        assertThat(preview("311.60", null)).isEmpty();
        assertThat(preview("311.60", 1)).isEmpty();
        assertThat(preview("0", 40)).isEmpty();
        assertThat(preview(null, 40)).isEmpty();
    }

    @Test
    @DisplayName("⭐⭐ a LOOSE default on a product the tenant may not split never reaches the till")
    void theDefaultCannotSmuggleInARefusedUnit() {
        // Both halves of A1+A3 together: the form said "start in pieces", the plan says no loose selling.
        // Answering LOOSE here would open every line in a unit Complete Sale refuses.
        when(catalog.getProduct(PRODUCT)).thenReturn(box(true, "LOOSE"));
        when(capabilities.isEnabled(Capability.LOOSE_SELLING)).thenReturn(false);

        Map<String, Object> out = answer();

        assertThat(out.get("allowLoose")).isEqualTo(false);
        assertThat(out).doesNotContainKey("defaultSellUnit");
    }
}
