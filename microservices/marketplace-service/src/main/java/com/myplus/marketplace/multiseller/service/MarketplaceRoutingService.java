package com.myplus.marketplace.multiseller.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2c — the operator's view of live routing: the limits in force, the sellers not being asked right now (an open
 * circuit), and the test switch that makes one seller slow. Every method is the operator's only.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceRoutingService {

    /** Sellers offered in the test switch's list: approved ones, oldest first, bounded. */
    static final int SELLER_CHOICES = 50;

    private final LiveRouting routing;
    private final MarketplaceSettingsService settings;
    private final MarketplaceSellerAccountRepository sellerAccounts;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;

    public MarketplaceOrderDTOs.RoutingView view() {
        access.assertOperator();
        LiveRouting.Limits l = routing.limits();
        List<MarketplaceOrderDTOs.RoutingCircuit> open = routing.openCircuits().stream()
                .map(c -> new MarketplaceOrderDTOs.RoutingCircuit(c.sellerOrganizationId(), name(c.sellerOrganizationId()),
                        c.failures(), c.until()))
                .toList();
        MarketplaceSettingsService.SlowSeller slow = l.testSwitch() ? settings.slowSeller() : null;
        List<MarketplaceOrderDTOs.RoutingSeller> sellers = !l.testSwitch() ? List.of()
                : sellerAccounts.findByStatusOrderByAppliedAtAsc(SellerAccount.APPROVED.name(), PageRequest.of(0, SELLER_CHOICES))
                        .map(a -> new MarketplaceOrderDTOs.RoutingSeller(a.getOrganizationId(), a.getDisplayName())).getContent();
        return new MarketplaceOrderDTOs.RoutingView(l.holdTimeoutMs(), l.deadlineMs(), l.breakerFailures(),
                l.breakerOpenSeconds(), open, l.testSwitch(),
                slow == null ? null : slow.sellerOrganizationId(), slow == null ? null : slow.delayMs(),
                slow == null ? null : name(slow.sellerOrganizationId()), sellers);
    }

    /** The seller says it is fixed: its next checkout is asked again at once. */
    public MarketplaceOrderDTOs.RoutingView close(Long seller) {
        access.assertOperator();
        if (seller == null) throw new ValidationException("Choose the seller.");
        if (routing.close(seller))
            audit.event("MKT_ROUTING_CIRCUIT_CLOSED", "MKT_SELLER", String.valueOf(seller), seller,
                    MarketplaceAuditService.Actor.OPERATOR, "OPEN", "CLOSED", null, null);
        return view();
    }

    /** The test switch; refused when the service was not started with it (never in production). */
    public MarketplaceOrderDTOs.RoutingView test(MarketplaceOrderDTOs.RoutingTest body) {
        access.assertOperator();
        if (!routing.limits().testSwitch())
            throw new ValidationException("The test switch is not available on this system.");
        settings.setSlowSeller(body == null ? null : body.sellerOrganizationId(), body == null ? null : body.delayMs());
        return view();
    }

    private String name(Long org) {
        return sellerAccounts.findByOrganizationId(org).map(a -> a.getDisplayName())
                .filter(n -> n != null && !n.isBlank()).orElse("Seller " + org);
    }
}
