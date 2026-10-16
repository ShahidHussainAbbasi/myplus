package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.dto.ConsignmentDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-5a (R4.6, R12.2, R2.1, R2.2) — consignment agreements: every term on which a consignor's stock is held and sold
 * by MaxTheService's warehouse, set BEFORE any consigned stock can move.
 *
 * <p>The operator offers the terms to an approved seller (the consignor); the consignee is the named warehouse (ruling
 * 2026-10-15, "Warehouse first"). The consignor's owner or admin accepts or declines; the operator may withdraw an offer
 * not yet answered. Either side may end an active agreement, with a reason. Terms are never edited: new terms are a new
 * agreement, and accepting it replaces the active one. One waiting offer and one active agreement per pair at most.
 *
 * <p>{@link #activeFor} is the switch later slices read: no active agreement, no consignment.
 */
@Service
@RequiredArgsConstructor
public class ConsignmentAgreementService {

    static final int PAGE = 200;
    static final Set<String> TRANSFER = Set.of("ON_SALE", "ON_DELIVERY");
    static final Set<String> PARTY = Set.of("CONSIGNOR", "CONSIGNEE");
    static final Set<String> INSURER = Set.of("CONSIGNOR", "CONSIGNEE", "NONE");
    static final BigDecimal MAX_COMMISSION = new BigDecimal("50");
    static final BigDecimal MAX_TOLERANCE = new BigDecimal("10");
    static final String ENTITY = "MKT_CONSIGNMENT_AGREEMENT";

    private final MarketplaceConsignmentAgreementRepository agreements;
    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplaceSettingsService settings;
    private final MarketplaceSellerService sellers;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;
    private final MarketplaceConsignedStockRepository consigned;

    // ── the operator ──────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ConsignmentDTOs.Agreement> list(String status) {
        access.assertOperator();
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        List<MarketplaceConsignmentAgreement> rows = s == null ? agreements.findAllByOrderByIdDesc(PageRequest.of(0, PAGE))
                : agreements.findByStatusOrderByIdDesc(s, PageRequest.of(0, PAGE));
        return toDtos(rows);
    }

    @Transactional
    public ConsignmentDTOs.Agreement offer(ConsignmentDTOs.OfferRequest req) {
        access.assertOperator();
        Long warehouse = settings.warehouseOrg()
                .orElseThrow(() -> new ValidationException("Name the MaxTheService warehouse first: it holds consigned stock."));
        Long consignor = req == null ? null : req.consignorOrganizationId();
        if (consignor == null) throw new ValidationException("Choose the seller whose stock it is.");
        if (consignor.equals(warehouse)) throw new ValidationException("The warehouse cannot consign stock to itself.");
        MarketplaceSellerAccount a = accounts.findByOrganizationId(consignor).orElse(null);
        if (a == null || !SellerAccount.APPROVED.name().equals(a.getStatus()))
            throw new ValidationException("Choose an approved seller account.");
        ConsignmentDTOs.Terms t = validate(req.terms());
        Optional<MarketplaceConsignmentAgreement> waiting = pair(consignor, warehouse, MarketplaceConsignmentAgreement.OFFERED);
        if (waiting.isPresent())
            throw new ValidationException(waiting.get().getAgreementNo() + " is still waiting for " + name(consignor)
                    + "'s answer. Withdraw it before offering new terms.");

        MarketplaceConsignmentAgreement c = new MarketplaceConsignmentAgreement();
        c.setConsignorOrgId(consignor);
        c.setConsigneeOrgId(warehouse);
        c.setStatus(MarketplaceConsignmentAgreement.OFFERED);
        apply(c, t);
        c.setNote(clip(req.note()));
        c.setOfferedByUserId(access.userId());
        c = agreements.save(c);
        audit.event("MKT_CONSIGNMENT_OFFERED", ENTITY, c.getAgreementNo(), consignor, MarketplaceAuditService.Actor.OPERATOR,
                null, MarketplaceConsignmentAgreement.OFFERED, c.getCommissionPct(), summary(c));
        return toDto(c, new HashMap<>());
    }

    // ── both sides ────────────────────────────────────────────────────────────────────────────────────

    /** A business's own agreements: as the consignor, or (the warehouse) as the consignee. */
    @Transactional(readOnly = true)
    public ConsignmentDTOs.View mine() {
        sellers.assertActiveSeller();
        Long org = access.org();
        boolean warehouse = settings.warehouseOrg().map(org::equals).orElse(false);
        return new ConsignmentDTOs.View(warehouse,
                toDtos(agreements.findByConsignorOrgIdOrConsigneeOrgIdOrderByIdDesc(org, org, PageRequest.of(0, PAGE))));
    }

    /**
     * WITHDRAW or END by the operator ({@code operator} true); ACCEPT, DECLINE or END by the consignor. The warehouse
     * answers nothing here: MaxTheService offers the terms, through its operator.
     */
    @Transactional
    public ConsignmentDTOs.Agreement decide(ConsignmentDTOs.DecisionRequest req, boolean operator) {
        if (operator) access.assertOperator();
        else {
            sellers.assertActiveSeller();
            access.assertOwnerOrAdmin();
        }
        MarketplaceConsignmentAgreement c = load(req);
        String action = req.action() == null ? "" : req.action().trim().toUpperCase(Locale.ROOT);
        if (!operator && !c.getConsignorOrgId().equals(access.org())) throw notFound();
        String reason = clip(req.reason());
        String from = c.getStatus();
        LocalDateTime now = LocalDateTime.now();
        MarketplaceAuditService.Actor actor = operator ? MarketplaceAuditService.Actor.OPERATOR : MarketplaceAuditService.Actor.SELLER;

        switch (action) {
            case "WITHDRAW" -> {
                if (!operator) throw new ValidationException("Only MaxTheService can withdraw its offer.");
                assertStatus(c, MarketplaceConsignmentAgreement.OFFERED, "Only an offer not yet answered can be withdrawn.");
                c.setStatus(MarketplaceConsignmentAgreement.WITHDRAWN);
                c.setAnsweredAt(now);
                c.setAnsweredByUserId(access.userId());
            }
            case "ACCEPT" -> {
                if (operator) throw new ValidationException("The seller accepts its own consignment terms.");
                assertStatus(c, MarketplaceConsignmentAgreement.OFFERED, "Only an offer not yet answered can be accepted.");
                pair(c.getConsignorOrgId(), c.getConsigneeOrgId(), MarketplaceConsignmentAgreement.ACTIVE).ifPresent(old -> {
                    old.setStatus(MarketplaceConsignmentAgreement.REPLACED);
                    old.setReplacedById(c.getId());
                    old.setEndedAt(now);
                    agreements.save(old);
                    audit.event("MKT_CONSIGNMENT_REPLACED", ENTITY, old.getAgreementNo(), old.getConsignorOrgId(), actor,
                            MarketplaceConsignmentAgreement.ACTIVE, MarketplaceConsignmentAgreement.REPLACED, null,
                            "Replaced by " + c.getAgreementNo());
                });
                c.setStatus(MarketplaceConsignmentAgreement.ACTIVE);
                c.setAnsweredAt(now);
                c.setAnsweredByUserId(access.userId());
            }
            case "DECLINE" -> {
                if (operator) throw new ValidationException("Withdraw the offer instead.");
                assertStatus(c, MarketplaceConsignmentAgreement.OFFERED, "Only an offer not yet answered can be declined.");
                if (reason == null) throw new ValidationException("Say why you decline, so MaxTheService can offer better terms.");
                c.setStatus(MarketplaceConsignmentAgreement.DECLINED);
                c.setAnswerReason(reason);
                c.setAnsweredAt(now);
                c.setAnsweredByUserId(access.userId());
            }
            case "END" -> {
                assertStatus(c, MarketplaceConsignmentAgreement.ACTIVE, "Only an active agreement can be ended.");
                if (reason == null) throw new ValidationException("Say why the agreement ends.");
                // MKT-5b: stock held under it would be left with no terms to sell, count or return it by. Accepting new
                // terms is the way to change them while stock is held: the stock carries over to the new agreement.
                long held = consigned.heldUnits(c.getConsignorOrgId(), c.getConsigneeOrgId());
                if (held > 0)
                    throw new ValidationException("The warehouse still holds " + held + (held == 1 ? " unit" : " units")
                            + " consigned under this agreement. They must be sold or returned before it can end.");
                c.setStatus(MarketplaceConsignmentAgreement.ENDED);
                c.setAnswerReason(reason);
                c.setEndedAt(now);
            }
            default -> throw new ValidationException("Choose what to do with the agreement.");
        }
        MarketplaceConsignmentAgreement saved = agreements.save(c);
        audit.event("MKT_CONSIGNMENT_" + saved.getStatus(), ENTITY, saved.getAgreementNo(), saved.getConsignorOrgId(), actor,
                from, saved.getStatus(), null, reason);
        return toDto(saved, new HashMap<>());
    }

    /** The terms consigned stock moves under for this pair, if any. Later slices refuse consignment without them. */
    @Transactional(readOnly = true)
    public Optional<MarketplaceConsignmentAgreement> activeFor(Long consignor, Long consignee) {
        if (consignor == null || consignee == null) return Optional.empty();
        return pair(consignor, consignee, MarketplaceConsignmentAgreement.ACTIVE);
    }

    // ── the terms ─────────────────────────────────────────────────────────────────────────────────────

    /** Every term present and in bounds; each refusal names the term. Text values are normalised to upper case. */
    static ConsignmentDTOs.Terms validate(ConsignmentDTOs.Terms t) {
        if (t == null) throw new ValidationException("Set the terms of the agreement.");
        String transfer = oneOf(t.ownershipTransfer(), TRANSFER, "Choose when ownership passes: on sale or on delivery.");
        String priceBy = oneOf(t.priceSetBy(), PARTY, "Choose who sets the selling price.");
        BigDecimal commission = pct(t.commissionPct(), MAX_COMMISSION, "Commission");
        int settle = days(t.settleEveryDays(), 1, 90, "Settle every");
        int reconcile = days(t.reconcileEveryDays(), 1, 365, "Reconcile every");
        int count = days(t.countEveryDays(), 1, 365, "Count the stock every");
        BigDecimal tolerance = pct(t.shrinkageTolerancePct(), MAX_TOLERANCE, "Shrinkage tolerance");
        String damage = oneOf(t.damageBearer(), PARTY, "Choose who bears damage.");
        String expiry = oneOf(t.expiryBearer(), PARTY, "Choose who bears expiry.");
        String insured = oneOf(t.insuredBy(), INSURER, "Choose who insures the stock, or none.");
        int claim = days(t.claimWindowDays(), 1, 90, "Claim window");
        if (t.proofRequired() == null) throw new ValidationException("Choose whether a shrinkage claim needs proof.");
        int unsold = days(t.unsoldReturnDays(), 1, 730, "Return unsold stock after");
        String unsoldBy = oneOf(t.unsoldReturnBearer(), PARTY, "Choose who pays to return unsold stock.");
        return new ConsignmentDTOs.Terms(transfer, priceBy, commission, settle, reconcile, count, tolerance, damage, expiry,
                insured, claim, t.proofRequired(), unsold, unsoldBy);
    }

    private static String oneOf(String v, Set<String> allowed, String refusal) {
        String s = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(s)) throw new ValidationException(refusal);
        return s;
    }

    private static int days(Integer v, int min, int max, String term) {
        if (v == null || v < min || v > max)
            throw new ValidationException(term + ": enter a number of days from " + min + " to " + max + ".");
        return v;
    }

    private static BigDecimal pct(BigDecimal v, BigDecimal max, String term) {
        if (v == null || v.signum() < 0 || v.compareTo(max) > 0)
            throw new ValidationException(term + ": enter a percentage from 0 to " + max.toPlainString() + ".");
        if (v.stripTrailingZeros().scale() > 2) throw new ValidationException(term + ": at most two decimal places.");
        return v.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static void apply(MarketplaceConsignmentAgreement c, ConsignmentDTOs.Terms t) {
        c.setOwnershipTransfer(t.ownershipTransfer());
        c.setPriceSetBy(t.priceSetBy());
        c.setCommissionPct(t.commissionPct());
        c.setSettleEveryDays(t.settleEveryDays());
        c.setReconcileEveryDays(t.reconcileEveryDays());
        c.setCountEveryDays(t.countEveryDays());
        c.setShrinkageTolerancePct(t.shrinkageTolerancePct());
        c.setDamageBearer(t.damageBearer());
        c.setExpiryBearer(t.expiryBearer());
        c.setInsuredBy(t.insuredBy());
        c.setClaimWindowDays(t.claimWindowDays());
        c.setProofRequired(t.proofRequired());
        c.setUnsoldReturnDays(t.unsoldReturnDays());
        c.setUnsoldReturnBearer(t.unsoldReturnBearer());
    }

    static ConsignmentDTOs.Terms terms(MarketplaceConsignmentAgreement c) {
        return new ConsignmentDTOs.Terms(c.getOwnershipTransfer(), c.getPriceSetBy(), c.getCommissionPct(),
                c.getSettleEveryDays(), c.getReconcileEveryDays(), c.getCountEveryDays(), c.getShrinkageTolerancePct(),
                c.getDamageBearer(), c.getExpiryBearer(), c.getInsuredBy(), c.getClaimWindowDays(), c.getProofRequired(),
                c.getUnsoldReturnDays(), c.getUnsoldReturnBearer());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────

    private Optional<MarketplaceConsignmentAgreement> pair(Long consignor, Long consignee, String status) {
        return agreements.findFirstByConsignorOrgIdAndConsigneeOrgIdAndStatusOrderByIdDesc(consignor, consignee, status);
    }

    private MarketplaceConsignmentAgreement load(ConsignmentDTOs.DecisionRequest req) {
        if (req == null || req.id() == null) throw new ValidationException("Choose the agreement.");
        MarketplaceConsignmentAgreement c = agreements.findById(req.id()).orElseThrow(ConsignmentAgreementService::notFound);
        if (req.version() != null && !Objects.equals(req.version(), c.getVersion()))
            throw new ValidationException("This agreement changed meanwhile. Refresh and try again.");
        return c;
    }

    private static void assertStatus(MarketplaceConsignmentAgreement c, String status, String refusal) {
        if (!status.equals(c.getStatus())) throw new ValidationException(refusal);
    }

    static ValidationException notFound() {
        return new ValidationException("No such consignment agreement.");
    }

    private List<ConsignmentDTOs.Agreement> toDtos(List<MarketplaceConsignmentAgreement> rows) {
        Map<Long, String> names = new HashMap<>();
        return rows.stream().map(c -> toDto(c, names)).toList();
    }

    private ConsignmentDTOs.Agreement toDto(MarketplaceConsignmentAgreement c, Map<Long, String> names) {
        String replacedBy = c.getReplacedById() == null ? null : String.format("CA-%06d", c.getReplacedById());
        return new ConsignmentDTOs.Agreement(c.getId(), c.getAgreementNo(), c.getConsignorOrgId(),
                names.computeIfAbsent(c.getConsignorOrgId(), this::name), c.getConsigneeOrgId(), PlatformWarehouseService.NAME,
                c.getStatus(), terms(c), c.getNote(), c.getAnswerReason(), replacedBy, c.getCreatedAt(), c.getAnsweredAt(),
                c.getEndedAt(), c.getVersion());
    }

    String name(Long org) {
        return accounts.findByOrganizationId(org).map(MarketplaceSellerAccount::getDisplayName)
                .filter(n -> n != null && !n.isBlank()).orElse("Seller " + org);
    }

    private static String summary(MarketplaceConsignmentAgreement c) {
        return "commission " + c.getCommissionPct().toPlainString() + "%, ownership " + c.getOwnershipTransfer()
                + ", settle every " + c.getSettleEveryDays() + " days";
    }

    private static String clip(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}
