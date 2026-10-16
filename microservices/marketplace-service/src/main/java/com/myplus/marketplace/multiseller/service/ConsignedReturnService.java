package com.myplus.marketplace.multiseller.service;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedReturn;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentCountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.support.AsOrg;
import com.myplus.marketplace.support.DownstreamMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MKT-5e — unsold consigned stock goes back to its owner (R20.5 "unsold-stock return", R4.6 the agreement's
 * {@code unsoldReturnDays} and {@code unsoldReturnBearer}). Slice doc {@code docs/slices/mkt-5e-unsold-returns.md}.
 *
 * <h3>Who starts it</h3>
 * The OWNER asks for units back at any time: it owns them. If they have been held longer than the agreement's
 * unsold-return term, the return is at the cost of the party the agreement names; asked for earlier, at the owner's.
 * The WAREHOUSE sends back only units held longer than the term (lot by lot, as each came in), at the agreement's
 * party's cost. Either way only units on the shelf and not promised to an order can go, under the agreement's lock
 * (the same lock checkout counts under), and from that moment they are no longer sold.
 *
 * <h3>How it ends</h3>
 * The warehouse hands them over (DISPATCHED) with the carrier's reference and what it cost; a cost the owner bears is
 * debited to its settlement account then, the return number its reference. The owner confirms it has them (RECEIVED):
 * its hold on them is let go, lot by lot, oldest first (the units never left its books, so nothing is bought or sold),
 * and RETURNED rows leave the ledger. Once nothing is held, the agreement can end.
 */
@Service
@RequiredArgsConstructor
public class ConsignedReturnService {

    static final int MAX_UNITS = 100_000;
    static final BigDecimal MAX_COST = new BigDecimal("100000.00");
    static final String CANCEL = "CANCEL";
    static final String DISPATCH = "DISPATCH";
    static final String RECEIVE = "RECEIVE";
    static final String CONSIGNOR = "CONSIGNOR";

    private final MarketplaceConsignedReturnRepository returns;
    private final MarketplaceConsignedStockRepository consigned;
    private final MarketplaceConsignmentCountRepository counts;
    private final MarketplaceConsignmentAgreementRepository agreements;
    private final MarketplaceOrderLineRepository lines;
    private final MarketplaceProductRepository products;
    private final ConsignmentAgreementService consignments;
    private final ConsignedSaleService sale;
    private final MarketplaceSettingsService settings;
    private final MarketplaceSettlementService settlement;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;
    private final TradeClient trade;
    private final PlatformTransactionManager txManager;

    // ── asking for it back ────────────────────────────────────────────────────────────────────────────

    /** The owner asks for units back, or the warehouse sends back units past the term. A repeat with the same key replays. */
    public TransferDTOs.ReturnRow request(TransferDTOs.ReturnRequest req) {
        Long org = access.org();
        Long warehouse = warehouse();
        boolean byWarehouse = warehouse.equals(org);
        if (req == null || req.mktProductId() == null) throw new ValidationException("Choose the stock that goes back.");
        Long owner = byWarehouse ? req.consignorOrgId() : org;
        if (owner == null || owner.equals(warehouse)) throw new ValidationException("Choose whose stock goes back.");
        int units = req.units() == null ? 0 : req.units();
        if (units < 1 || units > MAX_UNITS) throw new ValidationException("Enter how many units go back, 1 or more.");
        String key = req.idempotencyKey() == null ? "" : req.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > 64) throw new ValidationException("The request is missing its idempotency key.");
        Long product = req.mktProductId();

