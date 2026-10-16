package com.myplus.marketplace.multiseller.service;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-4a (R4.3, R4.4) — the operator marks an approved seller as a SUPPLIER (ruling, 2026-10-15: a supplier is a MyPlus
 * business the operator marks, not an API-only company), and says whether its stock counts are reliable.
 *
 * <p>A supplier owns, holds and ships its own stock, so its offers are SUPPLIER stock: the source follows who lists it,
 * exactly as the warehouse's offers are PLATFORM. Marking re-stamps every offer the seller already has (MERCHANT ↔
 * SUPPLIER) and re-publishes them, so the public catalogue never shows an offer under the wrong source. Orders already
 * placed keep the source their lines were snapshotted with.
 *
 * <p>Until the operator marks a supplier's counts as reliable, its offers are shown with the stock unconfirmed: the
 * customer reads that the supplier confirms when it accepts the order (R4.4: "never shown as immediately available
 * unless the supplier gives reliable availability and accepts within a defined time"; the defined time is the
 * operator's acceptance window, the same for every seller).
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplaceOfferRepository offers;
    private final MarketplaceSettingsService settings;
    private final OfferProjectionService projection;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;

    @Transactional
    public SellerDTOs.Account set(Long sellerOrg, SellerDTOs.SupplierRequest req) {
        access.assertOperator();
        if (req == null || req.supplier() == null) throw new ValidationException("Say whether this seller is a supplier.");
        MarketplaceSellerAccount a = accounts.findByOrganizationId(sellerOrg)
                .orElseThrow(() -> new ResourceNotFoundException("No seller account for that business."));
        if (req.version() != null && !req.version().equals(a.getVersion()))
            throw new OptimisticLockingFailureException("seller account changed");
        boolean supplier = req.supplier();
        boolean reliable = supplier && Boolean.TRUE.equals(req.stockReliable());
        if (supplier && !SellerAccount.APPROVED.name().equals(a.getStatus()))
            throw new ValidationException("Only an approved seller can be a supplier.");
        if (supplier && settings.warehouseOrg().filter(sellerOrg::equals).isPresent())
            throw new ValidationException("The MaxTheService warehouse sells MaxTheService's own stock, not a supplier's.");

        String before = describe(a);
        boolean sourceChanges = supplier != Boolean.TRUE.equals(a.getSupplier());
        a.setSupplier(supplier);
        a.setSupplierStockReliable(reliable);
        MarketplaceSellerAccount saved = accounts.saveAndFlush(a);

        if (sourceChanges) offers.findByOrganizationId(sellerOrg).forEach(o -> restamp(o, supplier));
        projection.publishSeller(sellerOrg);   // the source and whether its stock is confirmed, both read by the catalogue
        settings.saveSupplierStock(accounts.existsBySupplierTrue());
        audit.event("MKT_SUPPLIER_SET", "MKT_SELLER", String.valueOf(sellerOrg), sellerOrg, MarketplaceAuditService.Actor.OPERATOR,
                before, describe(saved), null, null);
        return MarketplaceSellerService.toDto(saved);
    }

    /** MERCHANT ↔ SUPPLIER only: any other source (PLATFORM, CONSIGNMENT) is not the supplier mark's to change. */
    private void restamp(MarketplaceOffer o, boolean supplier) {
        String from = supplier ? StockSourceType.MERCHANT.name() : StockSourceType.SUPPLIER.name();
        String current = o.getStockSourceType() == null ? StockSourceType.MERCHANT.name() : o.getStockSourceType();
        if (!from.equals(current)) return;
        o.setStockSourceType((supplier ? StockSourceType.SUPPLIER : StockSourceType.MERCHANT).name());
        offers.save(o);
    }

    private static String describe(MarketplaceSellerAccount a) {
        if (!Boolean.TRUE.equals(a.getSupplier())) return "seller";
        return Boolean.TRUE.equals(a.getSupplierStockReliable()) ? "supplier, reliable stock" : "supplier";
    }
}
