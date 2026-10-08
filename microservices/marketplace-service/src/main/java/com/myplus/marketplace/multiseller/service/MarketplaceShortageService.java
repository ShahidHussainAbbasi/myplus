package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Order;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.ShortageResult;
import com.myplus.marketplace.multiseller.domain.OfferCandidate;
import com.myplus.marketplace.multiseller.domain.SubstitutionPolicy;
import com.myplus.marketplace.multiseller.dto.MarketplaceOrderDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceShortage;
import com.myplus.marketplace.multiseller.entity.MarketplaceShortage.Cause;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceShortageRepository;
import com.myplus.marketplace.support.AsOrg;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2b — a seller's part that was not fulfilled (rejected, or not accepted in time). Source §11, §12.4.
 * Contract: docs/slices/mkt-2b-shortage-reroute.md
 *
 * <h3>Every unfulfilled part is recorded</h3>
 * {@link #begin} runs INSIDE the transaction that moved the part to REJECTED or EXPIRED and writes one
 * {@link MarketplaceShortage}: the cause and responsible party (R11.4). That record is never a charge (R12.4): the seller
 * may dispute it and an operator decides ({@link #dispute}, {@link #rule}).
 *
 * <h3>With the operator's switch on, the part is moved before the order can end</h3>
 * The part is marked {@code shortagePending}, which {@link MarketplaceCheckoutService#follow} counts as waiting, and
 * {@link #resolve} runs after the commit, outside any transaction:
 * <ol>
 *   <li>Candidates: the SAME canonical products (so never another variant, colour, size or brand — R11.3) from a seller
 *       not already in this order, eligible for the city and quantity exactly as the catalogue shows them; at most
 *       {@link #MAX_CANDIDATES} are tried (R18.1).</li>
 *   <li>Same or lower price AND same or earlier promise ({@link SubstitutionPolicy}) → held and offered to the new seller
 *       at once: REASSIGNED. A cheaper total refunds the difference to a card.</li>
 *   <li>Otherwise → held, and the shopper is asked ({@link #PROPOSAL_MINUTES} to answer): SUBSTITUTION_REQUESTED. A card
 *       order is never asked to pay more: its token is not kept, so only an alternative that costs no more is offered.</li>
 *   <li>Nothing → the part ends: LINE_CANCELLED, or ORDER_CANCELLED when it was the last; its money goes back.</li>
 * </ol>
 * A crash between the steps leaves the record PENDING; {@link #sweep} resolves it again, and ends it after
 * {@link #MAX_ATTEMPTS}. Every transition is made on freshly read rows with their {@code @Version}.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceShortageService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplaceShortageService.class);

    static final int MAX_CANDIDATES = 3;
    static final int PROPOSAL_MINUTES = 30;
    static final int MAX_ATTEMPTS = 3;
    static final Duration RETRY_AFTER = Duration.ofMinutes(2);
    static final int BATCH = 50;

    static final String DECLINED_FOR_SHOPPER = "You declined the alternative offered for this order.";
    static final String EXPIRED_FOR_SHOPPER = "The alternative offered for this order was not confirmed in time.";

    private final MarketplaceShortageRepository shortages;
    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceOrderRepository orders;
    private final MarketplaceOrderLineRepository lines;
    private final MarketplaceOfferRepository offers;
    private final MarketplaceOfferProjectionRepository projections;
    private final MarketplaceProductRepository products;
    private final PublicOfferService publicOffers;
    private final MarketplaceSettingsService settings;
    private final MarketplaceCheckoutService checkout;
    private final MarketplacePaymentService payments;
    private final TradeClient trade;
    private final SellerAccess access;
    private final MarketplaceAuditService audit;
    private final PlatformTransactionManager txManager;
    /** The seller side calls {@link #begin}; this calls back for its release and refund, so it is resolved on demand. */
    private final ObjectProvider<SellerOrderService> sellerSide;

    // ── 1. record, inside the caller's transaction ───────────────────────────────────────────────────────

    /**
     * Record why {@code part} was not fulfilled and decide, inside the caller's transaction, whether the order waits
     * for another seller. Applies {@link MarketplaceCheckoutService#follow} to {@code parent}; the caller saves both.
     *
     * @return true when the caller must {@link #resolve} after its commit (instead of refunding the part)
     */
    boolean begin(MarketplaceSellerOrder part, MarketplaceOrder parent, List<MarketplaceSellerOrder> parts, Cause cause,
            String evidence, String reasonIfEnded) {
        MarketplaceShortage sh = shortages.findBySellerOrderId(part.getId()).orElse(null);
        if (sh != null) return MarketplaceShortage.PENDING.equals(sh.getResult());           // a repeated call
        sh = new MarketplaceShortage();
        sh.setMktOrderId(part.getMktOrderId());
        sh.setSellerOrderId(part.getId());
        sh.setSellerOrganizationId(part.getSellerOrganizationId());
        sh.setCause(cause.name());
        sh.setResponsibleRole(cause.party);
        sh.setResponsibleOrgId("MERCHANT".equals(cause.party) ? part.getSellerOrganizationId() : null);
        sh.setEvidence(cut(evidence, 500));
        sh.setStatus(MarketplaceShortage.RECORDED);
        boolean reroute = settings.reroute() && !Order.CANCELLED.name().equals(parent.getStatus());
        part.setShortagePending(reroute);
        MarketplaceCheckoutService.follow(parent, parts, reasonIfEnded);
        if (reroute) {
            sh.setResult(MarketplaceShortage.PENDING);
        } else {
            sh.setResult(ended(parent));
            sh.setResolvedAt(LocalDateTime.now());
        }
        shortages.save(sh);
        return reroute;
    }

    // ── 2. resolve, after the commit, outside any transaction ────────────────────────────────────────────

    /** Look for another seller for the short part. Safe to repeat: only a PENDING record moves. */
    public void resolve(Long sellerOrderId) {
        MarketplaceShortage sh = shortages.findBySellerOrderId(sellerOrderId).orElse(null);
        if (sh == null || !MarketplaceShortage.PENDING.equals(sh.getResult())) return;
        tx().executeWithoutResult(s -> shortages.findById(sh.getId()).ifPresent(f -> {
            f.setAttempts(f.getAttempts() + 1);
            shortages.save(f);
        }));
        MarketplaceSellerOrder part = sellerOrders.findById(sellerOrderId).orElseThrow();
        MarketplaceOrder o = orders.findById(part.getMktOrderId()).orElseThrow();
        List<MarketplaceOrderLine> ls = lines.findBySellerOrderIdOrderByIdAsc(part.getId());
        BigDecimal was = MarketplaceCheckoutService.partTotal(ls);
        boolean card = MarketplaceCheckoutService.CARD.equals(o.getPaymentMode());

        for (Candidate c : candidates(o, ls, card, was)) {
            String holdKey = "MKT-" + UUID.randomUUID();
            if (hold(c, holdKey) != null) continue;                           // that seller cannot hold it: the next one
            boolean done;
            try {
                done = c.silent() ? reassign(sh.getId(), c, holdKey) : propose(sh.getId(), c, holdKey);
            } catch (RuntimeException e) {
                LOG.warn("MKT shortage {}: could not record {} ({}); released", sh.getId(), c.seller(), e.toString());
                done = false;
            }
            if (!done) {
                releaseKey(c.seller(), holdKey);                              // someone else resolved it meanwhile
                return;
            }
            if (c.silent()) {
                BigDecimal back = was.subtract(c.total());
                if (card && back.signum() > 0)
                    payments.refundPart(o.getId(), part.getId(), back, "Price difference on " + o.getOrderNo());
                audit.event("MKT_ORDER_REROUTED", "MKT_SELLER_ORDER", o.getOrderNo(), c.seller(),
                        MarketplaceAuditService.Actor.SYSTEM, ShortageResult.REASSIGNED.name(), SellerOrder.OFFERED.name(),
                        c.total(), "from seller " + part.getSellerOrganizationId());
            } else {
                audit.event("MKT_SUBSTITUTION_OFFERED", "MKT_SELLER_ORDER", o.getOrderNo(), c.seller(),
                        MarketplaceAuditService.Actor.SYSTEM, MarketplaceShortage.PENDING,
                        ShortageResult.SUBSTITUTION_REQUESTED.name(), c.total(), null);
            }
            return;
        }
        end(sh.getId());
    }

    /** One seller who can supply every line of the part, priced and promised as its live offers are now. */
    record Candidate(Long seller, List<NewLine> lines, BigDecimal total, int promiseHours, boolean silent) {
    }

    record NewLine(MarketplaceOffer offer, MarketplaceProduct product, BigDecimal price, int qty, int promiseHours) {
        BigDecimal total() {
            return price.multiply(BigDecimal.valueOf(qty));
        }
    }

    /**
     * Sellers not already in this order who can supply EVERY line of the part (the same canonical products), as the
     * catalogue would offer them to this city and quantity. Silent moves first, then by total and promise.
     */
    List<Candidate> candidates(MarketplaceOrder o, List<MarketplaceOrderLine> ls, boolean card, BigDecimal was) {
        Set<Long> inOrder = sellerOrders.findByMktOrderId(o.getId()).stream()
                .map(MarketplaceSellerOrder::getSellerOrganizationId).collect(Collectors.toSet());
        Map<Long, List<NewLine>> bySeller = null;
        Map<Long, Boolean> silent = new LinkedHashMap<>();
        for (MarketplaceOrderLine l : ls) {
            MarketplaceProduct product = products.findById(l.getMktProductId()).orElse(null);
            if (product == null || !PublicOfferService.visible(product)) return List.of();
            List<MarketplaceOfferProjection> rows = projections.findByMktProductIdAndStatusOrderByPriceAsc(
                    product.getId(), MarketplaceOfferProjection.LIVE);
            Map<Long, NewLine> best = new LinkedHashMap<>();
            for (OfferCandidate c : publicOffers.eligible(product, rows, o.getCity(), BigDecimal.valueOf(l.getQuantity()), null)) {
                if (inOrder.contains(c.sellerOrganizationId()) || best.containsKey(c.sellerOrganizationId())) continue;
                MarketplaceOffer offer = offers.findById(c.offerId()).orElse(null);
                if (offer == null) continue;
                best.put(c.sellerOrganizationId(), new NewLine(offer, product, c.price().setScale(2, RoundingMode.HALF_UP),
                        l.getQuantity(), c.promiseHours()));
            }
            Map<Long, List<NewLine>> next = new LinkedHashMap<>();
            for (Map.Entry<Long, NewLine> e : best.entrySet()) {
                if (bySeller != null && !bySeller.containsKey(e.getKey())) continue;     // must supply every line
                List<NewLine> acc = new ArrayList<>(bySeller == null ? List.of() : bySeller.get(e.getKey()));
                acc.add(e.getValue());
                next.put(e.getKey(), acc);
                NewLine n = e.getValue();
                // the same product, so the protected attributes are equal; price and promise decide (R11.3)
                boolean quiet = !SubstitutionPolicy.requiresCustomerApproval(Map.of(), Map.of(), l.getUnitPrice(), n.price(),
                        l.getPromiseHours() == null ? Integer.MAX_VALUE : l.getPromiseHours(), n.promiseHours());
                silent.merge(e.getKey(), quiet, Boolean::logicalAnd);
            }
            bySeller = next;
            if (bySeller.isEmpty()) return List.of();
        }
        if (bySeller == null) return List.of();
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<Long, List<NewLine>> e : bySeller.entrySet()) {
            BigDecimal total = e.getValue().stream().map(NewLine::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            int promise = e.getValue().stream().mapToInt(NewLine::promiseHours).max().orElse(0);
            boolean quiet = Boolean.TRUE.equals(silent.get(e.getKey()));
            if (card && !quiet && total.compareTo(was) > 0) continue;              // a card is never asked for more
            out.add(new Candidate(e.getKey(), e.getValue(), total, promise, quiet));
        }
        out.sort(Comparator.comparing((Candidate c) -> !c.silent()).thenComparing(Candidate::total)
                .thenComparingInt(Candidate::promiseHours));
        return out.size() > MAX_CANDIDATES ? out.subList(0, MAX_CANDIDATES) : out;
    }

    /** Hold the candidate's stock as that seller, under {@code key}. @return null when held, otherwise why not. */
    String hold(Candidate c, String key) {
        MarketplaceSellerOrder probe = new MarketplaceSellerOrder();
        probe.setSellerOrganizationId(c.seller());
        probe.setHoldKey(key);
        List<MarketplaceOrderLine> want = c.lines().stream().map(n -> {
            MarketplaceOrderLine l = new MarketplaceOrderLine();
            l.setSourceProductId(n.offer().getSourceProductId());
            l.setQuantity(n.qty());
            return l;
        }).toList();
        return checkout.hold(probe, want);
    }

    /** Silent move: a new part for the new seller, offered at once; the short part stops waiting. */
    private boolean reassign(Long shortageId, Candidate c, String holdKey) {
        Boolean ok = tx().execute(s -> {
            MarketplaceShortage sh = shortages.findById(shortageId).orElseThrow();
            if (!MarketplaceShortage.PENDING.equals(sh.getResult())) return false;
            MarketplaceSellerOrder part = sellerOrders.findById(sh.getSellerOrderId()).orElseThrow();
            MarketplaceOrder parent = orders.findById(sh.getMktOrderId()).orElseThrow();
            if (Order.CANCELLED.name().equals(parent.getStatus())) return false;
            MarketplaceSellerOrder np = newPart(parent, part, c.seller(), holdKey, c.lines());
            part.setShortagePending(false);
            sellerOrders.save(part);
            sh.setResult(ShortageResult.REASSIGNED.name());
            sh.setReplacementSellerOrderId(np.getId());
            sh.setResolvedAt(LocalDateTime.now());
            shortages.save(sh);
            reprice(parent, MarketplaceCheckoutService.partTotal(lines.findBySellerOrderIdOrderByIdAsc(part.getId())), c.total());
            MarketplaceCheckoutService.follow(parent, sellerOrders.findByMktOrderId(parent.getId()), null);
            orders.save(parent);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** Ask the shopper: the alternative is held for {@link #PROPOSAL_MINUTES}. */
    private boolean propose(Long shortageId, Candidate c, String holdKey) {
        Boolean ok = tx().execute(s -> {
            MarketplaceShortage sh = shortages.findById(shortageId).orElseThrow();
            if (!MarketplaceShortage.PENDING.equals(sh.getResult())) return false;
            sh.setResult(ShortageResult.SUBSTITUTION_REQUESTED.name());
            sh.setProposalSellerOrgId(c.seller());
            sh.setProposalHoldKey(holdKey);
            sh.setProposalHeld(true);
            sh.setProposalLines(c.lines().stream().map(n -> n.offer().getId() + ":" + n.qty() + ":" + n.price().toPlainString())
                    .collect(Collectors.joining(";")));
            sh.setProposalTotal(c.total());
            sh.setProposalPromiseHours(c.promiseHours());
            sh.setProposalExpiresAt(LocalDateTime.now().plusMinutes(PROPOSAL_MINUTES));
            shortages.save(sh);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** The new seller's part: snapshot lines of its own offers, the stock already held under {@code holdKey}. */
    private MarketplaceSellerOrder newPart(MarketplaceOrder parent, MarketplaceSellerOrder replaced, Long seller, String holdKey,
            List<NewLine> ls) {
        MarketplaceSellerOrder np = new MarketplaceSellerOrder();
        np.setMktOrderId(parent.getId());
        np.setSellerOrganizationId(seller);
        np.setAcceptanceStatus(SellerOrder.UNASSIGNED.name());
        np.setHoldKey(holdKey);
        np.setHeld(true);
        np.setReplacesSellerOrderId(replaced.getId());
        MarketplaceCheckoutService.move(np, SellerOrder.OFFERED);
        np.setAcceptBy(LocalDateTime.now().plusMinutes(settings.acceptMinutes()));
        np = sellerOrders.saveAndFlush(np);
        for (NewLine n : ls) lines.save(checkout.snapshot(np, n.offer(), n.product(), n.price(), n.qty()));
        return np;
    }

    /** The order's total follows what the shopper now pays: the short part's amount out, the new part's in. */
    private static void reprice(MarketplaceOrder parent, BigDecimal was, BigDecimal now) {
        BigDecimal delta = now.subtract(was);
        if (delta.signum() == 0) return;
        parent.setSubtotal(parent.getSubtotal().add(delta));
        parent.setTotal(parent.getTotal().add(delta));
    }

    // ── 3. the shopper answers ───────────────────────────────────────────────────────────────────────────

    /** Anonymous, proven by the order number AND its phone (as tracking is). */
    public MarketplaceOrderDTOs.OrderView decide(String orderNo, Long shortageId, MarketplaceOrderDTOs.ShortageDecision d) {
        MarketplaceOrder o = orderNo == null ? null : orders.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (o == null || d == null || d.phone() == null
                || !MarketplaceCheckoutService.digits(o.getCustomerPhone()).equals(MarketplaceCheckoutService.digits(d.phone())))
            throw new ResourceNotFoundException("No such order.");
        return answer(o, shortageId, d.accept());
    }

    /** The same answer from a signed-in shopper's My orders: the order is theirs by account, whatever phone it carries. */
    public MarketplaceOrderDTOs.OrderView decideAsCustomer(Long customerId, String orderNo, Long shortageId, Boolean accept) {
        MarketplaceOrder o = orderNo == null ? null : orders.findByOrderNo(orderNo.trim().toUpperCase(Locale.ROOT)).orElse(null);
        if (o == null || customerId == null || !customerId.equals(o.getCustomerId())) throw new ResourceNotFoundException("No such order.");
        return answer(o, shortageId, accept);
    }

    private MarketplaceOrderDTOs.OrderView answer(MarketplaceOrder o, Long shortageId, Boolean accept) {
        MarketplaceShortage sh = shortageId == null ? null : shortages.findById(shortageId).orElse(null);
        if (sh == null || !sh.getMktOrderId().equals(o.getId())) throw new ResourceNotFoundException("No such order.");
        if (accept == null) throw new ValidationException("Choose to accept or decline the alternative.");
        if (!ShortageResult.SUBSTITUTION_REQUESTED.name().equals(sh.getResult()) || sh.getCustomerDecision() != null) {
            if (MarketplaceShortage.ACCEPTED.equals(sh.getCustomerDecision()) && accept) return checkout.view(o);  // a repeat
            if (MarketplaceShortage.DECLINED.equals(sh.getCustomerDecision()) && !accept) return checkout.view(o);
            throw new ValidationException("This alternative is no longer open.");
        }
        if (!LocalDateTime.now().isBefore(sh.getProposalExpiresAt()))
            throw new ValidationException("This alternative expired. Your money for this part is returned.");
        if (accept) accept(sh.getId());
        else close(sh.getId(), MarketplaceShortage.DECLINED, DECLINED_FOR_SHOPPER);
        return checkout.view(orders.findById(o.getId()).orElseThrow());
    }

    private void accept(Long shortageId) {
        BigDecimal[] back = new BigDecimal[1];
        MarketplaceShortage done = tx().execute(s -> {
            MarketplaceShortage sh = shortages.findById(shortageId).orElseThrow();
            if (!ShortageResult.SUBSTITUTION_REQUESTED.name().equals(sh.getResult()) || sh.getCustomerDecision() != null)
                throw new ValidationException("This alternative is no longer open.");
            MarketplaceSellerOrder part = sellerOrders.findById(sh.getSellerOrderId()).orElseThrow();
            MarketplaceOrder parent = orders.findById(sh.getMktOrderId()).orElseThrow();
            if (Order.CANCELLED.name().equals(parent.getStatus())) throw new ValidationException("This order was cancelled.");
            List<NewLine> ls = proposalLines(sh);
            MarketplaceSellerOrder np = newPart(parent, part, sh.getProposalSellerOrgId(), sh.getProposalHoldKey(), ls);
            part.setShortagePending(false);
            sellerOrders.save(part);
            BigDecimal was = MarketplaceCheckoutService.partTotal(lines.findBySellerOrderIdOrderByIdAsc(part.getId()));
            back[0] = was.subtract(sh.getProposalTotal());
            sh.setCustomerDecision(MarketplaceShortage.ACCEPTED);
            sh.setCustomerDecidedAt(LocalDateTime.now());
            sh.setProposalHeld(false);                                    // the hold now belongs to the new part
            sh.setResult(ShortageResult.REASSIGNED.name());
            sh.setReplacementSellerOrderId(np.getId());
            sh.setResolvedAt(LocalDateTime.now());
            shortages.save(sh);
            reprice(parent, was, sh.getProposalTotal());
            MarketplaceCheckoutService.follow(parent, sellerOrders.findByMktOrderId(parent.getId()), null);
            orders.save(parent);
            return sh;
        });
        MarketplaceOrder o = orders.findById(done.getMktOrderId()).orElseThrow();
        if (MarketplaceCheckoutService.CARD.equals(o.getPaymentMode()) && back[0] != null && back[0].signum() > 0)
            payments.refundPart(o.getId(), done.getSellerOrderId(), back[0], "Price difference on " + o.getOrderNo());
        audit.event("MKT_SUBSTITUTION_ACCEPTED", "MKT_SELLER_ORDER", o.getOrderNo(), done.getProposalSellerOrgId(),
                MarketplaceAuditService.Actor.CUSTOMER, ShortageResult.SUBSTITUTION_REQUESTED.name(),
                ShortageResult.REASSIGNED.name(), done.getProposalTotal(), null);
    }

    /** Parse the proposal, priced as it was offered: the shopper approved THAT price, not today's. */
    private List<NewLine> proposalLines(MarketplaceShortage sh) {
        List<NewLine> out = new ArrayList<>();
        for (String part : sh.getProposalLines().split(";")) {
            String[] f = part.split(":");
            MarketplaceOffer offer = offers.findById(Long.valueOf(f[0])).orElseThrow();
            MarketplaceProduct product = products.findById(offer.getMktProductId()).orElseThrow();
            out.add(new NewLine(offer, product, new BigDecimal(f[2]), Integer.parseInt(f[1]),
                    offer.getPromiseHours() == null ? 0 : offer.getPromiseHours()));
        }
        return out;
    }

    /**
     * The proposal ends without a new part (declined, expired, or the whole order cancelled): the short part ends too,
     * the order follows, the proposal's hold and the part's money go back.
     */
    void close(Long shortageId, String decision, String reasonForShopper) {
        MarketplaceShortage done;
        try {
            done = tx().execute(s -> {
                MarketplaceShortage sh = shortages.findById(shortageId).orElseThrow();
                boolean open = ShortageResult.SUBSTITUTION_REQUESTED.name().equals(sh.getResult()) && sh.getCustomerDecision() == null;
                if (!open && !MarketplaceShortage.PENDING.equals(sh.getResult())) return null;
                if (open) {
                    sh.setCustomerDecision(decision);
                    sh.setCustomerDecidedAt(LocalDateTime.now());
                }
                finish(sh, reasonForShopper);
                return sh;
            });
        } catch (OptimisticLockingFailureException concurrent) {
            return;                                                       // the shopper and the sweeper met: one won
        }
        if (done == null) return;
        afterEnd(done);
    }

    /** No candidate, or too many failed attempts: the short part ends now, with the words its own ending had. */
    private void end(Long shortageId) {
        MarketplaceShortage sh = shortages.findById(shortageId).orElseThrow();
        close(shortageId, null, Cause.NO_RESPONSE.name().equals(sh.getCause())
                ? MarketplaceOrderSweeper.EXPIRED_FOR_SHOPPER : SellerOrderService.REJECTED_FOR_SHOPPER);
    }

    /** Inside a transaction: the short part stops waiting, the order follows, the outcome is recorded. */
    private void finish(MarketplaceShortage sh, String reasonForShopper) {
        MarketplaceSellerOrder part = sellerOrders.findById(sh.getSellerOrderId()).orElseThrow();
        part.setShortagePending(false);
        sellerOrders.save(part);
        MarketplaceOrder parent = orders.findById(sh.getMktOrderId()).orElseThrow();
        MarketplaceCheckoutService.follow(parent, sellerOrders.findByMktOrderId(parent.getId()).stream()
                .map(p -> p.getId().equals(part.getId()) ? part : p).toList(), reasonForShopper);
        orders.save(parent);
        sh.setResult(ended(parent));
        sh.setResolvedAt(LocalDateTime.now());
        shortages.save(sh);
    }

    /** After the commit: the proposal's hold goes back, then the short part's money. */
    private void afterEnd(MarketplaceShortage sh) {
        if (Boolean.TRUE.equals(sh.getProposalHeld())) releaseProposal(sh);
        sellerSide.getObject().moneyBack(sellerOrders.findById(sh.getSellerOrderId()).orElseThrow());
    }

    /**
     * MKT-2b — the shopper cancelled the whole order: every open shortage of it ends with the order, inside the
     * caller's transaction. @return the records whose proposal hold the caller releases after its commit.
     */
    List<MarketplaceShortage> cancelWithOrder(Long orderId) {
        List<MarketplaceShortage> open = new ArrayList<>();
        for (MarketplaceShortage sh : shortages.findByMktOrderIdOrderByIdAsc(orderId)) {
            boolean asking = ShortageResult.SUBSTITUTION_REQUESTED.name().equals(sh.getResult()) && sh.getCustomerDecision() == null;
            if (!asking && !MarketplaceShortage.PENDING.equals(sh.getResult())) continue;
            if (asking) {
                sh.setCustomerDecision(MarketplaceShortage.CANCELLED);
                sh.setCustomerDecidedAt(LocalDateTime.now());
            }
            sh.setResult(ShortageResult.ORDER_CANCELLED.name());
            sh.setResolvedAt(LocalDateTime.now());
            shortages.save(sh);
            sellerOrders.findById(sh.getSellerOrderId()).ifPresent(p -> {
                p.setShortagePending(false);
                sellerOrders.save(p);
            });
            open.add(sh);
        }
        return open;
    }

    void releaseProposal(MarketplaceShortage sh) {
        if (sh.getProposalHoldKey() == null || sh.getProposalSellerOrgId() == null) return;
        try {
            AsOrg.run(sh.getProposalSellerOrgId(), () -> trade.releaseHold(sh.getProposalHoldKey()));
            tx().executeWithoutResult(s -> shortages.findById(sh.getId()).ifPresent(f -> {
                f.setProposalHeld(false);
                shortages.save(f);
            }));
        } catch (RuntimeException e) {
            LOG.warn("MKT release of proposal {} failed; the sweeper retries: {}", sh.getProposalHoldKey(), e.toString());
        }
    }

    private void releaseKey(Long seller, String key) {
        try {
            AsOrg.run(seller, () -> trade.releaseHold(key));
        } catch (RuntimeException e) {
            LOG.warn("MKT release of {} failed: {}", key, e.toString());
        }
    }

    static String ended(MarketplaceOrder parent) {
        return Order.CANCELLED.name().equals(parent.getStatus()) ? ShortageResult.ORDER_CANCELLED.name()
                : ShortageResult.LINE_CANCELLED.name();
    }

    // ── 4. the clock ─────────────────────────────────────────────────────────────────────────────────────

    /** Proposals nobody answered, resolutions a crash interrupted, proposal holds a failed release left. */
    public int sweep() {
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (MarketplaceShortage sh : shortages.findByResultAndProposalExpiresAtBefore(
                ShortageResult.SUBSTITUTION_REQUESTED.name(), now, PageRequest.of(0, BATCH))) {
            if (sh.getCustomerDecision() != null) continue;
            close(sh.getId(), MarketplaceShortage.EXPIRED, EXPIRED_FOR_SHOPPER);
            n++;
        }
        for (MarketplaceShortage sh : shortages.findByResultAndCreatedAtBefore(MarketplaceShortage.PENDING,
                now.minus(RETRY_AFTER), PageRequest.of(0, BATCH))) {
            try {
                if (sh.getAttempts() >= MAX_ATTEMPTS) end(sh.getId());
                else resolve(sh.getSellerOrderId());
                n++;
            } catch (RuntimeException e) {
                LOG.warn("MKT shortage {} retry failed: {}", sh.getId(), e.toString());
            }
        }
        for (MarketplaceShortage sh : shortages.findByProposalHeldTrueAndResultNot(ShortageResult.SUBSTITUTION_REQUESTED.name(),
                PageRequest.of(0, BATCH))) {
            releaseProposal(sh);
            n++;
        }
        return n;
    }

    // ── 5. the record: dispute and ruling (R11.4, R12.4) ─────────────────────────────────────────────────

    /** The seller disputes the cause recorded against it. Another seller's record reads as missing. */
    @Transactional
    public MarketplaceOrderDTOs.SellerShortageView dispute(Long id, MarketplaceOrderDTOs.DisputeRequest req) {
        Long org = access.org();
        MarketplaceShortage sh = id == null ? null : shortages.findById(id).orElse(null);
        if (sh == null || !sh.getSellerOrganizationId().equals(org)) throw new ResourceNotFoundException("No such record.");
        String note = req == null || req.note() == null ? null : req.note().trim();
        if (note == null || note.isEmpty()) throw new ValidationException("Say why the cause is wrong. MaxTheService will review it.");
        if (note.length() > 500) throw new ValidationException("Keep the note under 500 characters.");
        if (!MarketplaceShortage.RECORDED.equals(sh.getStatus()))
            throw new ValidationException(MarketplaceShortage.DISPUTED.equals(sh.getStatus())
                    ? "You already disputed this. MaxTheService will decide." : "MaxTheService already decided this.");
        sh.setStatus(MarketplaceShortage.DISPUTED);
        sh.setDisputeNote(note);
        shortages.save(sh);
        audit.event("MKT_SHORTAGE_DISPUTED", "MKT_SHORTAGE", "SH-" + sh.getId(), org, MarketplaceAuditService.Actor.SELLER,
                MarketplaceShortage.RECORDED, MarketplaceShortage.DISPUTED, null, note);
        return sellerView(sh);
    }

    /** The operator decides a dispute. Neither outcome moves money: the record informs, it never debits (R12.4). */
    @Transactional
    public MarketplaceOrderDTOs.OperatorShortageView rule(Long id, MarketplaceOrderDTOs.ShortageRuling req) {
        access.assertOperator();
        MarketplaceShortage sh = id == null ? null : shortages.findById(id).orElse(null);
        if (sh == null) throw new ResourceNotFoundException("No such record.");
        String outcome = req == null || req.outcome() == null ? "" : req.outcome().trim().toUpperCase(Locale.ROOT);
        if (!MarketplaceShortage.UPHELD.equals(outcome) && !MarketplaceShortage.OVERTURNED.equals(outcome))
            throw new ValidationException("Choose to uphold or overturn the recorded cause.");
        if (!MarketplaceShortage.DISPUTED.equals(sh.getStatus()))
            throw new ValidationException("Only a disputed record is decided.");
        String note = req.note() == null ? null : req.note().trim();
        if (note == null || note.isEmpty()) throw new ValidationException("Write the reason for the seller.");
        sh.setStatus(outcome);
        sh.setDecisionNote(cut(note, 500));
        sh.setDecidedByUserId(access.userId());
        sh.setDecidedAt(LocalDateTime.now());
        shortages.save(sh);
        audit.event("MKT_SHORTAGE_RULED", "MKT_SHORTAGE", "SH-" + sh.getId(), sh.getSellerOrganizationId(),
                MarketplaceAuditService.Actor.OPERATOR, MarketplaceShortage.DISPUTED, outcome, null, note);
        return operatorView(sh);
    }

    /** The operator's list, oldest first; {@code status} empty = every record, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<MarketplaceOrderDTOs.OperatorShortageView> operatorList(String status, Integer page, Integer size) {
        access.assertOperator();
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 50 : Math.min(size, 100));
        if (status == null || status.isBlank()) return PageResponse.of(shortages.findAllByOrderByIdDesc(p), this::operatorView);
        String st = status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(MarketplaceShortage.RECORDED, MarketplaceShortage.DISPUTED, MarketplaceShortage.UPHELD,
                MarketplaceShortage.OVERTURNED).contains(st)) throw new ValidationException("Unknown status: " + status);
        return PageResponse.of(shortages.findByStatusInOrderByIdAsc(List.of(st), p), this::operatorView);
    }

    /** The seller's own record on its part; null when the part was fulfilled. */
    static MarketplaceOrderDTOs.SellerShortageView sellerView(MarketplaceShortage sh) {
        if (sh == null) return null;
        return new MarketplaceOrderDTOs.SellerShortageView(sh.getId(), sh.getCause(), sh.getResponsibleRole(), sh.getResult(),
                sh.getStatus(), sh.getDisputeNote(), sh.getDecisionNote(), MarketplaceShortage.RECORDED.equals(sh.getStatus()));
    }

    MarketplaceOrderDTOs.OperatorShortageView operatorView(MarketplaceShortage sh) {
        String orderNo = orders.findById(sh.getMktOrderId()).map(MarketplaceOrder::getOrderNo).orElse(null);
        Long minutes = sh.getResolvedAt() == null ? null : Duration.between(sh.getCreatedAt(), sh.getResolvedAt()).toMinutes();
        return new MarketplaceOrderDTOs.OperatorShortageView(sh.getId(), orderNo, sh.getSellerOrganizationId(),
                checkout.sellerName(sh.getSellerOrganizationId()), sh.getCause(), sh.getResponsibleRole(), sh.getEvidence(),
                sh.getResult(), sh.getStatus(), sh.getDisputeNote(), sh.getDecisionNote(), sh.getCustomerDecision(),
                sh.getCreatedAt(), sh.getResolvedAt(), minutes);
    }

    /** The seller-chosen cause, MERCHANT_STALE_STOCK when none is named; an operator's cause is refused here. */
    static Cause sellerCause(String given) {
        if (given == null || given.isBlank()) return Cause.MERCHANT_STALE_STOCK;
        Cause c;
        try {
            c = Cause.valueOf(given.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Choose why you cannot fulfil this order.");
        }
        if (!MarketplaceShortage.SELLER_CAUSES.contains(c)) throw new ValidationException("Choose why you cannot fulfil this order.");
        return c;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }
}