        MarketplaceConsignedReturn saved = new TransactionTemplate(txManager).execute(s -> {
            Optional<MarketplaceConsignedReturn> again = returns.findByRequestedByOrgIdAndIdempotencyKey(org, key);
            if (again.isPresent()) return again.get();
            MarketplaceConsignmentAgreement a = consignments.activeFor(owner, warehouse)
                    .orElseThrow(() -> new ValidationException("There is no active consignment agreement for this stock, so "
                            + "it cannot be sent back here. Ask MaxTheService."));
            agreements.lockById(a.getId());   // one checkout, count or return of this owner's stock at a time
            long returnable = returnable(owner, warehouse, product);
            if (returnable == 0)
                throw new ValidationException("Nothing of this product can go back now: " + PlatformWarehouseService.NAME
                        + " holds none for " + sale.name(owner) + " that is on the shelf and not promised to an order.");
            if (units > returnable)
                throw new ValidationException("Only " + returnable + " can go back now: the rest is promised to orders, counted "
                        + "missing or already going back.");
            long due = due(a, owner, warehouse, product, returnable);
            if (byWarehouse && units > due)
                throw new ValidationException(due == 0
                        ? "None of " + sale.name(owner) + "'s stock of this has been held " + a.getUnsoldReturnDays()
                                + " days yet (the agreement's term), so it cannot be sent back. The owner can ask for it."
                        : "Only " + due + (due == 1 ? " has" : " have") + " been held longer than " + a.getUnsoldReturnDays()
                                + " days (the agreement's term), so only " + (due == 1 ? "it" : "they") + " can be sent back.");
            MarketplaceConsignedReturn r = new MarketplaceConsignedReturn();
            r.setAgreementId(a.getId());
            r.setConsignorOrgId(owner);
            r.setConsigneeOrgId(warehouse);
            r.setMktProductId(product);
            r.setProductName(products.findById(product).map(p -> p.getCanonicalName()).orElse("Product " + product));
            r.setUnits(units);
            r.setReason(byWarehouse ? MarketplaceConsignedReturn.UNSOLD : MarketplaceConsignedReturn.RECALL);
            // past the term the agreement says who pays; asked for earlier, the owner does
            r.setBearer(byWarehouse || units <= due ? a.getUnsoldReturnBearer() : CONSIGNOR);
            r.setNote(clip(req.note(), 300));
            r.setStatus(MarketplaceConsignedReturn.REQUESTED);
            r.setRequestedByOrgId(org);
            r.setIdempotencyKey(key);
            r.setRequestedByUserId(access.userId());
            r.setRequestedAt(LocalDateTime.now());
            r = returns.save(r);
            audit.event("MKT_CONSIGN_RETURN_ASKED", "MKT_CONSIGNED_RETURN", r.getReturnNo(), owner,
                    MarketplaceAuditService.Actor.SELLER, null, MarketplaceConsignedReturn.REQUESTED, null,
                    units + " × " + r.getProductName() + ", " + r.getReason() + ", cost: " + r.getBearer());
            return r;
        });
        sale.resync(owner, warehouse, product);
        return row(saved);
    }

    /** On the shelf, not promised to an order waiting, not counted missing, not already going back. */
    long returnable(Long owner, Long warehouse, Long product) {
        return Math.max(0, consigned.balance(owner, warehouse, product) - lines.consignedPromised(owner, warehouse, product));
    }

    /**
     * Of what can go back, the units held longer than the agreement's term: the lots received on or before the cutoff,
     * less everything counted missing or going back (they may be of those lots).
     */
    long due(MarketplaceConsignmentAgreement a, Long owner, Long warehouse, Long product, long returnable) {
        Integer days = a.getUnsoldReturnDays();
        if (days == null || days <= 0) return 0;
        long aged = consigned.heldSince(owner, warehouse, product, LocalDateTime.now().minusDays(days));
        long out = counts.openMissing(owner, warehouse, product)
                + returns.units(owner, warehouse, product, MarketplaceConsignedReturn.UNDER_WAY);
        return Math.max(0, Math.min(aged - out, returnable));
    }

    // ── what happens next ─────────────────────────────────────────────────────────────────────────────

    /** CANCEL (whoever asked, before hand-over), DISPATCH (the warehouse), RECEIVE (the owner). */
    public TransferDTOs.ReturnRow decide(TransferDTOs.ReturnDecision req) {
        if (req == null || req.id() == null || req.action() == null) throw new ValidationException("Choose what to do.");
        String action = req.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case CANCEL -> cancel(req);
            case DISPATCH -> dispatch(req);
            case RECEIVE -> receive(req);
            default -> throw new ValidationException("Choose what to do.");
        };
    }

    private TransferDTOs.ReturnRow cancel(TransferDTOs.ReturnDecision req) {
        Long org = access.org();
        MarketplaceConsignedReturn r = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignedReturn x = mine(req.id(), org);
            if (!org.equals(x.getRequestedByOrgId()))
                throw new ValidationException("Only " + (x.getRequestedByOrgId().equals(x.getConsignorOrgId()) ? "the owner" : "the warehouse")
                        + " can cancel " + x.getReturnNo() + ": it asked for it.");
            expect(x, req.version(), MarketplaceConsignedReturn.REQUESTED);
            x.setStatus(MarketplaceConsignedReturn.CANCELLED);
            x.setCancelledAt(LocalDateTime.now());
            audit.event("MKT_CONSIGN_RETURN_CANCELLED", "MKT_CONSIGNED_RETURN", x.getReturnNo(), x.getConsignorOrgId(),
                    MarketplaceAuditService.Actor.SELLER, MarketplaceConsignedReturn.REQUESTED, MarketplaceConsignedReturn.CANCELLED,
                    null, null);
            return returns.save(x);
        });
        sale.resync(r.getConsignorOrgId(), r.getConsigneeOrgId(), r.getMktProductId());
        return row(r);
    }

    private TransferDTOs.ReturnRow dispatch(TransferDTOs.ReturnDecision req) {
        Long org = access.org();
        BigDecimal cost = req.cost() == null ? BigDecimal.ZERO : req.cost();
        if (cost.signum() < 0 || cost.compareTo(MAX_COST) > 0 || cost.stripTrailingZeros().scale() > 2)
            throw new ValidationException("Enter what sending it cost, from Rs 0 to Rs 100,000.");
        String reference = req.reference() == null ? "" : req.reference().trim();
        if (reference.length() < 3) throw new ValidationException("Give the carrier's reference, or who collected it.");
        if (reference.length() > 100) throw new ValidationException("Keep the reference to 100 characters.");
        BigDecimal paid = cost.setScale(2, RoundingMode.HALF_UP);
        MarketplaceConsignedReturn r = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignedReturn x = mine(req.id(), org);
            if (!org.equals(x.getConsigneeOrgId()))
                throw new ValidationException("Only the " + PlatformWarehouseService.NAME + " warehouse hands stock back.");
            expect(x, req.version(), MarketplaceConsignedReturn.REQUESTED);
            x.setStatus(MarketplaceConsignedReturn.DISPATCHED);
            x.setCost(paid);
            x.setReference(reference);
            x.setDispatchedByUserId(access.userId());
            x.setDispatchedAt(LocalDateTime.now());
            if (CONSIGNOR.equals(x.getBearer()) && paid.signum() > 0) {
                settlement.chargeReturn(x.getConsignorOrgId(), paid, x.getReturnNo(), "Consigned stock sent back, " + x.getReturnNo()
                        + ": " + x.getUnits() + " × " + x.getProductName() + ", " + reference);
                x.setSettlementRef(x.getReturnNo());
            }
            audit.event("MKT_CONSIGN_RETURN_SENT", "MKT_CONSIGNED_RETURN", x.getReturnNo(), x.getConsignorOrgId(),
                    MarketplaceAuditService.Actor.SELLER, MarketplaceConsignedReturn.REQUESTED, MarketplaceConsignedReturn.DISPATCHED,
                    paid, clip(reference, 200) + ", cost: " + x.getBearer());
            return returns.save(x);
        });
        return row(r);
    }

    private TransferDTOs.ReturnRow receive(TransferDTOs.ReturnDecision req) {
        Long org = access.org();
        if (req.version() == null) throw new ValidationException("This return changed meanwhile. Refresh and try again.");
        // 1. claim it (the version serialises two presses); a press after a failure carries on from RECEIVING
        MarketplaceConsignedReturn r = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignedReturn x = mine(req.id(), org);
            if (!org.equals(x.getConsignorOrgId())) throw new ValidationException("Only the owner confirms it has its stock back.");
            if (!MarketplaceConsignedReturn.RECEIVING.equals(x.getStatus()))
                expect(x, req.version(), MarketplaceConsignedReturn.DISPATCHED);
            else if (!req.version().equals(x.getVersion()))
                throw new ValidationException("This return changed meanwhile. Refresh and try again.");
            x.setStatus(MarketplaceConsignedReturn.RECEIVING);
            return returns.saveAndFlush(x);
        });
        // 2. the owner's books: its hold on the units let go, lot by lot (they never left its stock)
        List<Taken> taken = back(r.getConsignorOrgId(), r.getConsigneeOrgId(), r.getMktProductId(), r.getUnits());
        // 3. the ledger
        MarketplaceConsignedReturn done = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignedReturn x = returns.lockById(r.getId()).orElseThrow();
            if (MarketplaceConsignedReturn.RECEIVED.equals(x.getStatus())) return x;
            for (Taken t : taken) consigned.save(returnedRow(x, t));
            x.setStatus(MarketplaceConsignedReturn.RECEIVED);
            x.setReceivedByUserId(access.userId());
            x.setReceivedAt(LocalDateTime.now());
            audit.event("MKT_CONSIGN_RETURN_RECEIVED", "MKT_CONSIGNED_RETURN", x.getReturnNo(), x.getConsignorOrgId(),
                    MarketplaceAuditService.Actor.SELLER, MarketplaceConsignedReturn.DISPATCHED, MarketplaceConsignedReturn.RECEIVED,
                    null, x.getUnits() + " × " + x.getProductName());
            return returns.save(x);
        });
        return row(done);
    }

    private MarketplaceConsignedReturn mine(Long id, Long org) {
        return returns.lockById(id).filter(x -> org.equals(x.getConsignorOrgId()) || org.equals(x.getConsigneeOrgId()))
                .orElseThrow(() -> new ValidationException("No such return of your stock."));
    }

    private static void expect(MarketplaceConsignedReturn x, Integer version, String status) {
        if (!status.equals(x.getStatus()))
            throw new ValidationException(x.getReturnNo() + " is already " + words(x.getStatus()) + ".");
        if (version != null && !version.equals(x.getVersion()))
            throw new ValidationException("This return changed meanwhile. Refresh and try again.");
    }

    private static String words(String status) {
        return switch (status) {
            case MarketplaceConsignedReturn.DISPATCHED, MarketplaceConsignedReturn.RECEIVING -> "handed over";
            case MarketplaceConsignedReturn.RECEIVED -> "back with the owner";
            case MarketplaceConsignedReturn.CANCELLED -> "cancelled";
            default -> "waiting to be handed over";
        };
    }

    // ── the owner's books ─────────────────────────────────────────────────────────────────────────────

    /** One lot's share of a return. */
    record Taken(ConsignedSaleService.Lot lot, int units) { }

    /**
     * Let the owner's hold on {@code units} go, oldest lot first: the hold let go, the rest of the lot set aside again.
     * Nothing is written until every lot is done, so pressing again after a failure gives the same holds.
     */
    List<Taken> back(Long owner, Long warehouse, Long product, int units) {
        List<Taken> out = new ArrayList<>();
        int need = units;
        for (ConsignedSaleService.Lot lot : sale.lots(owner, warehouse, product)) {
            if (need == 0) break;
            int take = Math.min(need, lot.left());
            try {
                AsOrg.run(owner, () -> trade.releaseHold(lot.holdKey()));
            } catch (RuntimeException e) {
                throw new ValidationException("Your stock could not be put back in your books, so nothing changed. "
                        + DownstreamMessage.orElse(e, "Please try again."));
            }
            sale.setAside(owner, lot, lot.left() - take);
            out.add(new Taken(lot, take));
            need -= take;
        }
        if (need > 0)
            throw new ValidationException(PlatformWarehouseService.NAME + " holds only " + (units - need) + " of these for you, so "
                    + units + " cannot be taken back. Ask MaxTheService.");
        return out;
    }

    private MarketplaceConsignedStock returnedRow(MarketplaceConsignedReturn r, Taken t) {
        MarketplaceConsignedStock m = new MarketplaceConsignedStock();
        m.setAgreementId(r.getAgreementId());
        m.setConsignorOrgId(r.getConsignorOrgId());
        m.setConsigneeOrgId(r.getConsigneeOrgId());
        m.setMktProductId(r.getMktProductId());
        m.setConsignorProductId(t.lot().ownerProductId());
        m.setProductName(r.getProductName());
        m.setMovement(MarketplaceConsignedStock.RETURNED);
        m.setQuantity(-t.units());
        m.setUnitValue(t.lot().unitValue());
        m.setTransferId(t.lot().transferId());
        m.setHoldKey(t.lot().holdKey());
        m.setDocNo(r.getReturnNo());
        m.setCreatedByUserId(access.userId());
        return m;
    }

    // ── what the screens read ─────────────────────────────────────────────────────────────────────────

    /** The transfers screen with its held rows' returns. */
    @Transactional(readOnly = true)
    public TransferDTOs.View decorate(TransferDTOs.View v) {
        if (v == null || v.held() == null || v.held().isEmpty()) return v;
        Long warehouse = settings.warehouseOrg().orElse(null);
        if (warehouse == null) return v;
        return new TransferDTOs.View(v.warehouse(), v.warehouseNamed(), v.products(), v.transfers(), v.consignUnder(),
                decorate(access.org(), warehouse, v.held()));
    }

    /** The held rows with what can go back and the returns under way, for {@code org}'s screen. */
    @Transactional(readOnly = true)
    public List<TransferDTOs.Held> decorate(Long org, Long warehouse, List<TransferDTOs.Held> held) {
        List<TransferDTOs.Held> out = new ArrayList<>();
        Map<Long, Optional<MarketplaceConsignmentAgreement>> byOwner = new HashMap<>();
        for (TransferDTOs.Held h : held) {
            Long owner = h.consignorOrgId();
            if (owner == null) { out.add(h); continue; }
            Optional<MarketplaceConsignmentAgreement> a = byOwner.computeIfAbsent(owner, k -> consignments.activeFor(k, warehouse));
            long returnable = a.isPresent() ? returnable(owner, warehouse, h.mktProductId()) : 0;
            long due = a.map(x -> due(x, owner, warehouse, h.mktProductId(), returnable)).orElse(0L);
            List<TransferDTOs.ReturnRow> rows = returns.findTop10ByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdOrderByIdDesc(
                    owner, warehouse, h.mktProductId()).stream().map(this::row).toList();
            out.add(h.withBack(new TransferDTOs.Back(returnable, due, a.map(MarketplaceConsignmentAgreement::getUnsoldReturnDays).orElse(null),
                    a.map(MarketplaceConsignmentAgreement::getUnsoldReturnBearer).orElse(null), org.equals(owner) && a.isPresent(),
                    org.equals(warehouse) && a.isPresent(), rows)));
        }
        return out;
    }

    TransferDTOs.ReturnRow row(MarketplaceConsignedReturn r) {
        return new TransferDTOs.ReturnRow(r.getId(), r.getReturnNo(), r.getVersion(), r.getProductName(), r.getUnits(),
                r.getReason(), r.getBearer(), r.getCost(), r.getReference(), r.getSettlementRef(), r.getNote(), r.getStatus(),
                r.getRequestedByOrgId() != null && r.getRequestedByOrgId().equals(r.getConsignorOrgId()), r.getRequestedAt(),
                r.getDispatchedAt(), r.getReceivedAt());
    }

    private Long warehouse() {
        return settings.warehouseOrg()
                .orElseThrow(() -> new ValidationException("MaxTheService has no warehouse, so nothing consigned is held."));
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
