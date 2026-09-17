package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.SalesQuote;
import com.myplus.business_service.entity.SalesQuoteLine;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.settings.SettingsService;

/**
 * U14 — a sales quote in loose units ("10 tablets out of a box of 40"). Pure Mockito, no database.
 *
 * <p>Design: {@code microservices/docs/slices/u14-loose-units-in-quotes.md}. Built on 122 of 218 dev quotes belonging
 * to PHARMA tenants, who could previously only quote 0.25 of a box at the box price.
 *
 * <p>The cases that decide money: the quote is priced with the TILL's rule (a second copy would drift), and — the
 * user's ruling of 2026-09-17 — an accepted quote converts to exactly the total the customer accepted, even when the
 * shop's markup or the product's pack size has changed since.
 */
class QuoteLooseUnitsTest {

    private static final long PRODUCT = 6948L;

    private final CatalogClient catalog = mock(CatalogClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private SalesQuoteService service;

    /** The reported product: a box of 40 tablets at 311.60, splittable. */
    private static ProductRef box(Integer packSize, boolean allowLoose) {
        return ProductRef.builder().id(PRODUCT).name("test").sellingPrice(new BigDecimal("311.60"))
                .packSize(packSize).allowLoose(allowLoose).looseUnit("tablet").looseUnitPlural("tablets").build();
    }

    private static SalesQuoteLine looseRequest(float pieces) {
        SalesQuoteLine l = new SalesQuoteLine();
        l.setProductId(PRODUCT);
        l.setProductName("test");
        l.setUnitPrice(new BigDecimal("311.60"));
        l.setDiscount(BigDecimal.ZERO);
        l.setSoldUnit("LOOSE");
        l.setSoldQuantity(pieces);
        return l;
    }

    @BeforeEach
    void setUp() {
        service = new SalesQuoteService();
        ReflectionTestUtils.setField(service, "catalogClient", catalog);
        ReflectionTestUtils.setField(service, "settingsService", settings);
        when(settings.getDecimal(eq("pos.sale.looseMarkupPct"), any())).thenReturn(BigDecimal.ZERO);
        when(catalog.getProduct(PRODUCT)).thenReturn(box(40, true));
    }

    // ── pricing ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 10 tablets of a 40-box are quoted as 10 tablets @ 7.79 = 77.90, 0.25 of a box on the shelf")
    void tenTabletsAreQuotedInTablets() {
        SalesQuoteLine l = looseRequest(10f);

        service.priceLooseLine(l, 10f);

        assertThat(l.getSoldUnit()).isEqualTo("LOOSE");
        assertThat(l.getSoldQuantity()).isEqualTo(10f);
        assertThat(l.getSoldRate()).isEqualByComparingTo("7.79");
        assertThat(l.getQuantity()).isEqualTo(0.25f);
        assertThat(l.getLineTotal()).isEqualByComparingTo("77.90");
        // unitPrice stays the PACK price: conversion passes it as sellRate, which the rule divides.
        assertThat(l.getUnitPrice()).as("stored marked-up, the markup would be applied twice on conversion")
                .isEqualByComparingTo("311.60");
        assertThat(l.getPackSizeSnapshot()).isEqualTo(40);
        assertThat(l.getLooseMarkupPct()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("⭐⭐ a quote and a till sale for the same pieces cost the SAME — one rule, not two")
    void quotePriceEqualsTillPrice() {
        when(settings.getDecimal(eq("pos.sale.looseMarkupPct"), any())).thenReturn(new BigDecimal("10"));
        SalesQuoteLine l = looseRequest(7f);

        service.priceLooseLine(l, 7f);

        SellDTO till = new SellDTO();
        till.setSoldQuantity(7f);
        SagaSellService.LooseLine sale = SagaSellService.looseLine(
                till, box(40, true), "test", new BigDecimal("311.60"), new BigDecimal("10"));
        assertThat(l.getLineTotal()).isEqualByComparingTo(sale.lineTotal());
        assertThat(l.getSoldRate()).isEqualByComparingTo(sale.perPiece());
    }

    @Test
    @DisplayName("a line discount comes off the loose total, never below zero")
    void discountComesOffTheLooseTotal() {
        SalesQuoteLine l = looseRequest(10f);
        l.setDiscount(new BigDecimal("7.90"));

        service.priceLooseLine(l, 10f);

        assertThat(l.getLineTotal()).isEqualByComparingTo("70.00");
    }

    // ── refusals reach the pharmacist ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a product that may not be split is REFUSED with its reason, not a bare 'could not create'")
    void aSealedProductIsRefusedWithItsReason() {
        when(catalog.getProduct(PRODUCT)).thenReturn(box(40, false));

        // QuoteRefused is what SalesQuoteController shows verbatim; any other exception becomes a generic error.
        assertThatThrownBy(() -> service.priceLooseLine(looseRequest(10f), 10f))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class)
                .hasMessageContaining("not sold by the piece");
    }

    @Test
    @DisplayName("half a tablet cannot be quoted, as it cannot be sold")
    void fractionalPiecesAreRefused() {
        assertThatThrownBy(() -> service.priceLooseLine(looseRequest(2.5f), 2.5f))
                .isInstanceOf(SalesQuoteService.QuoteRefused.class)
                .hasMessageContaining("whole");
    }

    // ── conversion: the accepted quote binds ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the lock map addresses exactly the loose lines, by their position in the sale")
    void locksAreKeyedByLinePosition() {
        SalesQuote q = new SalesQuote();
        SalesQuoteLine pack = new SalesQuoteLine();
        pack.setProductId(1L);
        pack.setQuantity(2f);
        SalesQuoteLine loose = looseRequest(10f);
        service.priceLooseLine(loose, 10f);
        q.getLines().add(pack);
        q.getLines().add(loose);

        Map<Integer, SagaSellService.LoosePriceLock> locks = SalesQuoteService.looseLocksFor(q);

        assertThat(locks).containsOnlyKeys(1);
        assertThat(locks.get(1).packSize()).isEqualTo(40);
        assertThat(locks.get(1).markupPct()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("⭐ conversion carries a loose line as PIECES with the pack price, and a pack line unchanged")
    void conversionCarriesPieces() {
        SalesQuote q = new SalesQuote();
        SalesQuoteLine pack = new SalesQuoteLine();
        pack.setProductId(1L);
        pack.setQuantity(2f);
        pack.setUnitPrice(new BigDecimal("100"));
        SalesQuoteLine loose = looseRequest(10f);
        service.priceLooseLine(loose, 10f);
        q.getLines().add(pack);
        q.getLines().add(loose);

        CustomerHistoryDTO dto = service.toSaleRequest(q);

        SellDTO p = dto.getSales().get(0);
        assertThat(p.getSoldUnit()).as("a pack line is exactly as it was").isNull();
        assertThat(p.getQuantity()).isEqualTo(2f);
        SellDTO s = dto.getSales().get(1);
        assertThat(s.getSoldUnit()).isEqualTo("LOOSE");
        assertThat(s.getSoldQuantity()).isEqualTo(10f);
        assertThat(s.getSellRate()).isEqualByComparingTo("311.60");
    }

    @Test
    @DisplayName("⭐⭐ THE RULING: markup and pack size changed after acceptance — the invoice still charges the quote")
    void anAcceptedQuoteIsInvoicedAtItsAcceptedTotal() {
        // Quoted and accepted at markup 0, pack of 40: 10 tablets = 77.90.
        SalesQuoteLine quoted = looseRequest(10f);
        service.priceLooseLine(quoted, 10f);
        assertThat(quoted.getLineTotal()).isEqualByComparingTo("77.90");

        SellDTO line = new SellDTO();
        line.setSoldQuantity(10f);

        /*
         * ⚠ EACH CHANGE ON ITS OWN, with the figure it would charge stated EXACTLY.
         *
         * The first version changed markup to 25% AND pack size to 50 together — and 311.60 × 1.25 ÷ 50 = 7.79, the
         * SAME per-piece price as 311.60 ÷ 40. Both roads led to 77.90, so the "unlocked" control could not fail:
         * caught because its isNotEqual went red (myplus-54, whole-module run, 2026-09-17). A control that passes by
         * coincidence proves nothing. These values are chosen so locked and unlocked genuinely differ, and asserting
         * the exact repriced figure means a future coincidence is caught too.
         */

        // (a) The owner raised the loose markup to 25%. Same pack of 40.
        ProductRef samePack = box(40, true);
        SagaSellService.LooseLine markupReplayed = SagaSellService.looseLine(line, samePack, "test",
                quoted.getUnitPrice(), quoted.getLooseMarkupPct(), quoted.getPackSizeSnapshot());
        SagaSellService.LooseLine markupRepriced = SagaSellService.looseLine(line, samePack, "test",
                quoted.getUnitPrice(), new BigDecimal("25"));
        assertThat(markupReplayed.lineTotal()).as("locked: the accepted total").isEqualByComparingTo("77.90");
        assertThat(markupRepriced.lineTotal())
                .as("control: at today's 25% it would be 311.60 × 1.25 ÷ 40 = 9.74 a tablet, 97.40 — not what was accepted")
                .isEqualByComparingTo("97.40");

        // (b) The product was repacked as 50s. Same markup (0).
        ProductRef repacked = box(50, true);
        SagaSellService.LooseLine packReplayed = SagaSellService.looseLine(line, repacked, "test",
                quoted.getUnitPrice(), quoted.getLooseMarkupPct(), quoted.getPackSizeSnapshot());
        SagaSellService.LooseLine packRepriced = SagaSellService.looseLine(line, repacked, "test",
                quoted.getUnitPrice(), quoted.getLooseMarkupPct());
        assertThat(packReplayed.lineTotal()).as("locked: the accepted total").isEqualByComparingTo("77.90");
        assertThat(packRepriced.lineTotal())
                .as("control: as a pack of 50 it would be 311.60 ÷ 50 = 6.24 a tablet, 62.40 — not what was accepted")
                .isEqualByComparingTo("62.40");
    }
}
