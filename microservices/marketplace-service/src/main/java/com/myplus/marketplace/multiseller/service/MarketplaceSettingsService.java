package com.myplus.marketplace.multiseller.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.OfferSort;
import com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting;
import com.myplus.marketplace.multiseller.repository.MarketplacePlatformSettingRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1d — the operator's platform-wide marketplace settings (source §7.4 "ranking defaults").
 *
 * <p>The default sort is what a customer sees before choosing one. {@code RECOMMENDED} means the ranker's default
 * chain (delivery promise → price → promotion → rating → distance → history). Only sorts backed by data the offers
 * actually carry are offered: rating, distance and promotion have no source yet, so NEAREST, QUALITY and PROMOTION
 * would order nothing and are not choices (they still PARSE, so an old link never fails).
 */
@Service
@RequiredArgsConstructor
public class MarketplaceSettingsService {

    public static final String RECOMMENDED = "RECOMMENDED";

    /** The sorts a customer (and the operator, as the default) can choose, in screen order. */
    public static final List<String> SORTS = List.of(RECOMMENDED, OfferSort.LOWEST_PRICE.name(),
            OfferSort.FASTEST.name(), OfferSort.WARRANTY.name(), OfferSort.RETURN_POLICY.name());

    private final MarketplacePlatformSettingRepository settings;
    private final SellerAccess access;
    /** G-16 (R22.4): an operator's change to how the marketplace behaves is audited. */
    private final MarketplaceAuditService audit;

    /** The current default; a missing or no-longer-valid stored value reads as RECOMMENDED, never as an error. */
    @Transactional(readOnly = true)
    public String defaultSort() {
        return settings.findById(MarketplacePlatformSetting.DEFAULT_SORT)
                .map(MarketplacePlatformSetting::getSettingValue)
                .filter(SORTS::contains)
                .orElse(RECOMMENDED);
    }

    /** The operator console's read. Harmless data, but the console is the operator's: a tenant gets the same 403. */
    @Transactional(readOnly = true)
    public String operatorDefaultSort() {
        access.assertOperator();
        return defaultSort();
    }

    @Transactional
    public String setDefaultSort(String sort) {
        access.assertOperator();
        String s = sort == null ? "" : sort.trim().toUpperCase(Locale.ROOT);
        if (!SORTS.contains(s))
            throw new ValidationException("Choose one of: Recommended, Lowest price, Fastest delivery, Longest warranty, Longest returns.");
        MarketplacePlatformSetting row = settings.findById(MarketplacePlatformSetting.DEFAULT_SORT).orElseGet(() -> {
            MarketplacePlatformSetting n = new MarketplacePlatformSetting();
            n.setSettingKey(MarketplacePlatformSetting.DEFAULT_SORT);
            return n;
        });
        row.setSettingValue(s);
        row.setUpdatedByUserId(access.userId());
        row.setUpdatedAt(LocalDateTime.now());
        settings.save(row);
        audit.event("MKT_SETTING_CHANGED", "MKT_SETTING", row.getSettingKey(), null, MarketplaceAuditService.Actor.OPERATOR, null, row.getSettingValue(), null, null);
        return s;
    }

    /**
     * The sort to APPLY for a request: the customer's choice when it is one; otherwise the operator's default.
     * Explicit RECOMMENDED is a choice (the default chain), so it is not replaced by the operator's default.
     * Returns null for "the default chain".
     */
    @Transactional(readOnly = true)
    public OfferSort resolve(String requested) {
        String r = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (RECOMMENDED.equals(r)) return null;
        OfferSort parsed = OfferSort.parse(r);
        if (parsed != null) return parsed;
        String d = defaultSort();
        return RECOMMENDED.equals(d) ? null : OfferSort.valueOf(d);
    }

    // ── MKT-1e: the seller's acceptance window ─────────────────────────────────────────────────────────

    public static final int DEFAULT_ACCEPT_MINUTES = 5;
    static final int MIN_ACCEPT_MINUTES = 1;
    static final int MAX_ACCEPT_MINUTES = 60;

