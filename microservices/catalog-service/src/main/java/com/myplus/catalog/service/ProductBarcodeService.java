package com.myplus.catalog.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.catalog.entity.Product;
import com.myplus.catalog.entity.ProductBarcode;
import com.myplus.catalog.repository.ProductBarcodeRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.ScanResolution;

import lombok.RequiredArgsConstructor;

/**
 * U7 — the shop's own sticker codes.
 *
 * <p>Design: {@code docs/slices/u7-own-stickers.md}.
 */
@Service
@RequiredArgsConstructor
public class ProductBarcodeService {

    private final ProductBarcodeRepository barcodeRepo;
    private final ProductRepository productRepo;
    private final ProductService productService;

    /** A sticker for more than this is a typo, not a shelf label. Mirrors the guards on the sale side. */
    private static final int MAX_STICKER_QUANTITY = 10_000;

    /**
     * ⭐ Resolve a scanned code: <b>alias first, then the product's own barcode/sku.</b>
     *
     * <h3>Why alias first, and why that is safe</h3>
     * A shop's sticker has to win, or it would never fire. What makes that safe is
     * {@link #register}'s refusal to create an alias that collides with a real barcode or sku — checked in
     * both directions, because a refusal only binds new data and a product could otherwise take a code that
     * an alias already owned.
     *
     * <h3>⚠ allowLoose is re-checked HERE, not only at registration</h3>
     * A shop can switch loose selling off after a sticker exists. The sticker must stop working the moment it
     * does — otherwise a product the owner has deliberately made indivisible keeps being sold by the piece,
     * through a code nobody remembers registering.
     */
    @Transactional(readOnly = true)
    public ScanResolution scan(String code) {
        if (code == null || code.isBlank()) return ScanResolution.builder().build();
        String trimmed = code.trim();
        Long orgId = CurrentUser.organizationId();

        if (orgId != null) {
            var alias = barcodeRepo.findByOrganizationIdAndBarcode(orgId, trimmed);
            if (alias.isPresent()) {
                ProductBarcode a = alias.get();
                ProductRef ref = productService.getRef(a.getProductId());
                if (ref != null) {
                    boolean loose = "LOOSE".equalsIgnoreCase(a.getSoldUnit());
                    // The product may have stopped being divisible since this sticker was printed.
                    if (loose && !(Boolean.TRUE.equals(ref.getAllowLoose())
                            && ref.getPackSize() != null && ref.getPackSize() > 1)) {
                        throw new ValidationException(ref.getName()
                                + " is no longer sold by the piece, so this sticker cannot be used. "
                                + "Remove it, or switch loose selling back on for this product.");
                    }
                    return ScanResolution.builder()
                            .product(ref)
                            .soldUnit(loose ? "LOOSE" : "PACK")
                            .quantity(a.getQuantity() == null ? 1f : a.getQuantity().floatValue())
                            .ownSticker(true)
                            .build();
                }
            }
        }

        // Not a sticker: the ordinary answer, unchanged since before this feature existed.
        ProductRef ref = productService.lookup(trimmed);
        return ScanResolution.builder().product(ref).soldUnit("PACK").quantity(1f).ownSticker(false).build();
    }

    /** U12 — every sticker this shop has registered, for the label sheet. Org-scoped, like every read here. */
    @Transactional(readOnly = true)
    public List<ProductBarcode> forOrg() {
        Long orgId = CurrentUser.organizationId();
        if (orgId == null) return List.of();
        return barcodeRepo.findByOrganizationIdOrderByBarcodeAsc(orgId);
    }

    /** The stickers on one product, for the product form. */
    @Transactional(readOnly = true)
    public List<ProductBarcode> forProduct(Long productId) {
        Long orgId = CurrentUser.organizationId();
        if (orgId == null || productId == null) return List.of();
        return barcodeRepo.findByOrganizationIdAndProductIdOrderByBarcodeAsc(orgId, productId);
    }

