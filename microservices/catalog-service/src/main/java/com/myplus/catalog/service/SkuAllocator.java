package com.myplus.catalog.service;

import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.docnum.DocumentNumberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SKU-AUTO (doc slices/109-sku-auto-code.md) — the next automatic SKU for a business.
 *
 * <p>ONE code per product: a blank SKU is filled with {@code '2' + an 11-digit per-business number}
 * ({@code 200000000001}), Lightspeed's 12-digit format. The leading 2 keeps it out of the range manufacturers' retail
 * codes use, so an auto code never collides with a scanned EAN/UPC. The shop may still type its own SKU or change it.
 *
 * <p>The number comes from {@link DocumentNumberService} (counter {@value #DOC_TYPE}), the same lock-serialised per-org
 * counter invoices use, so two people saving at once never draw the same one. A number that is already a code in this
 * business — a typed SKU, a manufacturer barcode, or a U7 sticker — is skipped: the till's scan resolves all three, and
 * a sticker WINS, so an automatic SKU equal to one would sell the sticker's product (SKU-AUTO-3 review). Must run inside the caller's transaction ({@code next} is MANDATORY) and immediately before the
 * insert — the counter row stays locked until commit (allocate LATE).
 */
@Component
@RequiredArgsConstructor
public class SkuAllocator {

    public static final String DOC_TYPE = "SKU";
    /** A guard, not a limit anyone should reach: that many consecutive numbers already typed by hand is a data fault. */
    static final int MAX_SKIPS = 1000;

    private final DocumentNumberService documentNumbers;
    private final ProductRepository productRepository;
    private final com.myplus.catalog.repository.ProductBarcodeRepository stickers;

    /** The 12-digit form of counter value {@code n}. */
    public static String format(long n) {
        return "2" + String.format("%011d", n);
    }

    /** The next free automatic SKU for this business; {@code null} for a legacy row with no organisation. */
    public String next(Long orgId, Long userId) {
        if (orgId == null) return null;
        for (int i = 0; i < MAX_SKIPS; i++) {
            String code = format(documentNumbers.next(orgId, DOC_TYPE));
            if (!productRepository.existsCodeScoped(code, orgId, userId)
                    && stickers.findByOrganizationIdAndBarcode(orgId, code).isEmpty()) return code;
        }
        throw new IllegalStateException("SKU-AUTO: " + MAX_SKIPS + " consecutive automatic SKUs are already in use for org "
                + orgId + " - check the products' SKUs");
    }
}
