package com.myplus.marketplace.multiseller.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-3a (R4.2) — the MaxTheService warehouse: one organisation on the existing inventory, named by the operator, whose
 * offers are PLATFORM stock (owner MaxTheService, custodian and fulfiller the warehouse) and read "Sold and shipped by
 * MaxTheService". Its stock, holds, sale and invoice are that organisation's, through the same paths as any seller.
 *
 * <p>Only an approved seller with NO offers can be named: an existing shop's offers would silently become
 * MaxTheService's. The warehouse cannot be changed or cleared while it has offers live or waiting for approval, so a
 * PLATFORM offer never outlives the warehouse that sells it.
 */
@Service
@RequiredArgsConstructor
public class PlatformWarehouseService {

    /** What customers see as the seller of platform stock. No other seller may take this name. */
    public static final String NAME = "MaxTheService";

    private static final int MAX_CANDIDATES = 200;

    private final MarketplaceSettingsService settings;
    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplaceOfferRepository offers;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;

    @Transactional(readOnly = true)
    public SellerDTOs.Warehouse view() {
        access.assertOperator();
        Long current = settings.warehouseOrg().orElse(null);
        List<SellerDTOs.Account> candidates = new ArrayList<>();
        for (MarketplaceSellerAccount a : accounts.findByStatusOrderByAppliedAtAsc(SellerAccount.APPROVED.name(),
                PageRequest.of(0, MAX_CANDIDATES)).getContent())
            if (Objects.equals(a.getOrganizationId(), current) || offers.findByOrganizationId(a.getOrganizationId()).isEmpty())
                candidates.add(MarketplaceSellerService.toDto(a));
        return new SellerDTOs.Warehouse(current,
                current == null ? null : accounts.findByOrganizationId(current).map(MarketplaceSellerAccount::getDisplayName).orElse(null),
                current == null ? 0 : liveOffers(current), candidates);
    }

    @Transactional
    public SellerDTOs.Warehouse set(SellerDTOs.WarehouseRequest req) {
        access.assertOperator();
        Long org = req == null ? null : req.organizationId();
        Long current = settings.warehouseOrg().orElse(null);
        if (Objects.equals(org, current)) return view();
        // the operator's choice first: a refusal names what is wrong with it, whatever the current warehouse
        if (org != null) {
            MarketplaceSellerAccount a = accounts.findByOrganizationId(org).orElse(null);
            if (a == null || !SellerAccount.APPROVED.name().equals(a.getStatus()))
                throw new ValidationException("Choose an approved seller account.");
            if (!offers.findByOrganizationId(org).isEmpty())
                throw new ValidationException("This organisation already has offers of its own. Choose one with none: "
                        + "its offers would become MaxTheService's.");
        }
        if (current != null && liveOffers(current) > 0)
            throw new ValidationException("The warehouse has offers live or waiting for approval. Suspend them before "
                    + (org == null ? "removing" : "changing") + " the warehouse.");
        settings.saveWarehouseOrg(org);
        audit.event(org == null ? "MKT_WAREHOUSE_REMOVED" : "MKT_WAREHOUSE_SET", "MKT_SETTING", "platform.warehouseOrg", org,
                MarketplaceAuditService.Actor.OPERATOR, current == null ? null : String.valueOf(current),
                org == null ? null : String.valueOf(org), null, null);
        return view();
    }

    /** Offers on the marketplace or on their way there. */
    long liveOffers(Long org) {
        return offers.findByOrganizationId(org).stream().map(MarketplaceOffer::getApprovalStatus)
                .filter(s -> Approval.APPROVED.name().equals(s) || Approval.PENDING_REVIEW.name().equals(s)).count();
    }

    /** "Max The Service", "MaxTheService Official" and the like: the platform's name is not a shop's, nor part of one. */
    public static boolean isPlatformName(String name) {
        return name != null && name.replaceAll("[\\s._-]", "").toLowerCase(java.util.Locale.ROOT)
                .contains(NAME.toLowerCase(java.util.Locale.ROOT));
    }
}
