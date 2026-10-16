package com.myplus.marketplace.multiseller.service;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.ShrinkagePolicy;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentCount;
import com.myplus.marketplace.multiseller.entity.MarketplaceShrinkageClaim;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedReturn;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentCountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceShrinkageClaimRepository;
import com.myplus.marketplace.support.AsOrg;
import com.myplus.marketplace.support.DownstreamMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * MKT-5d — counts of consigned stock and shrinkage claims (R12.1-R12.4, R20.5). Slice doc
 * {@code docs/slices/mkt-5d-counts-shrinkage.md}.
 *
 * <h3>The count</h3>
 * The warehouse (the custodian) counts one owner's product ({@link #count}). Expected is what the ledger holds less
 * what earlier counts found missing and nobody has resolved. Units now missing stay the owner's, set aside in its books
 * and in the ledger, but leave what the listing can sell at once (the ledger's {@code balance} subtracts them). Of
 * them, the agreement's tolerance (rounded down to whole units) is the owner's to bear.
 *
 * <h3>Resolving what is missing</h3>
 * Each missing unit is resolved once: the OWNER writes units off ({@link #decide} WRITE_OFF), or claims units beyond the
 * tolerance from the custodian ({@link #claim}), with proof when the agreement asks for it, inside its claim window,
 * and only for a kind of loss the agreement puts on the custodian. The OPERATOR approves or rejects a claim (the dispute
 * step); nothing is paid before that (R12.4). An approval credits the owner's settlement account with the units'
 * declared value. Write-off and approval both take the units out of the owner's books (its hold let go, the units
 * written off, the rest set aside again, lot by lot) and write LOST rows to the ledger.
 */
@Service
@RequiredArgsConstructor
public class ShrinkageService {

    static final int MAX_UNITS = 100_000;
    static final String WRITE_OFF = "WRITE_OFF";
    static final String WITHDRAW = "WITHDRAW";
    static final String APPROVE = "APPROVE";
    static final String REJECT = "REJECT";

    private final MarketplaceConsignmentCountRepository counts;
    private final MarketplaceShrinkageClaimRepository claims;
    private final MarketplaceConsignedStockRepository consigned;
    private final MarketplaceConsignmentAgreementRepository agreements;
    private final MarketplaceProductRepository products;
    private final ConsignmentAgreementService consignments;
    private final ConsignedSaleService sale;
    private final MarketplaceSettingsService settings;
    private final MarketplaceSettlementService settlement;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;
    private final TradeClient trade;
    private final InventoryClient inventory;
    private final PlatformTransactionManager txManager;
    private final MarketplaceConsignedReturnRepository returns;

    // ── the count ─────────────────────────────────────────────────────────────────────────────────────

    /** The warehouse counted {@code counted} of one owner's product. A repeat with the same key answers the first. */
    public TransferDTOs.CountRow count(TransferDTOs.CountRequest req) {
        Long warehouse = warehouse();
        if (!warehouse.equals(access.org()))
            throw new ValidationException("Only the " + PlatformWarehouseService.NAME + " warehouse counts consigned stock.");
        if (req == null || req.consignorOrgId() == null || req.mktProductId() == null)
            throw new ValidationException("Choose whose stock you counted.");
        if (req.counted() == null || req.counted() < 0 || req.counted() > MAX_UNITS)
            throw new ValidationException("Enter how many you counted, 0 or more.");
        String key = req.idempotencyKey() == null ? "" : req.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > 64) throw new ValidationException("The request is missing its idempotency key.");
        String note = clip(req.note(), 300);
        Long owner = req.consignorOrgId();

        MarketplaceConsignmentCount c = new TransactionTemplate(txManager).execute(s -> {
            Optional<MarketplaceConsignmentCount> again = counts.findByConsigneeOrgIdAndIdempotencyKey(warehouse, key);
            if (again.isPresent()) return again.get();
            MarketplaceConsignmentAgreement a = consignments.activeFor(owner, warehouse)
                    .orElseThrow(() -> new ValidationException("There is no active consignment agreement with "
                            + sale.name(owner) + ", so its stock cannot be counted."));
            agreements.lockById(a.getId());   // one count or checkout of this owner's stock at a time
            // MKT-5e: units asked back are still on the shelf but no longer in the balance; count once they are handed over
            if (returns.units(owner, warehouse, req.mktProductId(), java.util.Set.of(MarketplaceConsignedReturn.REQUESTED)) > 0)
                throw new ValidationException("Some of this stock is waiting to go back to " + sale.name(owner)
                        + ". Hand it over (or cancel the return) first, then count.");
            int expected = (int) Math.max(0, consigned.balance(owner, warehouse, req.mktProductId()));
            if (expected == 0)
                throw new ValidationException("MaxTheService holds none of this product for " + sale.name(owner)
                        + " that is not already counted missing.");
            int missing = Math.max(0, expected - req.counted());
            MarketplaceConsignmentCount n = new MarketplaceConsignmentCount();
            n.setAgreementId(a.getId());
            n.setConsignorOrgId(owner);
            n.setConsigneeOrgId(warehouse);
            n.setMktProductId(req.mktProductId());
            n.setProductName(products.findById(req.mktProductId()).map(p -> p.getCanonicalName()).orElse("Product " + req.mktProductId()));
            n.setExpected(expected);
            n.setCounted(req.counted());
            n.setMissing(missing);
            n.setTolerated(Math.min(missing, ShrinkagePolicy.tolerated(expected, a.getShrinkageTolerancePct())));
            n.setNote(note);
            n.setIdempotencyKey(key);
            n.setCountedByUserId(access.userId());
            n.setCountedAt(LocalDateTime.now());
            n = counts.save(n);
            audit.event("MKT_CONSIGN_COUNTED", "MKT_CONSIGNMENT_COUNT", n.getCountNo(), owner,
                    MarketplaceAuditService.Actor.SELLER, String.valueOf(expected), String.valueOf(req.counted()), null,
                    missing == 0 ? "matches" : missing + " missing, " + n.getTolerated() + " within tolerance");
            return n;
        });
        if (c.getMissing() > 0) sale.resync(owner, warehouse, c.getMktProductId());
        return row(c, agreements.findById(c.getAgreementId()).orElse(null), List.of());
    }

    // ── the owner's claim ─────────────────────────────────────────────────────────────────────────────

    /** The owner claims missing units of a count from the custodian. Moves no money: OPEN until the operator decides. */
    public TransferDTOs.ClaimRow claim(TransferDTOs.ClaimRequest req) {
        Long owner = access.org();
        if (req == null || req.countId() == null) throw new ValidationException("Choose the count you are claiming for.");
        ShrinkagePolicy.Kind kind = ShrinkagePolicy.kind(req.kind());
        if (kind == null) throw new ValidationException("Choose what happened to the missing units.");
        String proof = req.proof() == null ? "" : req.proof().trim();
        if (proof.length() > 500) throw new ValidationException("Keep the proof to 500 characters: link to longer evidence.");
        int units = req.units() == null ? 0 : req.units();
        if (units < 1) throw new ValidationException("Enter how many units you claim, 1 or more.");

        MarketplaceShrinkageClaim saved = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignmentCount c = counts.lockById(req.countId()).filter(x -> owner.equals(x.getConsignorOrgId()))
                    .orElseThrow(() -> new ValidationException("No such count of your stock."));
            MarketplaceConsignmentAgreement a = agreements.findById(c.getAgreementId())
                    .orElseThrow(() -> new ValidationException("The agreement of " + c.getCountNo() + " is missing."));
            String responsible = ShrinkagePolicy.forConsignment(kind, a.getDamageBearer(), a.getExpiryBearer());
            if (ShrinkagePolicy.CONSIGNOR.equals(responsible))
                throw new ValidationException("Under " + a.getAgreementNo() + ", " + words(kind) + " is yours to bear, so there "
                        + "is nothing to claim from " + PlatformWarehouseService.NAME + ". Write the units off instead.");
            LocalDate by = claimBy(c, a);
            if (TenantClock.today().isAfter(by))
                throw new ValidationException("Claims for " + c.getCountNo() + " closed on " + by + " (" + a.getClaimWindowDays()
                        + " days after the count). Write the units off instead.");
            if (Boolean.TRUE.equals(a.getProofRequired()) && proof.length() < 5)
                throw new ValidationException("Under " + a.getAgreementNo() + " a claim needs proof: say what shows it, for "
                        + "example the CCTV time or a photo link.");
            int claimable = claimable(c, (int) claims.openUnits(c.getId()), (int) claims.rejectedUnits(c.getId()));
            if (units > claimable)
                throw new ValidationException(claimable == 0
                        ? "Nothing of " + c.getCountNo() + " can be claimed: " + reasonNone(c)
                        : "Only " + claimable + " of " + c.getCountNo() + "'s missing units can be claimed"
                                + (c.getTolerated() > 0 ? " (" + c.getTolerated() + " are within the agreement's tolerance)" : "") + ".");
            MarketplaceShrinkageClaim m = new MarketplaceShrinkageClaim();
            m.setCountId(c.getId());
            m.setAgreementId(a.getId());
            m.setConsignorOrgId(owner);
            m.setConsigneeOrgId(c.getConsigneeOrgId());
            m.setMktProductId(c.getMktProductId());
            m.setProductName(c.getProductName());
            m.setKind(kind.name());
            m.setResponsible(responsible);
            m.setUnits(units);
            m.setAmount(value(owner, c.getConsigneeOrgId(), c.getMktProductId(), units));
            m.setProof(proof.isEmpty() ? null : proof);
            m.setNote(clip(req.note(), 300));
            m.setStatus(MarketplaceShrinkageClaim.OPEN);
            m.setRaisedByUserId(access.userId());
            m.setRaisedAt(LocalDateTime.now());
            m = claims.save(m);
            audit.event("MKT_SHRINK_CLAIMED", "MKT_SHRINKAGE_CLAIM", m.getClaimNo(), owner, MarketplaceAuditService.Actor.SELLER,
                    null, MarketplaceShrinkageClaim.OPEN, m.getAmount(), units + " × " + c.getProductName() + ", " + kind.name());
            return m;
        });
        return claimRow(saved, x -> String.format(Locale.ROOT, "CC-%06d", x.getCountId()), o -> null);
    }

    /** What a claim of {@code units} is worth: the units at their declared value, oldest lot first. */
    BigDecimal value(Long owner, Long warehouse, Long product, int units) {
        BigDecimal total = BigDecimal.ZERO;
        int need = units;
        for (ConsignedSaleService.Lot lot : sale.lots(owner, warehouse, product)) {
            if (need == 0) break;
            int take = Math.min(need, lot.left());
            total = total.add(lot.unitValue().multiply(BigDecimal.valueOf(take)));
            need -= take;
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    // ── decisions ─────────────────────────────────────────────────────────────────────────────────────

    /** WRITE_OFF and WITHDRAW (the owner), APPROVE and REJECT (the operator). */
    public TransferDTOs.CountRow decide(TransferDTOs.ShrinkDecision req) {
        if (req == null || req.id() == null || req.action() == null) throw new ValidationException("Choose what to do.");
        String action = req.action().trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case WRITE_OFF -> writeOff(req);
            case WITHDRAW -> withdraw(req);
            case APPROVE, REJECT -> decideClaim(req, action);
            default -> throw new ValidationException("Choose what to do.");
        };
    }

    private TransferDTOs.CountRow writeOff(TransferDTOs.ShrinkDecision req) {
        Long owner = access.org();
        record Plan(MarketplaceConsignmentCount c, int units, String docNo) { }
        // 1. mark the units pending on the count first, so a claim raised meanwhile cannot take them too; they stay
        //    missing (never sold) throughout
        Plan p = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignmentCount c = counts.lockById(req.id()).filter(x -> owner.equals(x.getConsignorOrgId()))
                    .orElseThrow(() -> new ValidationException("No such count of your stock."));
            if (req.version() != null && !req.version().equals(c.getVersion()))
                throw new ValidationException("This count changed meanwhile. Refresh and try again.");
            int units = writable(c, (int) claims.openUnits(c.getId()));
            if (units == 0) throw new ValidationException("Nothing of " + c.getCountNo() + " is left to write off.");
            c.setPending(c.getPending() + units);
            counts.save(c);
            return new Plan(c, units, c.getCountNo() + "-W" + (c.getWriteOffs() + 1));
        });
        MarketplaceConsignmentCount c = p.c();
        List<Taken> taken;
        try {
            taken = lose(owner, c.getConsigneeOrgId(), c.getMktProductId(), p.units(), p.docNo(),
                    "Consigned stock missing at the " + PlatformWarehouseService.NAME + " warehouse, count " + c.getCountNo()
                            + ": written off");
        } catch (RuntimeException e) {
            new TransactionTemplate(txManager).executeWithoutResult(s -> counts.lockById(c.getId()).ifPresent(x -> {
                x.setPending(Math.max(0, x.getPending() - p.units()));
                counts.save(x);
            }));
            throw e;
        }
        MarketplaceConsignmentCount done = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignmentCount x = counts.lockById(c.getId()).orElseThrow();
            x.setWriteOffs(x.getWriteOffs() + 1);
            x.setPending(Math.max(0, x.getPending() - p.units()));
            x.setWrittenOff(x.getWrittenOff() + p.units());
            lostRows(x, taken, p.docNo());
            audit.event("MKT_SHRINK_WRITTEN_OFF", "MKT_CONSIGNMENT_COUNT", x.getCountNo(), owner,
                    MarketplaceAuditService.Actor.SELLER, null, p.docNo(), null, p.units() + " × " + x.getProductName());
            return counts.save(x);
        });
        return row(done, agreements.findById(done.getAgreementId()).orElse(null), claims.findByCountIdInOrderByIdDesc(List.of(done.getId())));
    }

    private TransferDTOs.CountRow withdraw(TransferDTOs.ShrinkDecision req) {
        Long owner = access.org();
        MarketplaceConsignmentCount c = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceShrinkageClaim m = claims.findById(req.id()).filter(x -> owner.equals(x.getConsignorOrgId()))
                    .orElseThrow(() -> new ValidationException("No such claim of yours."));
            open(m, req.version());
            m.setStatus(MarketplaceShrinkageClaim.WITHDRAWN);
            m.setDecidedByUserId(access.userId());
            m.setDecidedAt(LocalDateTime.now());
            claims.save(m);
            audit.event("MKT_SHRINK_WITHDRAWN", "MKT_SHRINKAGE_CLAIM", m.getClaimNo(), owner, MarketplaceAuditService.Actor.SELLER,
                    MarketplaceShrinkageClaim.OPEN, MarketplaceShrinkageClaim.WITHDRAWN, null, null);
            return counts.findById(m.getCountId()).orElseThrow();
        });
        return row(c, agreements.findById(c.getAgreementId()).orElse(null), claims.findByCountIdInOrderByIdDesc(List.of(c.getId())));
    }

    private TransferDTOs.CountRow decideClaim(TransferDTOs.ShrinkDecision req, String action) {
        access.assertOperator();
        MarketplaceShrinkageClaim m = claims.findById(req.id()).orElseThrow(() -> new ValidationException("No such claim."));
        open(m, req.version());
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (REJECT.equals(action)) {
            if (reason.length() < 3) throw new ValidationException("Say why it is rejected: the owner reads it.");
            MarketplaceConsignmentCount c = new TransactionTemplate(txManager).execute(s -> {
                MarketplaceShrinkageClaim x = claims.findById(m.getId()).orElseThrow();
                open(x, req.version());
                x.setStatus(MarketplaceShrinkageClaim.REJECTED);
                x.setDecisionReason(clip(reason, 300));
                x.setDecidedByUserId(access.userId());
                x.setDecidedAt(LocalDateTime.now());
                claims.save(x);
                audit.event("MKT_SHRINK_REJECTED", "MKT_SHRINKAGE_CLAIM", x.getClaimNo(), x.getConsignorOrgId(),
                        MarketplaceAuditService.Actor.OPERATOR, MarketplaceShrinkageClaim.OPEN, MarketplaceShrinkageClaim.REJECTED,
                        x.getAmount(), clip(reason, 200));
                return counts.findById(x.getCountId()).orElseThrow();
            });
            return row(c, agreements.findById(c.getAgreementId()).orElse(null), claims.findByCountIdInOrderByIdDesc(List.of(c.getId())));
        }
        // APPROVE: the owner's books first (keyed by the claim, so a retry replays), then the ledger, the count and the money
        List<Taken> taken = lose(m.getConsignorOrgId(), m.getConsigneeOrgId(), m.getMktProductId(), m.getUnits(), m.getClaimNo(),
                "Consigned stock missing at the " + PlatformWarehouseService.NAME + " warehouse: claim " + m.getClaimNo() + " approved");
        MarketplaceConsignmentCount c = new TransactionTemplate(txManager).execute(s -> {
            MarketplaceConsignmentCount k = counts.lockById(m.getCountId()).orElseThrow();
            MarketplaceShrinkageClaim x = claims.findById(m.getId()).orElseThrow();
            if (!MarketplaceShrinkageClaim.OPEN.equals(x.getStatus())) return k;   // a concurrent approval already did it
            x.setStatus(MarketplaceShrinkageClaim.APPROVED);
            x.setDecisionReason(reason.isEmpty() ? null : clip(reason, 300));
            x.setDecidedByUserId(access.userId());
            x.setDecidedAt(LocalDateTime.now());
            x.setSettlementRef(x.getClaimNo());
            claims.save(x);
            k.setApproved(k.getApproved() + x.getUnits());
            lostRows(k, taken, x.getClaimNo());
            counts.save(k);
            settlement.creditClaim(x.getConsignorOrgId(), x.getAmount(), x.getClaimNo(),
                    "Shrinkage claim " + x.getClaimNo() + " approved: " + x.getUnits() + " × " + x.getProductName() + ", "
                            + words(ShrinkagePolicy.Kind.valueOf(x.getKind())) + ", count " + k.getCountNo());
            audit.event("MKT_SHRINK_APPROVED", "MKT_SHRINKAGE_CLAIM", x.getClaimNo(), x.getConsignorOrgId(),
                    MarketplaceAuditService.Actor.OPERATOR, MarketplaceShrinkageClaim.OPEN, MarketplaceShrinkageClaim.APPROVED,
                    x.getAmount(), x.getUnits() + " × " + x.getProductName());
            return k;
        });
        return row(c, agreements.findById(c.getAgreementId()).orElse(null), claims.findByCountIdInOrderByIdDesc(List.of(c.getId())));
    }

    private static void open(MarketplaceShrinkageClaim m, Integer version) {
        if (!MarketplaceShrinkageClaim.OPEN.equals(m.getStatus()))
            throw new ValidationException(m.getClaimNo() + " is already " + m.getStatus().toLowerCase(Locale.ROOT) + ".");
        if (version != null && !version.equals(m.getVersion()))
            throw new ValidationException("This claim changed meanwhile. Refresh and try again.");
    }

    // ── the owner's books ─────────────────────────────────────────────────────────────────────────────

    /** One lot's share of a loss. */
    record Taken(ConsignedSaleService.Lot lot, int units) { }

    /**
     * Take {@code units} out of the owner's books, oldest lot first: let the lot's hold go, write the units off (keyed
     * by the document and lot, so a retry replays instead of writing off twice), set the rest of the lot aside again.
     * Lots done before a failure stay done; the same document retried carries on from there.
     */
    List<Taken> lose(Long owner, Long warehouse, Long product, int units, String docNo, String reason) {
        List<Taken> out = new ArrayList<>();
        int need = units;
        String ownerName = sale.name(owner);
        for (ConsignedSaleService.Lot lot : sale.lots(owner, warehouse, product)) {
            if (need == 0) break;
            int take = Math.min(need, lot.left());
            String key = "MKT-LOST-" + docNo + "-T" + lot.transferId();
            try {
                AsOrg.run(owner, () -> trade.releaseHold(lot.holdKey()));
            } catch (RuntimeException e) {
                throw new ValidationException(ownerName + "'s consigned stock could not be let go in its books, so nothing was "
                        + "written off. " + DownstreamMessage.orElse(e, "Please try again."));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("productId", lot.ownerProductId());
            body.put("adjustmentType", "DECREASE");
            body.put("quantity", take);
            body.put("reason", reason);
            body.put("idempotencyKey", key);
            try {
                AsOrg.call(owner, () -> inventory.adjustStock(body));
            } catch (RuntimeException e) {
                sale.setAside(owner, lot, lot.left());
                throw new ValidationException(ownerName + "'s stock could not be written off, so nothing was. "
                        + DownstreamMessage.orElse(e, "Please try again."));
            }
            sale.setAside(owner, lot, lot.left() - take);
            out.add(new Taken(lot, take));
            need -= take;
        }
        if (need > 0)
            throw new ValidationException("MaxTheService holds only " + (units - need) + " of these for " + ownerName
                    + ", so " + units + " cannot be written off.");
        return out;
    }

    private void lostRows(MarketplaceConsignmentCount c, List<Taken> taken, String docNo) {
        for (Taken t : taken) {
            MarketplaceConsignedStock m = new MarketplaceConsignedStock();
            m.setAgreementId(c.getAgreementId());
            m.setConsignorOrgId(c.getConsignorOrgId());
            m.setConsigneeOrgId(c.getConsigneeOrgId());
            m.setMktProductId(c.getMktProductId());
            m.setConsignorProductId(t.lot().ownerProductId());
            m.setProductName(c.getProductName());
            m.setMovement(MarketplaceConsignedStock.LOST);
            m.setQuantity(-t.units());
            m.setUnitValue(t.lot().unitValue());
            m.setTransferId(t.lot().transferId());
            m.setHoldKey(t.lot().holdKey());
            m.setDocNo(docNo);
            m.setCreatedByUserId(access.userId());
            consigned.save(m);
        }
    }

    // ── what the screens read ─────────────────────────────────────────────────────────────────────────

    /** The transfers screen with its held rows' counts and claims. */
    @Transactional(readOnly = true)
    public TransferDTOs.View decorate(TransferDTOs.View v) {
        if (v == null || v.held() == null || v.held().isEmpty()) return v;
        Long warehouse = settings.warehouseOrg().orElse(null);
        if (warehouse == null) return v;
        return new TransferDTOs.View(v.warehouse(), v.warehouseNamed(), v.products(), v.transfers(), v.consignUnder(),
                decorate(access.org(), warehouse, v.held()));
    }

    /** The held rows with their counts and claims, for {@code org}'s screen (the warehouse's, or one owner's). */
    @Transactional(readOnly = true)
    public List<TransferDTOs.Held> decorate(Long org, Long warehouse, List<TransferDTOs.Held> held) {
        List<TransferDTOs.Held> out = new ArrayList<>();
        Map<Long, Optional<MarketplaceConsignmentAgreement>> byOwner = new HashMap<>();
        for (TransferDTOs.Held h : held) {
            Long owner = h.consignorOrgId();
            if (owner == null) { out.add(h); continue; }
            Optional<MarketplaceConsignmentAgreement> a = byOwner.computeIfAbsent(owner, k -> consignments.activeFor(k, warehouse));
            List<MarketplaceConsignmentCount> cs = counts.findTop10ByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdOrderByIdDesc(
                    owner, warehouse, h.mktProductId());
            Map<Long, List<MarketplaceShrinkageClaim>> byCount = new HashMap<>();
            if (!cs.isEmpty())
                for (MarketplaceShrinkageClaim m : claims.findByCountIdInOrderByIdDesc(cs.stream().map(MarketplaceConsignmentCount::getId).toList()))
                    byCount.computeIfAbsent(m.getCountId(), k -> new ArrayList<>()).add(m);
            List<TransferDTOs.CountRow> rows = new ArrayList<>();
            long missing = 0;
            for (MarketplaceConsignmentCount c : cs) {
                rows.add(row(c, a.filter(x -> x.getId().equals(c.getAgreementId()))
                        .orElseGet(() -> agreements.findById(c.getAgreementId()).orElse(null)), byCount.getOrDefault(c.getId(), List.of())));
                missing += c.open();
            }
            LocalDate due = a.map(x -> countDue(x, cs, owner, warehouse, h.mktProductId())).orElse(null);
            MarketplaceConsignmentAgreement t = a.orElse(null);
            out.add(h.withShrink(new TransferDTOs.Shrink(missing, due, due != null && TenantClock.today().isAfter(due),
                    org.equals(warehouse), org.equals(owner), t == null ? null : t.getAgreementNo(),
                    t == null ? null : t.getShrinkageTolerancePct(), t == null ? null : t.getClaimWindowDays(),
                    t != null && Boolean.TRUE.equals(t.getProofRequired()), t == null ? null : t.getDamageBearer(),
                    t == null ? null : t.getExpiryBearer(), rows)));
        }
        return out;
    }

    /** The next count the agreement asks for: its frequency after the last count, or after the stock first came in. */
    private LocalDate countDue(MarketplaceConsignmentAgreement a, List<MarketplaceConsignmentCount> cs, Long owner, Long warehouse,
                               Long product) {
        if (a.getCountEveryDays() == null || a.getCountEveryDays() <= 0) return null;
        LocalDateTime from = !cs.isEmpty() ? cs.get(0).getCountedAt()
                : consigned.findFirstByConsignorOrgIdAndConsigneeOrgIdAndMktProductIdAndMovementOrderByIdAsc(owner, warehouse,
                        product, MarketplaceConsignedStock.RECEIVED).map(MarketplaceConsignedStock::getCreatedAt).orElse(null);
        return from == null ? null : from.toLocalDate().plusDays(a.getCountEveryDays());
    }

    /** The operator's queue: claims waiting for a decision first, then the latest decided. */
    @Transactional(readOnly = true)
    public List<TransferDTOs.ClaimRow> queue() {
        access.assertOperator();
        Map<Long, String> countNos = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        return claims.queue(PageRequest.of(0, 100)).stream()
                .map(m -> claimRow(m, x -> countNos.computeIfAbsent(x.getCountId(),
                        id -> String.format(Locale.ROOT, "CC-%06d", id)), o -> names.computeIfAbsent(o, sale::name)))
                .toList();
    }

    TransferDTOs.CountRow row(MarketplaceConsignmentCount c, MarketplaceConsignmentAgreement a, List<MarketplaceShrinkageClaim> ms) {
        int openClaimed = units(ms, MarketplaceShrinkageClaim.OPEN);
        int rejected = units(ms, MarketplaceShrinkageClaim.REJECTED);
        LocalDate by = a == null ? null : claimBy(c, a);
        boolean windowOpen = by != null && !TenantClock.today().isAfter(by);
        return new TransferDTOs.CountRow(c.getId(), c.getCountNo(), c.getVersion(), c.getCountedAt(), c.getExpected(),
                c.getCounted(), c.getMissing(), c.getTolerated(), c.getWrittenOff(), c.getApproved(), c.open(),
                windowOpen ? claimable(c, openClaimed, rejected) : 0, writable(c, openClaimed), by, c.getNote(),
                ms.stream().map(m -> claimRow(m, x -> c.getCountNo(), o -> null)).toList());
    }

    private TransferDTOs.ClaimRow claimRow(MarketplaceShrinkageClaim m, Function<MarketplaceShrinkageClaim, String> countNo,
                                           Function<Long, String> names) {
        return new TransferDTOs.ClaimRow(m.getId(), m.getClaimNo(), m.getVersion(), countNo.apply(m), m.getConsignorOrgId(),
                names.apply(m.getConsignorOrgId()), m.getProductName(), m.getKind(), m.getResponsible(), m.getUnits(),
                m.getAmount(), m.getProof(), m.getNote(), m.getStatus(), m.getDecisionReason(), m.getSettlementRef(),
                m.getRaisedAt(), m.getDecidedAt());
    }

    private static int units(List<MarketplaceShrinkageClaim> ms, String status) {
        return ms.stream().filter(m -> status.equals(m.getStatus())).mapToInt(MarketplaceShrinkageClaim::getUnits).sum();
    }

    /**
     * Of a count's missing units: beyond the tolerance, not approved, not under a waiting claim and not already rejected
     * (the dispute is over: they can only be written off), and still open.
     */
    static int claimable(MarketplaceConsignmentCount c, int openClaimed, int rejected) {
        return Math.max(0, Math.min(c.getMissing() - c.getTolerated() - c.getApproved() - openClaimed - rejected,
                c.open() - openClaimed - c.getPending()));
    }

    /** Of a count's missing units: still open, not under a waiting claim, and not being written off already. */
    static int writable(MarketplaceConsignmentCount c, int openClaimed) {
        return Math.max(0, c.open() - openClaimed - c.getPending());
    }

    private static LocalDate claimBy(MarketplaceConsignmentCount c, MarketplaceConsignmentAgreement a) {
        int days = a.getClaimWindowDays() == null ? 0 : a.getClaimWindowDays();
        return c.getCountedAt().toLocalDate().plusDays(days);
    }

    private static String reasonNone(MarketplaceConsignmentCount c) {
        if (c.open() == 0) return "every missing unit is already resolved.";
        if (c.getMissing() - c.getTolerated() - c.getApproved() <= 0) return "what is missing is within the agreement's tolerance.";
        return "the rest is already claimed: waiting for MaxTheService, or rejected (write those off).";
    }

    /** The kind in words, for messages. */
    static String words(ShrinkagePolicy.Kind k) {
        return switch (k) {
            case THEFT -> "theft";
            case DAMAGE -> "damage";
            case BREAKAGE -> "breakage";
            case SPOILAGE -> "spoilage";
            case EXPIRY -> "expiry";
            case MISCOUNT -> "a miscount";
            case CONSUMPTION -> "unrecorded use";
            case SHORT_SHIPMENT -> "a short shipment";
            case DELIVERY_LOSS -> "loss in delivery";
        };
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
