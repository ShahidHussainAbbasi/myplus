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
}