    /**
     * Register a sticker.
     *
     * <h3>⚠ The refusal that matters most</h3>
     * A code already used as some product's barcode or sku CANNOT become an alias. If a manufacturer GTIN
     * were registered as "1 tablet", <b>every scan of that pack would sell one tablet</b> — the commonest
     * transaction in the shop, mis-priced, silently, until the takings looked wrong.
     *
     * <p>The database cannot express this: it spans two tables. So it is enforced here, and the gate proves
     * it, and the migration carries a comment telling the next reader not to "simplify" it away on the
     * grounds that the unique index looks sufficient.
     */
    @Transactional
    public ProductBarcode register(Long productId, String barcode, String soldUnit, Integer quantity) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        if (orgId == null) throw new ValidationException("No organisation on this session.");
        if (barcode == null || barcode.isBlank()) throw new ValidationException("Enter the code on the sticker.");
        String code = barcode.trim();

        // Anti-IDOR: a productId from a request is never trusted. The product must be THIS tenant's.
        Product product = productRepo.findById(productId)
                .filter(p -> p.getOrganizationId() == null
                        ? java.util.Objects.equals(p.getCreatedBy(), userId)
                        : p.getOrganizationId().equals(orgId))
                .orElseThrow(() -> new ValidationException("That product is not available on this account."));

        if (barcodeRepo.countProductsUsingCode(code, orgId, userId) > 0) {
            throw new ValidationException("\"" + code + "\" is already a product's barcode or SKU. "
                    + "Using it as a sticker would make every scan of that pack sell the wrong quantity.");
        }
        barcodeRepo.findByOrganizationIdAndBarcode(orgId, code).ifPresent(existing -> {
            throw new ValidationException("\"" + code + "\" is already used by another sticker. "
                    + "One code, one meaning.");
        });

        String unit = (soldUnit == null) ? "LOOSE" : soldUnit.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"PACK".equals(unit) && !"LOOSE".equals(unit)) {
            throw new ValidationException("A sticker means PACK or LOOSE — not \"" + soldUnit + "\".");
        }
        if ("LOOSE".equals(unit)
                && !(Boolean.TRUE.equals(product.getAllowLoose())
                     && product.getPackSize() != null && product.getPackSize() > 1)) {
            // The same control the till enforces. A sticker must not be a way around it.
            throw new ValidationException(product.getName() + " is not sold by the piece, so a loose sticker "
                    + "would have nothing to mean. Switch on \"may be sold loose\" first.");
        }
        int qty = (quantity == null) ? 1 : quantity;
        if (qty < 1) throw new ValidationException("A sticker means at least one. Enter a whole number above zero.");
        if (qty > MAX_STICKER_QUANTITY) {
            throw new ValidationException(qty + " looks like a typo — a single scan would ring up more than any "
                    + "shop holds.");
        }

        return barcodeRepo.save(ProductBarcode.builder()
                .organizationId(orgId)
                .barcode(code)
                .productId(product.getId())
                .soldUnit(unit)
                .quantity(qty)
                .createdBy(userId)
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** Remove a sticker. Ordinary lookup for that code resumes immediately. */
    @Transactional
    public void remove(Long id) {
        Long orgId = CurrentUser.organizationId();
        barcodeRepo.findById(id)
                .filter(b -> b.getOrganizationId() != null && b.getOrganizationId().equals(orgId))
                .ifPresent(barcodeRepo::delete);
    }

    /**
     * ⚠ The OTHER direction: may this product take this barcode/sku?
     *
     * <p>A refusal only binds new data. Without this, a product edited to take a code an alias already owns
     * would be shadowed by that alias on every scan — the same defect, arrived at from the other side.
     */
    @Transactional(readOnly = true)
    public void assertCodeFreeForProduct(String code) {
        Long orgId = CurrentUser.organizationId();
        if (orgId == null || code == null || code.isBlank()) return;
        barcodeRepo.findByOrganizationIdAndBarcode(orgId, code.trim()).ifPresent(alias -> {
            throw new ValidationException("\"" + code.trim() + "\" is already one of this shop's own stickers. "
                    + "Remove the sticker first, or use a different code.");
        });
    }
}
