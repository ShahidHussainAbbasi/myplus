package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.domain.ReturnCostPolicy;
import com.myplus.marketplace.multiseller.dto.SupportDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplaceReturn;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceSupportCase;
import com.myplus.marketplace.multiseller.entity.MarketplaceSupportMessage;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSupportCaseRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSupportMessageRepository;
import com.myplus.marketplace.multiseller.service.MarketplaceAuditService.Actor;
import com.myplus.marketplace.service.OrderService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1f — support cases and marketplace returns (slice doc {@code mkt-1f-support-returns.md}).
 *
 * <p><b>One place to complain (R8.2).</b> The customer writes to MaxTheService; the operator tasks the seller; the
 * seller's answer reaches the customer signed "MaxTheService support". The customer never sees a seller's phone or an
 * internal note, and a seller sees only cases on its own orders.
 *
 * <p><b>The cost follows the cause (R13.1, R13.3).</b> A return's bearer is resolved WHEN OPENED from the line's party
 * snapshot through {@link ReturnCostPolicy}, and stored. Change of mind is allowed within the line's snapshotted
 * return days and costs the customer the pickup fee (R-MKT-14).
 *
 * <p><b>Money and books (R13.2).</b> When the seller's rider has the item: a CREDIT NOTE on the seller's invoice (faulty
 * goods quarantined), then the refund — through the card, once per return; or, for cash on delivery, handed back by the
 * rider at pickup (R-MKT-12). The return is claimed (RECEIVED) before either, so a retry never doubles them.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceSupportService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplaceSupportService.class);

    static final String CASE_DOC = "SC";
    static final String RETURN_DOC = "RT";
    static final Set<String> TOPICS = Set.of("ORDER_PROBLEM", "RETURN", "WARRANTY", "OTHER");
    static final Set<String> OUTCOMES = Set.of("RESTOCK", "QUARANTINE", "WRITE_OFF");
    static final List<String> OPEN_STATES = List.of(MarketplaceSupportCase.OPEN, MarketplaceSupportCase.WAITING_SELLER,
            MarketplaceSupportCase.WAITING_CUSTOMER);
    static final Set<String> RETURN_IN_PROGRESS = Set.of(MarketplaceReturn.REQUESTED, MarketplaceReturn.APPROVED,
            MarketplaceReturn.RECEIVED);

    public static final String LOOKING_INTO_IT = "MaxTheService support is looking into this.";
    static final String SUPPORT = "MaxTheService support";
    static final String NO_ORDER = "No such order.";
    static final String NO_CASE = "No such case.";
    static final String NO_RETURN = "No such return.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final MarketplaceSupportCaseRepository cases;
    private final MarketplaceSupportMessageRepository messages;
    private final MarketplaceReturnRepository returns;
    private final MarketplaceOrderRepository orders;
    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceOrderLineRepository lines;
    private final MarketplaceSellerAccountRepository sellerAccounts;
    private final MarketplaceSettingsService settings;
    private final MarketplacePaymentService payments;
    private final MarketplaceAuditService audit;
    private final OrderService storeOrders;
    private final DocumentNumberService numbers;
    private final SellerAccess access;
    private final PlatformTransactionManager txManager;

    // ── customer ───────────────────────────────────────────────────────────────────────────────────────

    /** Open (or add to) the order's one case. A RETURN topic also opens the return of one line. */
    @Transactional
    public SupportDTOs.CaseView open(MarketplaceCustomer c, String orderNo, SupportDTOs.OpenCaseRequest req) {
        MarketplaceOrder o = ownOrder(c, orderNo);
        if (req == null || req.topic() == null || !TOPICS.contains(req.topic().trim().toUpperCase(Locale.ROOT)))
            throw new ValidationException("Choose what you need help with.");
        String topic = req.topic().trim().toUpperCase(Locale.ROOT);
        String note = clip(req.note(), 2000);
        MarketplaceSellerOrder so = sellerOrder(o);
        if (!SellerOrder.ACCEPTED.name().equals(so.getAcceptanceStatus()) && !SellerOrder.HANDED_OVER.name().equals(so.getAcceptanceStatus()))
            throw new ValidationException("You can ask for help once the seller has confirmed the order. Until then you can cancel it in My orders.");
        if (!"RETURN".equals(topic) && note == null) throw new ValidationException("Tell us what went wrong.");

        MarketplaceSupportCase sc = cases.findFirstByMktOrderIdAndStatusNotOrderByIdDesc(o.getId(), MarketplaceSupportCase.RESOLVED)
                .orElse(null);
        boolean opened = sc == null;
        if (opened) {
            sc = new MarketplaceSupportCase();
            sc.setCaseNo(String.format(Locale.ROOT, "SC-%06d", numbers.next(MarketplaceCheckoutService.PLATFORM_ORG, CASE_DOC)));
            sc.setMktOrderId(o.getId());
            sc.setSellerOrgId(so.getSellerOrganizationId());
            sc.setCustomerId(c.getId());
            sc.setTopic(topic);
        }
        sc.setStatus(MarketplaceSupportCase.OPEN);
        sc = cases.save(sc);

        if ("RETURN".equals(topic)) openReturn(sc, o, so, req);
        if (note != null) message(sc.getId(), MarketplaceSupportMessage.CUSTOMER, c.getId(), note, true);
        if (opened) audit.event(MarketplaceAuditService.CASE_OPENED, MarketplaceAuditService.ENTITY_CASE, sc.getCaseNo(),
                sc.getSellerOrgId(), Actor.CUSTOMER, null, topic, null, o.getOrderNo());
        return customerView(sc);
    }

    @Transactional(readOnly = true)
    public List<SupportDTOs.CaseView> myCases(MarketplaceCustomer c) {
        return cases.findTop50ByCustomerIdOrderByCreatedAtDesc(c.getId()).stream().map(this::customerView).toList();
    }

    @Transactional(readOnly = true)
    public SupportDTOs.CaseView myCase(MarketplaceCustomer c, String caseNo) {
        return customerView(ownCase(c, caseNo));
    }

    @Transactional
    public SupportDTOs.CaseView customerMessage(MarketplaceCustomer c, String caseNo, String body) {
        MarketplaceSupportCase sc = ownCase(c, caseNo);
        String b = clip(body, 2000);
        if (b == null) throw new ValidationException("Write your message.");
        if (MarketplaceSupportCase.RESOLVED.equals(sc.getStatus())) sc.setStatus(MarketplaceSupportCase.OPEN);
        else if (MarketplaceSupportCase.WAITING_CUSTOMER.equals(sc.getStatus())) sc.setStatus(MarketplaceSupportCase.OPEN);
        cases.save(sc);
        message(sc.getId(), MarketplaceSupportMessage.CUSTOMER, c.getId(), b, true);
        return customerView(sc);
    }

    // ── operator ───────────────────────────────────────────────────────────────────────────────────────

    /** The queue: urgent first, then the longest waiting. Default: every case not yet resolved. */
    @Transactional(readOnly = true)
    public PageResponse<SupportDTOs.CaseRow> queue(String status, Integer page, Integer size) {
        access.assertOperator();
        List<String> states = status == null || status.isBlank() ? OPEN_STATES : List.of(status.trim().toUpperCase(Locale.ROOT));
        return PageResponse.of(cases.findByStatusInOrderByUrgentDescCreatedAtAsc(states,
                PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 50 : Math.min(size, 100))),
                sc -> new SupportDTOs.CaseRow(sc.getCaseNo(), orderNo(sc), sc.getTopic(), sc.getStatus(), sc.isUrgent(),
                        sellerName(sc.getSellerOrgId()), sc.getCreatedAt(), sc.getUpdatedAt()));
    }

    @Transactional(readOnly = true)
    public SupportDTOs.CaseView operatorView(String caseNo) {
        access.assertOperator();
        return fullView(caseByNo(caseNo));
    }

    @Transactional
    public SupportDTOs.CaseView reply(SupportDTOs.ReplyRequest req) {
        access.assertOperator();
        MarketplaceSupportCase sc = caseByNo(req == null ? null : req.caseNo());
        String b = clip(req.body(), 2000);
        if (b == null) throw new ValidationException("Write the reply.");
        boolean internal = Boolean.TRUE.equals(req.internal());
        message(sc.getId(), MarketplaceSupportMessage.OPERATOR, access.userId(), b, !internal);
        if (!internal) {
            sc.setStatus(MarketplaceSupportCase.WAITING_CUSTOMER);
            cases.save(sc);
        }
        audit.event(MarketplaceAuditService.CASE_REPLIED, MarketplaceAuditService.ENTITY_CASE, sc.getCaseNo(), sc.getSellerOrgId(),
                Actor.OPERATOR, null, internal ? "INTERNAL" : "TO_CUSTOMER", null, null);
        return fullView(sc);
    }

    @Transactional
    public SupportDTOs.CaseView task(SupportDTOs.TaskRequest req) {
        access.assertOperator();
        MarketplaceSupportCase sc = caseByNo(req == null ? null : req.caseNo());
        String note = clip(req.note(), 2000);
        if (note == null) throw new ValidationException("Tell the seller what to do.");
        if (MarketplaceSupportCase.RESOLVED.equals(sc.getStatus())) throw new ValidationException("This case is resolved.");
        String before = sc.getStatus();
        sc.setStatus(MarketplaceSupportCase.WAITING_SELLER);
        cases.save(sc);
        message(sc.getId(), MarketplaceSupportMessage.OPERATOR, access.userId(), "Task for the seller: " + note, false);
        audit.event(MarketplaceAuditService.CASE_TASKED, MarketplaceAuditService.ENTITY_CASE, sc.getCaseNo(), sc.getSellerOrgId(),
                Actor.OPERATOR, before, sc.getStatus(), null, note);
        return fullView(sc);
    }

    @Transactional
    public SupportDTOs.CaseView resolve(SupportDTOs.ResolveRequest req) {
        access.assertOperator();
        MarketplaceSupportCase sc = caseByNo(req == null ? null : req.caseNo());
        String note = clip(req.note(), 500);
        if (note == null) throw new ValidationException("Say how it was resolved.");
        boolean pending = returns.findByCaseIdOrderByIdAsc(sc.getId()).stream().anyMatch(r -> RETURN_IN_PROGRESS.contains(r.getStatus()));
        if (pending) throw new ValidationException("A return on this case is still in progress. Finish or reject it first.");
        String before = sc.getStatus();
        sc.setStatus(MarketplaceSupportCase.RESOLVED);
        sc.setResolution(note);
        cases.save(sc);
        message(sc.getId(), MarketplaceSupportMessage.OPERATOR, access.userId(), note, true);
        audit.event(MarketplaceAuditService.CASE_RESOLVED, MarketplaceAuditService.ENTITY_CASE, sc.getCaseNo(), sc.getSellerOrgId(),
                Actor.OPERATOR, before, MarketplaceSupportCase.RESOLVED, null, note);
        return fullView(sc);
    }

    /** Approve or reject a requested return. Asking again for the same decision changes nothing. */
    @Transactional
    public SupportDTOs.ReturnView decide(SupportDTOs.DecisionRequest req) {
        access.assertOperator();
        MarketplaceReturn r = returnByNo(req == null ? null : req.returnNo());
        String d = req.decision() == null ? "" : req.decision().trim().toUpperCase(Locale.ROOT);
        if (!MarketplaceReturn.APPROVED.equals(d) && !MarketplaceReturn.REJECTED.equals(d))
            throw new ValidationException("Choose Approve or Reject.");
        if (d.equals(r.getStatus())) return view(r);
        if (!MarketplaceReturn.REQUESTED.equals(r.getStatus()))
            throw new ValidationException("This return is already " + r.getStatus().toLowerCase(Locale.ROOT) + ".");
        String note = clip(req.note(), 500);
        if (MarketplaceReturn.REJECTED.equals(d) && note == null) throw new ValidationException("Tell the customer why it is not approved.");
        r.setStatus(d);
        r.setDecisionNote(note);
        r.setDecidedByUserId(access.userId());
        returns.save(r);
        MarketplaceSupportCase sc = cases.findById(r.getCaseId()).orElseThrow();
        sc.setStatus(MarketplaceReturn.APPROVED.equals(d) ? MarketplaceSupportCase.WAITING_SELLER : MarketplaceSupportCase.WAITING_CUSTOMER);
        cases.save(sc);
        message(sc.getId(), MarketplaceSupportMessage.OPERATOR, access.userId(), MarketplaceReturn.APPROVED.equals(d)
                ? "Your return " + r.getReturnNo() + " is approved. The seller's rider will collect it from your address."
                        + cashNote(r) + (note == null ? "" : " " + note)
                : "Your return " + r.getReturnNo() + " was not approved: " + note, true);
        audit.event(MarketplaceAuditService.RETURN_DECIDED, MarketplaceAuditService.ENTITY_RETURN, r.getReturnNo(), r.getSellerOrgId(),
                Actor.OPERATOR, MarketplaceReturn.REQUESTED, d, r.getRefundAmount(), note);
        return view(r);
    }

    // ── seller ─────────────────────────────────────────────────────────────────────────────────────────

    /** The seller's tasks: its own cases waiting on it, and its approved returns waiting for "Item received". */
    @Transactional(readOnly = true)
    public List<SupportDTOs.SellerTask> tasks() {
        Long org = access.org();
        List<MarketplaceSupportCase> mine = new ArrayList<>(cases.findTop100BySellerOrgIdAndStatusOrderByCreatedAtAsc(org,
                MarketplaceSupportCase.WAITING_SELLER));
        for (MarketplaceReturn r : returns.findTop100BySellerOrgIdAndStatusOrderByCreatedAtAsc(org, MarketplaceReturn.APPROVED)) {
            if (mine.stream().noneMatch(x -> x.getId().equals(r.getCaseId()))) cases.findById(r.getCaseId()).ifPresent(mine::add);
        }
        return mine.stream().map(this::sellerTask).toList();
    }

    @Transactional
    public SupportDTOs.SellerTask taskReply(SupportDTOs.TaskReplyRequest req) {
        Long org = access.org();
        MarketplaceSupportCase sc = cases.findByCaseNo(norm(req == null ? null : req.caseNo()))
                .filter(x -> x.getSellerOrgId().equals(org)).orElseThrow(() -> new ResourceNotFoundException(NO_CASE));
        String b = clip(req.body(), 2000);
        if (b == null) throw new ValidationException("Write your answer.");
        // Relayed to the customer as MaxTheService support (never the seller's name or number); back to the operator.
        message(sc.getId(), MarketplaceSupportMessage.SELLER, access.userId(), b, true);
        sc.setStatus(MarketplaceSupportCase.OPEN);
        cases.save(sc);
        audit.event(MarketplaceAuditService.CASE_REPLIED, MarketplaceAuditService.ENTITY_CASE, sc.getCaseNo(), org,
                Actor.SELLER, MarketplaceSupportCase.WAITING_SELLER, MarketplaceSupportCase.OPEN, null, null);
        return sellerTask(sc);
    }

    /**
     * The rider has the item. Claims the return (RECEIVED), raises the credit note on the seller's invoice once, then
     * refunds: by card through the provider, once; cash on delivery is handed back at pickup (R-MKT-12). A second press
     * after the refund changes nothing.
     */
    public SupportDTOs.ReturnView received(SupportDTOs.ReceivedRequest req) {
        Long org = access.org();
        Long user = access.userId();
        String outcome = req == null || req.outcome() == null ? "" : req.outcome().trim().toUpperCase(Locale.ROOT);
        Long id = tx().execute(s -> {
            MarketplaceReturn r = returns.findByReturnNo(norm(req == null ? null : req.returnNo()))
                    .filter(x -> x.getSellerOrgId().equals(org)).orElseThrow(() -> new ResourceNotFoundException(NO_RETURN));
            if (MarketplaceReturn.REFUNDED.equals(r.getStatus()) || MarketplaceReturn.RECEIVED.equals(r.getStatus())) return r.getId();
            if (!MarketplaceReturn.APPROVED.equals(r.getStatus()))
                throw new ValidationException("Only an approved return can be received. This one is " + r.getStatus().toLowerCase(Locale.ROOT) + ".");
            if (!OUTCOMES.contains(outcome))
                throw new ValidationException("Choose what happens to the item: restock, quarantine or write off.");
            MarketplaceOrder o = orders.findById(r.getMktOrderId()).orElseThrow();
            boolean cash = !MarketplaceReturn.CARD.equals(channel(o));
            if (cash && !Boolean.TRUE.equals(req.cashHandedBack()))
                throw new ValidationException("This order was paid in cash: hand Rs " + money(r.getRefundAmount())
                        + " back to the customer at pickup, then tick \"Cash handed back\".");
            r.setStatus(MarketplaceReturn.RECEIVED);
            r.setOutcome(outcome);
            r.setReceivedByUserId(user);
            returns.save(r);
            audit.event(MarketplaceAuditService.RETURN_RECEIVED, MarketplaceAuditService.ENTITY_RETURN, r.getReturnNo(), org,
                    Actor.SELLER, MarketplaceReturn.APPROVED, outcome, r.getRefundAmount(), null);
            return r.getId();
        });
        MarketplaceReturn r = returns.findById(id).orElseThrow();
        if (MarketplaceReturn.REFUNDED.equals(r.getStatus())) return view(r);

        MarketplaceOrder o = orders.findById(r.getMktOrderId()).orElseThrow();
        if (r.getCreditNoteNo() == null) {
            MarketplaceOrderLine line = lines.findById(r.getOrderLineId()).orElseThrow();
            MarketplaceSellerOrder so = sellerOrder(o);
            String notes = storeOrders.marketplaceReturn(so.getStoreOrderId(), org, line.getSourceProductId(), r.getQuantity(),
                    !"RESTOCK".equals(r.getOutcome()), "Marketplace return " + r.getReturnNo() + " (" + r.getReason() + ")");
            tx().executeWithoutResult(s -> {
                MarketplaceReturn f = returns.findById(id).orElseThrow();
                f.setCreditNoteNo(notes == null || notes.isBlank() ? "—" : notes);
                returns.save(f);
            });
        }

        boolean card = MarketplaceReturn.CARD.equals(channel(o));
        boolean refunded = !card || payments.refundReturn(o.getId(), id, r.getRefundAmount(), "Return " + r.getReturnNo());
        return tx().execute(s -> {
            MarketplaceReturn f = returns.findById(id).orElseThrow();
            f.setRefundChannel(card ? MarketplaceReturn.CARD : MarketplaceReturn.CASH_AT_PICKUP);
            if (refunded) f.setStatus(MarketplaceReturn.REFUNDED);
            returns.save(f);
            if (refunded) {
                MarketplaceSupportCase sc = cases.findById(f.getCaseId()).orElseThrow();
                sc.setStatus(MarketplaceSupportCase.OPEN);
                cases.save(sc);
                message(sc.getId(), MarketplaceSupportMessage.SYSTEM, null, card
                        ? "Your refund of Rs " + money(f.getRefundAmount()) + " for return " + f.getReturnNo() + " is on its way to your card."
                        : "The rider handed back Rs " + money(f.getRefundAmount()) + " for return " + f.getReturnNo() + ".", true);
                audit.event(MarketplaceAuditService.RETURN_REFUNDED, MarketplaceAuditService.ENTITY_RETURN, f.getReturnNo(), org,
                        Actor.SELLER, MarketplaceReturn.RECEIVED, f.getRefundChannel(), f.getRefundAmount(), f.getCreditNoteNo());
            } else {
                LOG.warn("MKT return {}: credit note {} raised, card refund pending — the sweeper retries", f.getReturnNo(), f.getCreditNoteNo());
            }
            return view(f);
        });
    }

    // ── the return itself ──────────────────────────────────────────────────────────────────────────────

    private void openReturn(MarketplaceSupportCase sc, MarketplaceOrder o, MarketplaceSellerOrder so, SupportDTOs.OpenCaseRequest req) {
        ReturnCostPolicy.Reason reason;
        try {
            reason = ReturnCostPolicy.Reason.valueOf(req.reason() == null ? "" : req.reason().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Choose why you are returning it.");
        }
        MarketplaceOrderLine line = req.lineId() == null ? null : lines.findById(req.lineId())
                .filter(l -> l.getSellerOrderId().equals(so.getId())).orElse(null);
        if (line == null) throw new ValidationException("Choose the item you are returning.");
        int qty = req.quantity() == null ? line.getQuantity() : req.quantity();
        if (qty < 1 || qty > line.getQuantity())
            throw new ValidationException("You can return 1 to " + line.getQuantity() + " of this item.");
        if (so.getDeliveredAt() == null) throw new ValidationException("You can return an item once it has been delivered.");
        if (returns.existsByOrderLineIdAndStatusIn(line.getId(), RETURN_IN_PROGRESS))
            throw new ValidationException("This item already has a return in progress.");
        if (reason == ReturnCostPolicy.Reason.CHANGE_OF_MIND) {
            int days = line.getReturnDays() == null ? 0 : line.getReturnDays();           // the order's snapshot, never today's policy
            LocalDateTime ends = so.getDeliveredAt().plusDays(days);
            if (days <= 0 || LocalDateTime.now().isAfter(ends))
                throw new ValidationException("The return period for this item ended on " + DAY.format(ends)
                        + ". If it is faulty or not as described, choose that reason instead.");
        }
        ReturnCostPolicy.Party bearer = ReturnCostPolicy.bearerFor(reason);
        // Phase 1: the seller's own rider delivers (R-MKT-13), so the carrier is the fulfiller; the platform org is MaxTheService's.
        Long bearerOrg = ReturnCostPolicy.bearerOrganization(reason, new ReturnCostPolicy.PartySnapshot(line.getSellerOrganizationId(),
                line.getStockOwnerOrganizationId(), line.getCustodianOrganizationId(), line.getFulfillerOrganizationId(),
                line.getFulfillerOrganizationId(), MarketplaceCheckoutService.PLATFORM_ORG));

        BigDecimal amount = line.getUnitPrice().multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deduction = bearer == ReturnCostPolicy.Party.CUSTOMER ? settings.changeOfMindFee().min(amount) : BigDecimal.ZERO.setScale(2);
        MarketplaceReturn r = new MarketplaceReturn();
        r.setReturnNo(String.format(Locale.ROOT, "RT-%06d", numbers.next(MarketplaceCheckoutService.PLATFORM_ORG, RETURN_DOC)));
        r.setCaseId(sc.getId());
        r.setMktOrderId(o.getId());
        r.setOrderLineId(line.getId());
        r.setSellerOrgId(so.getSellerOrganizationId());
        r.setQuantity(qty);
        r.setReason(reason.name());
        r.setBearerRole(bearer.name());
        r.setBearerOrgId(bearerOrg);
        r.setStatus(MarketplaceReturn.REQUESTED);
        r.setLineAmount(amount);
        r.setDeduction(deduction);
        r.setRefundAmount(amount.subtract(deduction));
        returns.save(r);
        if (ReturnCostPolicy.requiresUrgentEscalation(reason)) {
            sc.setUrgent(true);
            cases.save(sc);
        }
        message(sc.getId(), MarketplaceSupportMessage.SYSTEM, null, "Return " + r.getReturnNo() + " requested: " + qty + " × "
                + line.getProductName() + ". " + (deduction.signum() > 0 ? "A pickup fee of Rs " + money(deduction) + " is deducted from the refund. " : "")
                + "MaxTheService will confirm the pickup.", true);
        audit.event(MarketplaceAuditService.RETURN_OPENED, MarketplaceAuditService.ENTITY_RETURN, r.getReturnNo(), r.getSellerOrgId(),
                Actor.CUSTOMER, null, reason.name(), r.getRefundAmount(), "bearer " + bearer.name());
    }

    // ── views ──────────────────────────────────────────────────────────────────────────────────────────

    private SupportDTOs.CaseView customerView(MarketplaceSupportCase sc) {
        List<SupportDTOs.MessageView> ms = messages.findByCaseIdAndVisibleToCustomerTrueOrderByIdAsc(sc.getId()).stream()
                .map(m -> new SupportDTOs.MessageView(MarketplaceSupportMessage.CUSTOMER.equals(m.getAuthorKind()) ? "You" : SUPPORT,
                        m.getBody(), false, m.getCreatedAt()))
                .toList();
        return new SupportDTOs.CaseView(sc.getCaseNo(), orderNo(sc), sc.getTopic(), sc.getStatus(), sc.isUrgent(), sc.getCreatedAt(),
                ms, returns.findByCaseIdOrderByIdAsc(sc.getId()).stream().map(this::customerReturnView).toList());
    }

    private SupportDTOs.CaseView fullView(MarketplaceSupportCase sc) {
        List<SupportDTOs.MessageView> ms = messages.findByCaseIdOrderByIdAsc(sc.getId()).stream()
                .map(m -> new SupportDTOs.MessageView(role(m.getAuthorKind()), m.getBody(), !m.isVisibleToCustomer(), m.getCreatedAt()))
                .toList();
        return new SupportDTOs.CaseView(sc.getCaseNo(), orderNo(sc), sc.getTopic(), sc.getStatus(), sc.isUrgent(), sc.getCreatedAt(),
                ms, returns.findByCaseIdOrderByIdAsc(sc.getId()).stream().map(this::view).toList());
    }

    private SupportDTOs.SellerTask sellerTask(MarketplaceSupportCase sc) {
        MarketplaceOrder o = orders.findById(sc.getMktOrderId()).orElseThrow();
        List<SupportDTOs.MessageView> ms = messages.findByCaseIdOrderByIdAsc(sc.getId()).stream()
                .filter(m -> !MarketplaceSupportMessage.OPERATOR.equals(m.getAuthorKind()) || m.getBody().startsWith("Task for the seller")
                        || m.isVisibleToCustomer())
                .map(m -> new SupportDTOs.MessageView(role(m.getAuthorKind()), m.getBody(), false, m.getCreatedAt()))
                .toList();
        return new SupportDTOs.SellerTask(sc.getCaseNo(), o.getOrderNo(), sc.getTopic(), sc.getStatus(), o.getCustomerName(),
                o.getCustomerPhone(), o.getDeliveryAddress(), o.getCity(), ms,
                returns.findByCaseIdOrderByIdAsc(sc.getId()).stream().map(this::view).toList(), sc.getCreatedAt());
    }

    SupportDTOs.ReturnView view(MarketplaceReturn r) {
        String product = lines.findById(r.getOrderLineId()).map(MarketplaceOrderLine::getProductName).orElse(null);
        String bearerName = r.getBearerOrgId() == null ? null
                : r.getBearerOrgId() == MarketplaceCheckoutService.PLATFORM_ORG ? "MaxTheService" : sellerName(r.getBearerOrgId());
        return new SupportDTOs.ReturnView(r.getReturnNo(), r.getStatus(), r.getReason(), r.getQuantity(), product, r.getBearerRole(),
                bearerName, r.getLineAmount(), r.getDeduction(), r.getRefundAmount(), r.getRefundChannel(), r.getOutcome(),
                r.getCreditNoteNo(), r.getDecisionNote(), r.getCreatedAt());
    }

    /** The customer's view of a return: no credit note, no bearer organisation (a seller's name is not theirs to know). */
    private SupportDTOs.ReturnView customerReturnView(MarketplaceReturn r) {
        SupportDTOs.ReturnView v = view(r);
        return new SupportDTOs.ReturnView(v.returnNo(), v.status(), v.reason(), v.quantity(), v.productName(), v.bearerRole(),
                v.bearerRole().equals(ReturnCostPolicy.Party.CUSTOMER.name()) ? "You" : SUPPORT, v.lineAmount(), v.deduction(),
                v.refundAmount(), v.refundChannel(), v.outcome(), null, v.decisionNote(), v.createdAt());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private MarketplaceOrder ownOrder(MarketplaceCustomer c, String orderNo) {
        MarketplaceOrder o = orderNo == null ? null : orders.findByOrderNo(norm(orderNo)).orElse(null);
        if (o == null || o.getCustomerId() == null || !o.getCustomerId().equals(c.getId())) throw new ResourceNotFoundException(NO_ORDER);
        return o;
    }

    private MarketplaceSupportCase ownCase(MarketplaceCustomer c, String caseNo) {
        return cases.findByCaseNo(norm(caseNo)).filter(sc -> c.getId().equals(sc.getCustomerId()))
                .orElseThrow(() -> new ResourceNotFoundException(NO_CASE));
    }

    private MarketplaceSupportCase caseByNo(String caseNo) {
        return cases.findByCaseNo(norm(caseNo)).orElseThrow(() -> new ResourceNotFoundException(NO_CASE));
    }

    private MarketplaceReturn returnByNo(String returnNo) {
        return returns.findByReturnNo(norm(returnNo)).orElseThrow(() -> new ResourceNotFoundException(NO_RETURN));
    }

    private MarketplaceSellerOrder sellerOrder(MarketplaceOrder o) {
        return sellerOrders.findByMktOrderId(o.getId()).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException(NO_ORDER));
    }

    private void message(Long caseId, String kind, Long ref, String body, boolean visible) {
        MarketplaceSupportMessage m = new MarketplaceSupportMessage();
        m.setCaseId(caseId);
        m.setAuthorKind(kind);
        m.setAuthorRef(ref);
        m.setBody(body.length() > 2000 ? body.substring(0, 2000) : body);
        m.setVisibleToCustomer(visible);
        messages.save(m);
    }

    private String orderNo(MarketplaceSupportCase sc) {
        return orders.findById(sc.getMktOrderId()).map(MarketplaceOrder::getOrderNo).orElse(null);
    }

    private String sellerName(Long org) {
        return sellerAccounts.findByOrganizationId(org).map(a -> a.getDisplayName()).orElse(null);
    }

    private static String channel(MarketplaceOrder o) {
        return "CARD".equals(o.getPaymentMode()) ? MarketplaceReturn.CARD : MarketplaceReturn.CASH_AT_PICKUP;
    }

    private String cashNote(MarketplaceReturn r) {
        MarketplaceOrder o = orders.findById(r.getMktOrderId()).orElse(null);
        return o != null && !"CARD".equals(o.getPaymentMode())
                ? " The rider hands you Rs " + money(r.getRefundAmount()) + " in cash when collecting it." : "";
    }

    private static String role(String kind) {
        return switch (kind) {
            case MarketplaceSupportMessage.CUSTOMER -> "Customer";
            case MarketplaceSupportMessage.SELLER -> "Seller";
            case MarketplaceSupportMessage.SYSTEM -> "System";
            default -> SUPPORT;
        };
    }

    private static String norm(String no) {
        return no == null ? "" : no.trim().toUpperCase(Locale.ROOT);
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.0f", v);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }
}
