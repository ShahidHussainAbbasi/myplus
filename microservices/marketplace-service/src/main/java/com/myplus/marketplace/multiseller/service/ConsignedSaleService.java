package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.PurchaseRecordRequest;
import com.myplus.commerce.contracts.dto.PurchaseRecordResult;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.CommissionPolicy;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Match;
import com.myplus.marketplace.multiseller.domain.StockSourceType;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.support.AsOrg;
import com.myplus.marketplace.support.DownstreamMessage;

import lombok.RequiredArgsConstructor;

/**
 * MKT-5c — the MaxTheService warehouse sells consigned stock (R2.1, R4.5, R15.4, R20.5). Slice doc
 * {@code docs/slices/mkt-5c-consigned-sale.md}.
 *
 * <h3>The listing</h3>
 * The warehouse is the consignee and so the seller (R2.1): consigned stock is a CONSIGNMENT offer OF THE WAREHOUSE,
 * owner = the consignor, custodian = seller = fulfiller = the warehouse, one per owner and product (V42). Shoppers see
 * "MaxTheService"; the owner is never a public field. The party the agreement names sets the price
 * ({@code priceSetBy}): that party, and only that party, lists it and changes its terms ({@link #list}). The agreement
 * is MaxTheService's own approval, so the listing is live at once, inside the operator's price limits; the operator can
 * still suspend it like any offer.
 *
 * <h3>What it can sell</h3>
 * Not inventory: the units are set aside in the OWNER's books (5b). What the listing can promise is the ledger balance
 * less the units of orders still waiting for the warehouse. Checkout counts it again under the agreement row's lock
 * ({@link #unpromised}), so two shoppers are never promised the same unit.
 *
 * <h3>The sale (ownership passes on sale)</h3>
 * When the warehouse accepts, before its own sale to the shopper, each consigned line's units move from the owner's
 * books into the warehouse's ({@link #sell}), lot by lot (each transfer received is a lot with its own hold): the lot's
 * hold is let go, the owner sells the units to MaxTheService at the selling price less the agreement's commission, the
 * rest of the lot is set aside again under the same key, and the warehouse buys them on the owner's invoice. Then the
 * warehouse's sale to the shopper takes them from its own shelf, as for its own stock. Every step is keyed by order
 * line, lot and attempt, and the SOLD row is written last, so an Accept retried after a failure replays instead of
 * selling twice; a purchase refused after the owner's sale voids that sale and the next try is a new attempt.
 *
 * <h3>The money</h3>
 * The order line carries the agreement's commission as its snapshot (basis ITEMS), and settles to the OWNER after
 * delivery and the return window (MarketplaceSettlementService: the payee of a CONSIGNMENT line is its owner, and
 * MaxTheService collected the money, cash or card).
 */