    /**
     * Minutes a MERCHANT seller has to accept (source §10: "configurable"; the MKT-1a default is 5). A missing or
     * invalid stored value reads as the default. The stock hold is taken for longer than this by the trade side
     * (order holds expire after days), so the window is always the shorter of the two, as AcceptanceTerms requires.
     */
    @Transactional(readOnly = true)
    public int acceptMinutes() {
        return settings.findById(MarketplacePlatformSetting.ACCEPT_MINUTES)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> { try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return -1; } })
                .filter(m -> m >= MIN_ACCEPT_MINUTES && m <= MAX_ACCEPT_MINUTES)
                .orElse(DEFAULT_ACCEPT_MINUTES);
    }

    @Transactional(readOnly = true)
    public int operatorAcceptMinutes() {
        access.assertOperator();
        return acceptMinutes();
    }

    @Transactional
    public int setAcceptMinutes(Integer minutes) {
        access.assertOperator();
        if (minutes == null || minutes < MIN_ACCEPT_MINUTES || minutes > MAX_ACCEPT_MINUTES)
            throw new ValidationException("The acceptance window is 1 to 60 minutes.");
        MarketplacePlatformSetting row = settings.findById(MarketplacePlatformSetting.ACCEPT_MINUTES).orElseGet(() -> {
            MarketplacePlatformSetting n = new MarketplacePlatformSetting();
            n.setSettingKey(MarketplacePlatformSetting.ACCEPT_MINUTES);
            return n;
        });
        row.setSettingValue(String.valueOf(minutes));
        row.setUpdatedByUserId(access.userId());
        row.setUpdatedAt(LocalDateTime.now());
        settings.save(row);
        audit.event("MKT_SETTING_CHANGED", "MKT_SETTING", row.getSettingKey(), null, MarketplaceAuditService.Actor.OPERATOR, null, row.getSettingValue(), null, null);
        return minutes;
    }

    // ── MKT-1f: the change-of-mind pickup fee (ruling R-MKT-14: the customer bears it) ─────────────────────

    public static final java.math.BigDecimal DEFAULT_CHANGE_OF_MIND_FEE = new java.math.BigDecimal("250.00");
    static final java.math.BigDecimal MAX_CHANGE_OF_MIND_FEE = new java.math.BigDecimal("5000.00");

    /** The fee deducted from a change-of-mind refund. A missing or invalid stored value reads as the default. */
    @Transactional(readOnly = true)
    public java.math.BigDecimal changeOfMindFee() {
        return settings.findById(MarketplacePlatformSetting.CHANGE_OF_MIND_FEE)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> { try { return new java.math.BigDecimal(v.trim()); } catch (NumberFormatException e) { return null; } })
                .filter(f -> f.signum() >= 0 && f.compareTo(MAX_CHANGE_OF_MIND_FEE) <= 0)
                .orElse(DEFAULT_CHANGE_OF_MIND_FEE).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Transactional
    public java.math.BigDecimal setChangeOfMindFee(java.math.BigDecimal amount) {
        access.assertOperator();
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_CHANGE_OF_MIND_FEE) > 0)
            throw new ValidationException("The change-of-mind fee is Rs 0 to 5,000.");
        java.math.BigDecimal v = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        MarketplacePlatformSetting row = settings.findById(MarketplacePlatformSetting.CHANGE_OF_MIND_FEE).orElseGet(() -> {
            MarketplacePlatformSetting n = new MarketplacePlatformSetting();
            n.setSettingKey(MarketplacePlatformSetting.CHANGE_OF_MIND_FEE);
            return n;
        });
        row.setSettingValue(v.toPlainString());
        row.setUpdatedByUserId(access.userId());
        row.setUpdatedAt(LocalDateTime.now());
        settings.save(row);
        audit.event("MKT_SETTING_CHANGED", "MKT_SETTING", row.getSettingKey(), null, MarketplaceAuditService.Actor.OPERATOR, null, row.getSettingValue(), null, null);
        return v;
    }

    // ── MKT-1g: settlement ───────────────────────────────────────────────────────────────────────────────

    public static final int DEFAULT_T_PLUS_DAYS = 1;
    static final int MAX_T_PLUS_DAYS = 10;

    /** N in T+N (source §15, design §6.2 {@code mkt.settlement.tPlusDays}). A missing or invalid value reads as 1. */
    @Transactional(readOnly = true)
    public int tPlusDays() {
        return settings.findById(MarketplacePlatformSetting.SETTLEMENT_T_PLUS_DAYS)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> { try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return -1; } })
                .filter(n -> n >= 0 && n <= MAX_T_PLUS_DAYS)
                .orElse(DEFAULT_T_PLUS_DAYS);
    }

    @Transactional
    public int setTPlusDays(Integer days) {
        access.assertOperator();
        if (days == null || days < 0 || days > MAX_T_PLUS_DAYS)
            throw new ValidationException("Payment days after the return window are 0 to 10 business days.");
        save(MarketplacePlatformSetting.SETTLEMENT_T_PLUS_DAYS, String.valueOf(days));
        return days;
    }

    // ── MKT-2a: one checkout, several sellers ────────────────────────────────────────────────────────────

    /** Off unless an operator switched it on: a missing or unreadable value is off (the Phase 1 rule, R17.1). */
    @Transactional(readOnly = true)
    public boolean multiSeller() {
        return settings.findById(MarketplacePlatformSetting.MULTI_SELLER)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean operatorMultiSeller() {
        access.assertOperator();
        return multiSeller();
    }

    @Transactional
    public boolean setMultiSeller(boolean on) {
        access.assertOperator();
        save(MarketplacePlatformSetting.MULTI_SELLER, String.valueOf(on));
        return on;
    }

    // ── MKT-2b: a short part moves to another seller ─────────────────────────────────────────────────────

    /** Off unless an operator switched it on: a missing or unreadable value is off (the part ends, as in Phase 1). */
    @Transactional(readOnly = true)
    public boolean reroute() {
        return settings.findById(MarketplacePlatformSetting.SHORTAGE_REROUTE)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    @Transactional
    public boolean setReroute(boolean on) {
        access.assertOperator();
        save(MarketplacePlatformSetting.SHORTAGE_REROUTE, String.valueOf(on));
        return on;
    }

    // ── MKT-2c: the operator's test switch for live routing ──────────────────────────────────────────────

    /** One seller made slow on purpose, so the routing deadline can be seen working. */
    public record SlowSeller(Long sellerOrganizationId, long delayMs) {}

    static final long MAX_TEST_DELAY_MS = 30_000;

    /** The stored test slowness, or null (off, missing or unreadable). {@link LiveRouting} honours it only when
     *  {@code mkt.routing.test-switch} is on. */
    @Transactional(readOnly = true)
    public SlowSeller slowSeller() {
        return settings.findById(MarketplacePlatformSetting.ROUTING_SLOW_SELLER)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> {
                    String[] p = v.trim().split(":");
                    try {
                        SlowSeller s = new SlowSeller(Long.parseLong(p[0]), Long.parseLong(p[1]));
                        return s.delayMs() > 0 && s.delayMs() <= MAX_TEST_DELAY_MS ? s : null;
                    } catch (RuntimeException e) { return null; }
                })
                .orElse(null);
    }

    /** {@code seller} null, or delay 0, switches it off. */
    @Transactional
    public SlowSeller setSlowSeller(Long seller, Long delayMs) {
        access.assertOperator();
        if (seller == null || delayMs == null || delayMs == 0) {
            save(MarketplacePlatformSetting.ROUTING_SLOW_SELLER, "");
            return null;
        }
        if (delayMs < 0 || delayMs > MAX_TEST_DELAY_MS)
            throw new ValidationException("The test delay is 1 to 30,000 milliseconds.");
        save(MarketplacePlatformSetting.ROUTING_SLOW_SELLER, seller + ":" + delayMs);
        return new SlowSeller(seller, delayMs);
    }

    // ── MKT-2d: what a seller owes for cash orders ───────────────────────────────────────────────────────

    public static final int DEFAULT_COD_REMIT_DAYS = 7;
    static final int MAX_COD_REMIT_DAYS = 60;

    /** Days a seller has to pay what it owes for cash orders. A missing or invalid value reads as 7. */
    @Transactional(readOnly = true)
    public int codRemitDays() {
        return settings.findById(MarketplacePlatformSetting.COD_REMIT_DAYS)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> { try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return -1; } })
                .filter(n -> n >= 1 && n <= MAX_COD_REMIT_DAYS)
                .orElse(DEFAULT_COD_REMIT_DAYS);
    }

    @Transactional
    public int setCodRemitDays(Integer days) {
        access.assertOperator();
        if (days == null || days < 1 || days > MAX_COD_REMIT_DAYS)
            throw new ValidationException("Days to pay for cash orders are 1 to 60.");
        save(MarketplacePlatformSetting.COD_REMIT_DAYS, String.valueOf(days));
        return days;
    }

    /** Off unless an operator switched it on: a seller that owes keeps taking cash orders, as before. */
    @Transactional(readOnly = true)
    public boolean codStopWhenOverdue() {
        return settings.findById(MarketplacePlatformSetting.COD_STOP_WHEN_OVERDUE)
                .map(MarketplacePlatformSetting::getSettingValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    @Transactional
    public boolean setCodStopWhenOverdue(boolean on) {
        access.assertOperator();
        save(MarketplacePlatformSetting.COD_STOP_WHEN_OVERDUE, String.valueOf(on));
        return on;
    }

    /** The operator's books: the org whose ledger takes the commission, and the user postings are made as. */
    public record Books(Long organizationId, Long userId) {}

    /** Empty until an operator chooses (or first settles, which chooses the operator's own organisation). */
    @Transactional(readOnly = true)
    public java.util.Optional<Books> books() {
        return settings.findById(MarketplacePlatformSetting.SETTLEMENT_BOOKS_ORG)
                .flatMap(r -> {
                    try { return java.util.Optional.of(new Books(Long.parseLong(r.getSettingValue().trim()), r.getUpdatedByUserId())); }
                    catch (RuntimeException e) { return java.util.Optional.empty(); }
                });
    }

    /** Book the marketplace in the CALLING operator's own organisation. */
    @Transactional
    public Books useMyBooks() {
        access.assertOperator();
        Long org = access.org();
        save(MarketplacePlatformSetting.SETTLEMENT_BOOKS_ORG, String.valueOf(org));
        return new Books(org, access.userId());
    }

    private void save(String key, String value) {
        MarketplacePlatformSetting row = settings.findById(key).orElseGet(() -> {
            MarketplacePlatformSetting n = new MarketplacePlatformSetting();
            n.setSettingKey(key);
            return n;
        });
        row.setSettingValue(value);
        row.setUpdatedByUserId(access.userId());
        row.setUpdatedAt(LocalDateTime.now());
        settings.save(row);
        audit.event("MKT_SETTING_CHANGED", "MKT_SETTING", key, null, MarketplaceAuditService.Actor.OPERATOR, null, value, null, null);
    }
}