@Service
@RequiredArgsConstructor
public class ConsignedSaleService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsignedSaleService.class);

    static final String ON_SALE = "ON_SALE";
    static final String CONSIGNOR = "CONSIGNOR";
    static final int DEFAULT_PROMISE_HOURS = 48;

    private final MarketplaceOfferRepository offers;
    private final MarketplaceOfferProjectionRepository projections;
    private final MarketplaceProductRepository products;
    private final MarketplaceProductSourceRepository sources;
    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplaceConsignedStockRepository consigned;
    private final MarketplaceConsignmentAgreementRepository agreements;
    private final MarketplaceOrderLineRepository lines;
    private final ConsignmentAgreementService consignments;
    private final MarketplaceSettingsService settings;
    private final MarketplaceSellerService sellers;
    private final MarketplacePolicyService policies;
    private final OfferProjectionService projection;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;
    private final TradeClient trade;
    private final PlatformTransactionManager txManager;

    // ── the listing ───────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public TransferDTOs.Listing list(TransferDTOs.ListRequest req) {
        sellers.assertActiveSeller();
        if (req == null || req.mktProductId() == null) throw new ValidationException("Choose the product.");
        Long org = access.org();
        Long warehouse = settings.warehouseOrg()
                .orElseThrow(() -> new ValidationException("MaxTheService has no warehouse, so nothing consigned can be sold."));
        Long owner = warehouse.equals(org) ? req.consignorOrgId() : org;
        if (owner == null || owner.equals(warehouse)) throw new ValidationException("Choose whose consigned stock to list.");
        MarketplaceConsignmentAgreement a = consignments.activeFor(owner, warehouse)
                .orElseThrow(() -> new ValidationException("There is no active consignment agreement for this stock, so it cannot be listed."));
        if (!ON_SALE.equals(a.getOwnershipTransfer()))
            throw new ValidationException("Under " + a.getAgreementNo() + " ownership passes on delivery. Selling consigned "
                    + "stock on those terms is not available yet: MaxTheService can offer terms where it passes on sale.");
        boolean ownerPrices = CONSIGNOR.equals(a.getPriceSetBy());
        if (ownerPrices != org.equals(owner))
            throw new ValidationException("Under " + a.getAgreementNo() + ", " + (ownerPrices ? name(owner) : PlatformWarehouseService.NAME)
                    + " sets the selling price.");
        MarketplaceProduct product = products.findById(req.mktProductId())
                .orElseThrow(() -> new ValidationException("No such marketplace product."));
        if (consigned.balance(owner, warehouse, product.getId()) <= 0)
            throw new ValidationException("MaxTheService holds none of " + product.getCanonicalName() + " for " + name(owner) + ".");
        MarketplaceProductSource shelf = sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(warehouse, product.getId(),
                        Match.MATCHED.name())
                .orElseThrow(() -> new ValidationException("MaxTheService no longer carries this product, so it cannot sell it."));

        MarketplaceOffer o = offers.findByOrganizationIdAndMktProductIdAndStockOwnerOrganizationIdAndStockSourceType(
                warehouse, product.getId(), owner, StockSourceType.CONSIGNMENT.name()).orElse(null);
        boolean first = o == null;
        if (first) {
            o = new MarketplaceOffer();
            o.setOrganizationId(warehouse);
            o.setMktProductId(product.getId());
            o.setPaused(false);
        } else if (req.version() != null && !req.version().equals(o.getVersion())) {
            throw new ValidationException("This listing changed meanwhile. Refresh and try again.");
        }
        BigDecimal before = o.getMarketplacePrice();
        if (req.price() != null) {
            if (req.price().signum() <= 0) throw new ValidationException("Set a selling price above zero.");
            if (req.price().scale() > 2) throw new ValidationException("A price has at most two decimal places.");
            o.setMarketplacePrice(req.price().setScale(2, RoundingMode.UNNECESSARY));
        }
        if (o.getMarketplacePrice() == null) throw new ValidationException("Set a selling price above zero.");
        MarketplaceOfferService.priceWithinLimits(o.getMarketplacePrice(), product);
        if (req.deliveryAreas() != null) o.setDeliveryAreas(MarketplaceOfferService.areas(req.deliveryAreas()));
        if (o.getDeliveryAreas() == null) throw new ValidationException("Name at least one city it is delivered to.");
        if (req.promiseHours() != null) {
            if (req.promiseHours() < 1 || req.promiseHours() > 720)
                throw new ValidationException("The delivery promise must be between 1 and 720 hours.");
            o.setPromiseHours(req.promiseHours());
        }
        if (o.getPromiseHours() == null) o.setPromiseHours(DEFAULT_PROMISE_HOURS);
        if (req.warrantyPolicyId() != null) o.setWarrantyPolicyId(policies.usable(req.warrantyPolicyId(), MarketplacePolicy.WARRANTY).getId());
        if (req.returnPolicyId() != null) o.setReturnPolicyId(policies.usable(req.returnPolicyId(), MarketplacePolicy.RETURN).getId());
        if (o.getWarrantyPolicyId() == null || o.getReturnPolicyId() == null)
            throw new ValidationException("Choose a warranty and a return policy.");

        // parties: the warehouse sells, holds and ships; the consignor owns (R4.5). The commission is the agreement's,
        // taken at checkout, so the offer carries no commission policy of its own.
        o.setSourceProductId(shelf.getSourceProductId());
        o.setStockSourceType(StockSourceType.CONSIGNMENT.name());
        o.setSellerOrganizationId(warehouse);
        o.setStockOwnerOrganizationId(owner);
        o.setCustodianOrganizationId(warehouse);
        o.setFulfillerOrganizationId(warehouse);
        o.setCommissionPolicyId(null);
        if (!Approval.SUSPENDED.name().equals(o.getApprovalStatus())) {
            // the agreement is MaxTheService's approval; a listing the operator suspended stays suspended
            o.setApprovalStatus(Approval.APPROVED.name());
            o.setReviewNote("Consigned under " + a.getAgreementNo());
            o.setReviewedByUserId(access.userId());
            o.setReviewedAt(LocalDateTime.now());
            if (o.getPublishedAt() == null) o.setPublishedAt(LocalDateTime.now());
        }
        o = offers.save(o);
        projection.publish(o);
        audit.event(first ? "MKT_CONSIGN_LISTED" : "MKT_CONSIGN_RELISTED", "MKT_OFFER", String.valueOf(o.getId()), owner,
                MarketplaceAuditService.Actor.SELLER, before == null ? null : before.toPlainString(),
                o.getMarketplacePrice().toPlainString(), o.getMarketplacePrice(), a.getAgreementNo());
        return listing(o, a.getAgreementNo());
    }

    /**
     * The held rows decorated for {@code org}'s screen (5b's table): units sold, whether {@code org} may list, and the
     * listing. One agreement read per owner, one offer read per row: bounded by what the ledger holds.
     */
    public List<TransferDTOs.Held> decorate(Long org, Long warehouse, List<Object[]> rows, Function<Long, String> names) {
        List<TransferDTOs.Held> out = new ArrayList<>();
        Map<Long, Optional<MarketplaceConsignmentAgreement>> byOwner = new HashMap<>();
        for (Object[] r : rows) {
            Long owner = (Long) r[0];
            Long product = (Long) r[1];
            Optional<MarketplaceConsignmentAgreement> a = byOwner.computeIfAbsent(owner, k -> consignments.activeFor(k, warehouse));
            boolean canList = a.filter(x -> ON_SALE.equals(x.getOwnershipTransfer()))
                    .map(x -> CONSIGNOR.equals(x.getPriceSetBy()) == org.equals(owner)).orElse(false);
            TransferDTOs.Listing l = offers.findByOrganizationIdAndMktProductIdAndStockOwnerOrganizationIdAndStockSourceType(
                    warehouse, product, owner, StockSourceType.CONSIGNMENT.name())
                    .map(o -> listing(o, a.map(MarketplaceConsignmentAgreement::getAgreementNo).orElse(null))).orElse(null);
            out.add(new TransferDTOs.Held(names.apply(owner), product, (String) r[2], ((Number) r[3]).longValue(),
                    r[4] == null ? BigDecimal.ZERO : new BigDecimal(r[4].toString()).setScale(2, RoundingMode.HALF_UP),
                    owner, r.length > 5 && r[5] != null ? ((Number) r[5]).longValue() : 0, canList, l));
        }
        return out;
    }

    private TransferDTOs.Listing listing(MarketplaceOffer o, String agreementNo) {
        boolean live = projections.findById(o.getId()).map(p -> MarketplaceOfferProjection.LIVE.equals(p.getStatus())).orElse(false);
        return new TransferDTOs.Listing(o.getId(), o.getVersion(), o.getMarketplacePrice(), live, projection.consignedAvailable(o),
                o.getDeliveryAreas(), o.getPromiseHours(), o.getWarrantyPolicyId(), o.getReturnPolicyId(), agreementNo);
    }

    /** Re-count the listings of these lines' consigned stock now, rather than at the next sweep. Best effort. */
    public void resync(List<MarketplaceOrderLine> ls) {
        Set<Long> done = new HashSet<>();
        for (MarketplaceOrderLine l : ls)
            if (StockSourceType.CONSIGNMENT.name().equals(l.getStockSourceType()) && done.add(l.getOfferId())) sync(l.getOfferId());
    }

    /** As {@link #resync}, for one owner's product at the warehouse (5b: stock was just received). */
    public void resync(Long owner, Long warehouse, Long mktProductId) {
        offers.findByOrganizationIdAndMktProductIdAndStockOwnerOrganizationIdAndStockSourceType(warehouse, mktProductId, owner,
                StockSourceType.CONSIGNMENT.name()).ifPresent(o -> sync(o.getId()));
    }

    private void sync(Long offerId) {
        try {
            projection.syncStock(offerId);
        } catch (RuntimeException e) {
            LOG.warn("MKT-5c: listing {} not re-counted now; the sweep does it: {}", offerId, e.toString());
        }
    }

    // ── checkout ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The order line's commission is the AGREEMENT's (R2.2: the terms in force), copied like any snapshot. Called by
     * the checkout snapshot for a CONSIGNMENT line; a line with no agreement in force keeps none (its checkout is then
     * refused by {@link #unpromised}).
     */
    public void terms(MarketplaceOrderLine l) {
        consignments.activeFor(l.getStockOwnerOrganizationId(), l.getCustodianOrganizationId()).ifPresent(a -> {
            l.setCommissionPolicyId(null);
            l.setCommissionBasis(CommissionPolicy.Basis.ITEMS.name());
            l.setCommissionRate(a.getCommissionPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            l.setCommissionFixed(null);
        });
    }

    /**
     * The parts whose consigned lines cannot all be promised: for each owner's product in the order, under that
     * owner's agreement row locked, the ledger balance must cover every unit promised to orders still waiting — this
     * order's own lines among them, as they were just written. Parts with no consigned line are never in the answer.
     */
    public Set<Long> unpromised(List<MarketplaceSellerOrder> parts, Function<Long, List<MarketplaceOrderLine>> linesOf) {
        record Key(Long owner, Long custodian, Long product) { }
        Map<Key, Set<Long>> partsOf = new LinkedHashMap<>();
        for (MarketplaceSellerOrder so : parts)
            for (MarketplaceOrderLine l : linesOf.apply(so.getId()))
                if (StockSourceType.CONSIGNMENT.name().equals(l.getStockSourceType()))
                    partsOf.computeIfAbsent(new Key(l.getStockOwnerOrganizationId(), l.getCustodianOrganizationId(), l.getMktProductId()),
                            k -> new HashSet<>()).add(so.getId());
        Set<Long> refused = new HashSet<>();
        for (Map.Entry<Key, Set<Long>> e : partsOf.entrySet()) {
            Key k = e.getKey();
            Boolean ok = new TransactionTemplate(txManager).execute(s -> {
                MarketplaceConsignmentAgreement a = consignments.activeFor(k.owner(), k.custodian()).orElse(null);
                if (a == null || agreements.lockById(a.getId()).isEmpty()) return false;
                return lines.consignedPromised(k.owner(), k.custodian(), k.product()) <= consigned.balance(k.owner(), k.custodian(), k.product());
            });
            if (!Boolean.TRUE.equals(ok)) refused.addAll(e.getValue());
        }
        return refused;
    }

    // ── the sale ──────────────────────────────────────────────────────────────────────────────────────

    /** One lot still held: the transfer it came in on, its hold in the owner's books, the owner's product, units left. */
    record Lot(Long transferId, String holdKey, Long ownerProductId, int left, BigDecimal unitValue) { }

    /**
     * Before the warehouse's own sale: every consigned line's units move from the owner's books into the warehouse's.
     * Throws, with the reason, when they cannot; lines and lots already moved stay moved, and a retry carries on from
     * there. Not transactional: each ledger row is written on its own, after the books it records.
     */
    public void sell(MarketplaceSellerOrder so, String orderNo, List<MarketplaceOrderLine> ls, Map<Long, List<String>> serials) {
        for (MarketplaceOrderLine l : ls) {
            if (!StockSourceType.CONSIGNMENT.name().equals(l.getStockSourceType())) continue;
            List<MarketplaceConsignedStock> done = consigned.findByOrderLineIdAndMovement(l.getId(), MarketplaceConsignedStock.SOLD);
            int moved = done.stream().mapToInt(m -> -m.getQuantity()).sum();
            int need = l.getQuantity() - moved;
            if (need <= 0) continue;
            Long owner = l.getStockOwnerOrganizationId(), warehouse = l.getCustodianOrganizationId();
            MarketplaceConsignmentAgreement a = consignments.activeFor(owner, warehouse)
                    .orElseThrow(() -> new ValidationException("There is no active consignment agreement with " + name(owner)
                            + " any more, so its stock cannot be sold. Reject the order and say why."));
            List<Lot> lots = lots(owner, warehouse, l.getMktProductId());
            // MKT-5d: units a count found missing are still in the lots (the owner's books) but not on the shelf
            int held = (int) Math.min(lots.stream().mapToInt(Lot::left).sum(), consigned.balance(owner, warehouse, l.getMktProductId()));
            if (held < need)
                throw new ValidationException("MaxTheService holds only " + held + " × " + l.getProductName() + " for " + name(owner)
                        + ", so this order cannot be accepted. Reject it and say why.");
            BigDecimal net = owed(l);
            Set<String> used = new HashSet<>();
            for (MarketplaceConsignedStock m : done) if (m.getSerials() != null) used.addAll(List.of(m.getSerials().split(",")));
            List<String> free = new ArrayList<>();
            List<String> given = serials == null ? null : SellerOrderService.clean(serials.get(l.getId()));
            if (given != null) for (String s : given) if (!used.contains(s)) free.add(s);
            for (Lot lot : lots) {
                if (need == 0) break;
                int take = Math.min(need, lot.left());
                List<String> mine = free.size() >= take ? new ArrayList<>(free.subList(0, take)) : List.of();
                free.removeAll(mine);
                moveLot(so, orderNo, l, a, lot, take, net, mine.isEmpty() ? null : String.join(",", mine));
                need -= take;
            }
        }
    }

    /** What MaxTheService owes the owner a unit: the line's price less the commission it was sold under, to the paisa. */
    static BigDecimal owed(MarketplaceOrderLine l) {
        BigDecimal rate = l.getCommissionRate() == null ? BigDecimal.ZERO : l.getCommissionRate();
        return l.getUnitPrice().subtract(l.getUnitPrice().multiply(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    List<Lot> lots(Long owner, Long warehouse, Long mktProductId) {
        List<Lot> out = new ArrayList<>();
        for (Object[] r : consigned.lots(owner, warehouse, mktProductId))
            out.add(new Lot((Long) r[0], (String) r[1], (Long) r[2], ((Number) r[3]).intValue(),
                    r[4] == null ? BigDecimal.ZERO : new BigDecimal(r[4].toString())));
        return out;
    }

    /** One lot's share of one line: let go, the owner's sale, set the rest aside again, the warehouse's purchase, the row. */
    private void moveLot(MarketplaceSellerOrder so, String orderNo, MarketplaceOrderLine l, MarketplaceConsignmentAgreement a,
                         Lot lot, int take, BigDecimal net, String serials) {
        Long owner = l.getStockOwnerOrganizationId(), warehouse = l.getCustodianOrganizationId();
        int attempt = (int) consigned.countByTransferIdAndOrderLineIdAndMovement(lot.transferId(), l.getId(), MarketplaceConsignedStock.VOIDED);
        String key = "MKT-SO-" + so.getId() + "-L" + l.getId() + "-T" + lot.transferId() + "-" + attempt;
        String ownerName = name(owner);

        try {
            AsOrg.run(owner, () -> trade.releaseHold(lot.holdKey()));
        } catch (RuntimeException e) {
            throw new ValidationException(ownerName + "'s consigned stock could not be let go in its books, so the order was not "
                    + "accepted. " + DownstreamMessage.orElse(e, "Please try again."));
        }
        SaleRecordResult sale;
        try {
            sale = AsOrg.call(owner, () -> trade.recordSale(SaleRecordRequest.builder()
                    .idempotencyKey(key + "-sale")
                    .organizationId(owner)
                    .channel("MARKETPLACE")
                    .customer(SaleRecordRequest.Customer.builder().name(PlatformWarehouseService.NAME)
                            .contact(StockTransferService.BUYER_CONTACT).build())
                    .lines(List.of(SaleRecordRequest.Line.builder()
                            .productId(lot.ownerProductId())
                            .quantity(BigDecimal.valueOf(take))
                            .unitPrice(net)
                            .description(l.getProductName())
                            .serials(serials)
                            .build()))
                    .notes("Consigned stock sold by MaxTheService, order " + orderNo + ", under " + a.getAgreementNo())
                    .build()));
            if (sale == null || sale.getInvoiceNo() == null) throw new ValidationException("No invoice was returned.");
        } catch (RuntimeException e) {
            setAside(owner, lot, lot.left());
            throw new ValidationException(ownerName + "'s sale to MaxTheService could not be recorded, so the order was not "
                    + "accepted. " + DownstreamMessage.orElse(e, "Please try again."));
        }
        if (lot.left() > take) setAside(owner, lot, lot.left() - take);

        PurchaseRecordResult bought;
        try {
            // the warehouse's own identity, as in 3c: the person accepting IS the warehouse
            bought = trade.recordPurchase(PurchaseRecordRequest.builder()
                    .idempotencyKey(key + "-purchase")
                    .organizationId(warehouse)
                    .vendorName(ownerName)
                    .productId(l.getSourceProductId())
                    .quantity(BigDecimal.valueOf(take))
                    .unitCost(net)
                    .serials(serials)
                    .billNo(sale.getInvoiceNo())
                    .build());
            if (bought == null || bought.getPurchaseId() == null) throw new ValidationException("No purchase was returned.");
        } catch (RuntimeException e) {
            LOG.warn("MKT-5c: purchase for {} refused, voiding {}'s invoice {}: {}", key, owner, sale.getInvoiceNo(), e.toString());
            AsOrg.run(owner, () -> trade.reverseSale(sale.getInvoiceNo(), "Consigned stock for " + orderNo + " not bought"));
            row(l, a, lot, MarketplaceConsignedStock.VOIDED, 0, attempt, null, sale.getInvoiceNo(), null, net);
            setAside(owner, lot, lot.left());
            throw new ValidationException("MaxTheService's purchase could not be recorded, so the order was not accepted and "
                    + ownerName + "'s sale was cancelled. " + DownstreamMessage.orElse(e, e.getMessage() == null ? "" : e.getMessage()));
        }
        row(l, a, lot, MarketplaceConsignedStock.SOLD, -take, attempt, serials, sale.getInvoiceNo(), bought.getPurchaseId(), net);
        audit.event("MKT_CONSIGN_SOLD", "MKT_ORDER_LINE", orderNo, owner, MarketplaceAuditService.Actor.SELLER, null,
                MarketplaceConsignedStock.SOLD, net.multiply(BigDecimal.valueOf(take)),
                take + " × " + l.getProductName() + ", " + sale.getInvoiceNo() + ", purchase " + bought.getPurchaseId());
    }

    /** Set {@code units} of the lot aside again under its own key; a hold that cannot be taken is logged, not fatal. */
    void setAside(Long owner, Lot lot, int units) {
        if (units <= 0) return;
        StockHoldRequest hold = StockHoldRequest.builder()
                .organizationId(owner)
                .holdKey(lot.holdKey())
                .lines(List.of(new StockReservationLine(lot.ownerProductId(), BigDecimal.valueOf(units))))
                .holdKind(StockReservationRequest.HoldKind.CONSIGNMENT)
                .build();
        try {
            StockHoldResponse r = AsOrg.call(owner, () -> trade.holdStock(hold));
            if (r == null || !r.isHeld())
                LOG.warn("MKT-5: {} units of lot {} not set aside again in org {}'s books: {}", units, lot.holdKey(), owner,
                        r == null ? "no answer" : r.getReason());
        } catch (RuntimeException e) {
            LOG.warn("MKT-5: {} units of lot {} not set aside again in org {}'s books: {}", units, lot.holdKey(), owner, e.toString());
        }
    }

    private void row(MarketplaceOrderLine l, MarketplaceConsignmentAgreement a, Lot lot, String movement, int qty, int attempt,
                     String serials, String invoiceNo, Long purchaseId, BigDecimal net) {
        MarketplaceConsignedStock m = new MarketplaceConsignedStock();
        m.setAgreementId(a.getId());
        m.setConsignorOrgId(l.getStockOwnerOrganizationId());
        m.setConsigneeOrgId(l.getCustodianOrganizationId());
        m.setMktProductId(l.getMktProductId());
        m.setConsignorProductId(lot.ownerProductId());
        m.setProductName(l.getProductName());
        m.setMovement(movement);
        m.setQuantity(qty);
        m.setUnitValue(lot.unitValue());
        m.setSerials(serials);
        m.setTransferId(lot.transferId());
        m.setHoldKey(lot.holdKey());
        m.setOrderLineId(l.getId());
        m.setAttempt(attempt);
        m.setSaleInvoiceNo(invoiceNo);
        m.setPurchaseId(purchaseId);
        m.setUnitPrice(net);
        m.setCreatedByUserId(access.userId());
        consigned.save(m);
    }

    String name(Long org) {
        return accounts.findByOrganizationId(org).map(x -> x.getDisplayName()).filter(n -> n != null && !n.isBlank())
                .orElse("Seller " + org);
    }
}
