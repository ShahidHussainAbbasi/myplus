package com.myplus.marketplace.multiseller.service;

import com.myplus.common.security.time.TenantClock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.commerce.domain.Money;
import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.BusinessDayCalendar;
import com.myplus.marketplace.multiseller.domain.CommissionPolicy;
import com.myplus.marketplace.multiseller.domain.MarketplaceStateMachines;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.LedgerEntryType;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Settlement;
import com.myplus.marketplace.multiseller.domain.SettlementCalculator;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplacePayout;
import com.myplus.marketplace.multiseller.entity.MarketplaceReturn;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceSettlementEntry;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePayoutRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;
import com.myplus.marketplace.multiseller.service.MarketplaceAuditService.Actor;
import com.myplus.marketplace.multiseller.service.MarketplaceSettingsService.Books;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1g — commission, the settlement ledger, T+N eligibility, manual payouts and the operator's journals (slice doc
 * {@code mkt-1g-settlement-payouts.md}; source §15, §16, §22.3).
 *
 * <h3>Nothing is payable before delivery and the return window (R15.2, R16.2)</h3>
 * A line waits NOT_ELIGIBLE until its seller order is delivered, then PENDING_RETURN_WINDOW until the return days it
 * was SOLD with (the snapshot, never today's policy) have passed and T+N business days more. A return in progress
 * holds it (ON_HOLD). Only then are its ledger rows written: the ledger never holds money that is not yet owed.
 *
 * <h3>The ledger is append-only (R15.6)</h3>
 * {@link MarketplaceSettlementEntryRepository} can insert and read, nothing else; a correction is a new ADJUSTMENT
 * row. Every row carries an idempotency key, so a settlement run that is repeated or races another writes each row
 * once (the unique key rolls the loser back).
 *
 * <h3>Both directions (R-MKT-2)</h3>
 * Paid online, the platform holds the customer's money: the seller is credited the SALE and debited the COMMISSION.
 * Cash on delivery, the seller's rider holds it: the same two rows plus COLLECTED_BY_SELLER, so the seller's balance
 * goes negative by the commission it owes. One balance, one control account (finance 2400).
 *
 * <h3>Payouts take two people (R16.3, R22.3)</h3>
 * Requested by one operator for the seller's whole positive balance, approved by ANOTHER, marked paid with the bank's
 * reference. A repeated request with the same key is the same payout; a seller has at most one payout in flight.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceSettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplaceSettlementService.class);

    /** Return window used when a line was sold with no return policy (design §6.2 returnWindowDays). */
    static final int DEFAULT_RETURN_DAYS = 7;
    static final int BATCH = 200;
    static final String PAYOUT_DOC = "PO";
    static final String ADJUSTMENT_DOC = "ADJ";
    static final String REMITTANCE_DOC = "RM";
    static final BigDecimal MAX_ADJUSTMENT = new BigDecimal("1000000.00");
    static final String CARD = "CARD";

    static final List<String> WAITING = List.of(Settlement.NOT_ELIGIBLE.name(), Settlement.PENDING_RETURN_WINDOW.name(),
            Settlement.ON_HOLD.name());
    static final Set<String> RETURN_IN_PROGRESS = MarketplaceSupportService.RETURN_IN_PROGRESS;
    static final List<String> IN_FLIGHT = List.of(MarketplacePayout.REQUESTED, MarketplacePayout.APPROVED);
    /** Seller orders that became sales: only their lines are on a statement. */
    static final List<String> ACCEPTED = List.of(com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder.ACCEPTED.name(),
            com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerOrder.HANDED_OVER.name());

    /** Weekends only: Pakistani banks' Saturday/Sunday. A holiday list is MKT-2f (settlement reports). */
    static final BusinessDayCalendar CALENDAR = BusinessDayCalendar.saturdaySundayWeekend(Set.of());

    private final MarketplaceOrderLineRepository lines;
    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceOrderRepository orders;
    private final MarketplaceReturnRepository returns;
    private final MarketplaceSettlementEntryRepository entries;
    private final MarketplacePayoutRepository payouts;
    private final MarketplaceSellerAccountRepository sellerAccounts;
    private final MarketplaceSettingsService settings;
    private final MarketplaceGlOutboxService gl;
    private final MarketplaceAuditService audit;
    private final DocumentNumberService numbers;
    private final SellerAccess access;
    private final PlatformTransactionManager txManager;
    private final CodStandingService cod;

    // ── the arithmetic (pure, given the rows) ──────────────────────────────────────────────────────────────

    /** A line's split, and who holds the customer's money. */
    record Figures(SettlementCalculator.Breakdown breakdown, boolean sellerCollected) {
    }

    /**
     * The line's split, through {@link SettlementCalculator} so the reconciliation identity holds by construction.
     *
     * <ul>
     *   <li>The customer amount is the line, plus the order's delivery fee on its FIRST line (Phase 1: the seller's
     *       rider delivers, so the fee is the seller's), less what was refunded on returns.</li>
     *   <li>Commission is on what was SOLD and kept: a returned item earns MaxTheService nothing. A change-of-mind pickup
     *       fee the customer paid stays with the seller, whose rider did the pickup.</li>
     *   <li>Fees, tax and reserve are zero in Phase 1: no processing fee or refund reserve is configured yet, and the
     *       commission invoice's tax waits for ruling R-MKT-9. The calculator carries them for when they arrive.</li>
     * </ul>
     */
    static Figures figures(MarketplaceOrderLine l, MarketplaceOrder o, boolean firstLine, Collection<MarketplaceReturn> lineReturns) {
        BigDecimal returnedItems = Money.ZERO, refunded = Money.ZERO;
        for (MarketplaceReturn r : lineReturns) {
            if (!MarketplaceReturn.REFUNDED.equals(r.getStatus())) continue;
            returnedItems = returnedItems.add(Money.nz(r.getLineAmount()));
            refunded = refunded.add(Money.nz(r.getRefundAmount()));
        }
        BigDecimal delivery = firstLine && o != null ? Money.nz(o.getDeliveryFee()) : Money.ZERO;
        BigDecimal items = Money.nz(l.getLineTotal()).subtract(returnedItems).max(Money.ZERO);
        BigDecimal customer = Money.nz(l.getLineTotal()).add(delivery).subtract(refunded).max(Money.ZERO);
        CommissionPolicy policy = policyOf(l);
        BigDecimal commission = policy == null || items.signum() == 0 ? Money.ZERO
                : policy.commissionOn(items.add(delivery), delivery).min(items);
        SettlementCalculator.Breakdown b = SettlementCalculator.calculate(new SettlementCalculator.Inputs(customer,
                CommissionPolicy.fixed(commission), Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO));
        boolean sellerCollected = o == null || !CARD.equals(o.getPaymentMode());
        return new Figures(b, sellerCollected);
    }

    /** The snapshot, or none: a line sold with no commission policy earns MaxTheService nothing. */
    static CommissionPolicy policyOf(MarketplaceOrderLine l) {
        if (l.getCommissionBasis() == null) return null;
        try {
            CommissionPolicy.Basis basis = CommissionPolicy.Basis.valueOf(l.getCommissionBasis());
            return new CommissionPolicy(basis, l.getCommissionRate(), l.getCommissionFixed());
        } catch (RuntimeException e) {
            LOG.warn("MKT-1g: line {} has an unreadable commission snapshot ({}); settled with no commission",
                    l.getId(), l.getCommissionBasis());
            return null;
        }
    }

    /** The day a line delivered at {@code deliveredAt}, sold with {@code returnDays}, becomes payable. */
    static LocalDate eligibleOn(LocalDateTime deliveredAt, Integer returnDays, int tPlusDays) {
        if (deliveredAt == null) return null;
        LocalDate windowEnd = deliveredAt.toLocalDate().plusDays(returnDays == null ? DEFAULT_RETURN_DAYS : Math.max(0, returnDays));
        LocalDate d = CALENDAR.plusBusinessDays(windowEnd, tPlusDays);
        while (!CALENDAR.isBusinessDay(d)) d = d.plusDays(1);   // T+0 on a Saturday pays on Monday
        return d;
    }

    // ── the settlement run ─────────────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${mkt.settlement.sweep-ms:600000}", initialDelayString = "${mkt.settlement.sweep-initial-ms:90000}")
    public void scheduledRun() {
        SettlementDTOs.RunResult r = settleDue(TenantClock.today());
        if (r.settled() > 0 || r.waitingForBooks() > 0)
            LOG.info("MKT-1g settlement: {} checked, {} settled, {} waiting, {} on hold, {} waiting for the operator's books",
                    r.checked(), r.settled(), r.waiting(), r.onHold(), r.waitingForBooks());
    }

    /** The operator's "Settle now": the same run, and it chooses the operator's own books if none are chosen yet. */
    public SettlementDTOs.RunResult runNow() {
        access.assertOperator();
        new TransactionTemplate(txManager).executeWithoutResult(s -> ensureBooks());
        return settleDue(TenantClock.today());
    }

    /** Every delivered line still waiting, each in its own transaction: one bad line cannot stop the rest. */
    SettlementDTOs.RunResult settleDue(LocalDate today) {
        int checked = 0, settled = 0, waiting = 0, onHold = 0, noBooks = 0;
        Optional<Books> books = settings.books();
        int tPlus = settings.tPlusDays();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (MarketplaceOrderLine candidate : lines.findDeliveredInStatus(WAITING, PageRequest.of(0, BATCH))) {
            checked++;
            try {
                String outcome = tx.execute(s -> settleLine(candidate.getId(), today, tPlus, books.orElse(null)));
                if (Settlement.ELIGIBLE.name().equals(outcome)) settled++;
                else if (Settlement.ON_HOLD.name().equals(outcome)) onHold++;
                else if ("NO_BOOKS".equals(outcome)) noBooks++;
                else waiting++;
            } catch (RuntimeException e) {
                LOG.warn("MKT-1g: line {} not settled this run: {}", candidate.getId(), e.getMessage());
            }
        }
        return new SettlementDTOs.RunResult(checked, settled, waiting, onHold, noBooks);
    }

    /** Move one line as far as today allows; write its ledger rows and the operator's journal when it settles. */
    String settleLine(Long lineId, LocalDate today, int tPlus, Books books) {
        MarketplaceOrderLine l = lines.findById(lineId).orElse(null);
        if (l == null || !WAITING.contains(l.getSettlementStatus())) return "SKIP";
        MarketplaceSellerOrder so = sellerOrders.findById(l.getSellerOrderId()).orElse(null);
        if (so == null || so.getDeliveredAt() == null) return Settlement.NOT_ELIGIBLE.name();
        List<MarketplaceReturn> lineReturns = returns.findByOrderLineIdIn(List.of(l.getId()));
        if (lineReturns.stream().anyMatch(r -> RETURN_IN_PROGRESS.contains(r.getStatus()))) {
            move(l, Settlement.ON_HOLD);
            return Settlement.ON_HOLD.name();
        }
        LocalDate due = eligibleOn(so.getDeliveredAt(), l.getReturnDays(), tPlus);
        if (today.isBefore(due)) {
            move(l, Settlement.PENDING_RETURN_WINDOW);
            return Settlement.PENDING_RETURN_WINDOW.name();
        }
        if (books == null) {
            move(l, Settlement.PENDING_RETURN_WINDOW);
            return "NO_BOOKS";
        }
        MarketplaceOrder o = orders.findById(so.getMktOrderId()).orElse(null);
        Figures f = figures(l, o, isFirstLine(l), lineReturns);
        SettlementCalculator.Breakdown b = f.breakdown();
        String ref = ref(o, l);
        String key = "line:" + l.getId() + ":";
        append(l.getSellerOrganizationId(), l.getId(), null, LedgerEntryType.SALE, Money.ZERO, b.customerAmount(), ref,
                "Customer paid for " + clip(l.getProductName(), 200), key + "SALE", null);
        append(l.getSellerOrganizationId(), l.getId(), null, LedgerEntryType.COMMISSION, b.commission(), Money.ZERO, ref,
                "MaxTheService commission", key + "COMMISSION", null);
        if (f.sellerCollected())
            append(l.getSellerOrganizationId(), l.getId(), null, LedgerEntryType.COLLECTED_BY_SELLER, b.customerAmount(),
                    Money.ZERO, ref, "Cash collected by your rider on delivery", key + "COLLECTED", null);
        move(l, Settlement.ELIGIBLE);
        gl.enqueue(books.organizationId(), books.userId(), PostingEventRequest.builder()
                .eventType("MKT_SETTLEMENT").eventKey("MKT-SET-" + l.getId()).date(today).ref(ref)
                .grandTotal(b.customerAmount()).paidAmount(f.sellerCollected() ? Money.ZERO : b.customerAmount())
                .commission(b.commission()).method("BANK").build());
        audit.event("MKT_LINE_SETTLED", "MKT_ORDER_LINE", ref, l.getSellerOrganizationId(), Actor.SYSTEM, null,
                Settlement.ELIGIBLE.name(), b.merchantPayable(), "commission " + b.commission().toPlainString());
        return Settlement.ELIGIBLE.name();
    }

    /** Walk the settlement machine to {@code target}, through PENDING_RETURN_WINDOW when the direct move is not allowed. */
    static void move(MarketplaceOrderLine l, Settlement target) {
        Settlement from = Settlement.valueOf(l.getSettlementStatus());
        if (from == target) return;
        if (!MarketplaceStateMachines.SETTLEMENT.canTransition(from, target)
                && MarketplaceStateMachines.SETTLEMENT.canTransition(from, Settlement.PENDING_RETURN_WINDOW)) {
            from = MarketplaceStateMachines.SETTLEMENT.transition(from, Settlement.PENDING_RETURN_WINDOW);
            if (from == target) { l.setSettlementStatus(from.name()); return; }
        }
        l.setSettlementStatus(MarketplaceStateMachines.SETTLEMENT.transition(from, target).name());
    }

    private boolean isFirstLine(MarketplaceOrderLine l) {
        List<MarketplaceOrderLine> all = lines.findBySellerOrderIdOrderByIdAsc(l.getSellerOrderId());
        return all.isEmpty() || all.get(0).getId().equals(l.getId());
    }

    private MarketplaceSettlementEntry append(Long org, Long lineId, Long payoutId, LedgerEntryType type, BigDecimal debit,
            BigDecimal credit, String ref, String memo, String key, Long userId) {
        if (debit.signum() == 0 && credit.signum() == 0) return null;   // a zero row says nothing
        MarketplaceSettlementEntry e = new MarketplaceSettlementEntry();
        e.setOrganizationId(org);
        e.setOrderLineId(lineId);
        e.setPayoutId(payoutId);
        e.setEntryType(type.name());
        e.setDebitAmount(Money.scale(debit));
        e.setCreditAmount(Money.scale(credit));
        e.setCurrency(MarketplaceSettlementEntry.CURRENCY);
        e.setRef(ref);
        e.setMemo(memo);
        e.setEffectiveAt(LocalDateTime.now());
        e.setIdempotencyKey(key);
        e.setCreatedByUserId(userId);
        return entries.save(e);
    }

    // ── the seller's statement ─────────────────────────────────────────────────────────────────────────────

    /** The seller's own lines, newest first, each with its whole split (R15.5, R16.1). */
    @Transactional(readOnly = true)
    public PageResponse<SettlementDTOs.StatementLine> statement(String status, Integer page, Integer size) {
        Long org = access.org();
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 20 : Math.min(size, 100));
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null) {
            try { Settlement.valueOf(st); }
            catch (IllegalArgumentException e) { throw new ValidationException("Unknown settlement status: " + st + "."); }
        }
        return statementPage(lines.statement(org, ACCEPTED, st, p));
    }

    private PageResponse<SettlementDTOs.StatementLine> statementPage(Page<MarketplaceOrderLine> rows) {
        List<Long> lineIds = rows.getContent().stream().map(MarketplaceOrderLine::getId).toList();
        Map<Long, MarketplaceSellerOrder> sos = new HashMap<>();
        Map<Long, MarketplaceOrder> parents = new HashMap<>();
        Map<Long, List<MarketplaceReturn>> rets = lineIds.isEmpty() ? Map.of()
                : returns.findByOrderLineIdIn(lineIds).stream().collect(Collectors.groupingBy(MarketplaceReturn::getOrderLineId));
        Map<Long, List<MarketplaceSettlementEntry>> posted = lineIds.isEmpty() ? Map.of()
                : entries.findByOrderLineIdIn(lineIds).stream().collect(Collectors.groupingBy(MarketplaceSettlementEntry::getOrderLineId));
        Map<Long, String> payoutNos = new HashMap<>();
        Map<Long, Long> firstLines = new HashMap<>();
        int tPlus = settings.tPlusDays();
        return PageResponse.of(rows, l -> {
            MarketplaceSellerOrder so = sos.computeIfAbsent(l.getSellerOrderId(), id -> sellerOrders.findById(id).orElse(null));
            MarketplaceOrder o = so == null ? null : parents.computeIfAbsent(so.getMktOrderId(), id -> orders.findById(id).orElse(null));
            Long first = firstLines.computeIfAbsent(l.getSellerOrderId(), id -> {
                List<MarketplaceOrderLine> all = lines.findBySellerOrderIdOrderByIdAsc(id);
                return all.isEmpty() ? l.getId() : all.get(0).getId();
            });
            Figures f = figures(l, o, first.equals(l.getId()), rets.getOrDefault(l.getId(), List.of()));
            SettlementCalculator.Breakdown b = f.breakdown();
            List<MarketplaceSettlementEntry> mine = posted.get(l.getId());
            BigDecimal customer = b.customerAmount(), commission = b.commission();
            if (mine != null) {   // settled: the ledger is the truth, not today's arithmetic
                customer = sum(mine, LedgerEntryType.SALE, MarketplaceSettlementEntry::getCreditAmount);
                commission = sum(mine, LedgerEntryType.COMMISSION, MarketplaceSettlementEntry::getDebitAmount);
            }
            String payoutNo = l.getPayoutId() == null ? null : payoutNos.computeIfAbsent(l.getPayoutId(),
                    id -> payouts.findById(id).map(MarketplacePayout::getPayoutNo).orElse(null));
            return new SettlementDTOs.StatementLine(l.getId(), o == null ? null : o.getOrderNo(), l.getProductName(),
                    l.getQuantity(), l.getSettlementStatus(), so == null ? null : so.getDeliveredAt(),
                    so == null ? null : eligibleOn(so.getDeliveredAt(), l.getReturnDays(), tPlus),
                    f.sellerCollected() ? "SELLER" : "PLATFORM", customer, commission, b.deliveryFee(), b.processingFee(),
                    b.tax(), b.reserve(), b.adjustment(), customer.subtract(commission).subtract(b.deliveryFee())
                            .subtract(b.processingFee()).subtract(b.tax()).subtract(b.reserve()).subtract(b.adjustment()),
                    payoutNo);
        });
    }

    private static BigDecimal sum(List<MarketplaceSettlementEntry> es, LedgerEntryType type,
            Function<MarketplaceSettlementEntry, BigDecimal> side) {
        return es.stream().filter(e -> type.name().equals(e.getEntryType())).map(side).reduce(Money.ZERO, BigDecimal::add);
    }

    /** The seller's own account: balance, latest rows, payouts. */
    @Transactional(readOnly = true)
    public SettlementDTOs.AccountView myAccount() {
        return account(access.org(), false);
    }

    /** The operator's view of one seller's account. */
    @Transactional(readOnly = true)
    public SettlementDTOs.AccountView sellerAccount(Long org) {
        access.assertOperator();
        if (org == null) throw new ValidationException("Choose the seller.");
        return account(org, true);
    }

    private SettlementDTOs.AccountView account(Long org, boolean operator) {
        List<SettlementDTOs.EntryRow> rows = entries.findByOrganizationIdOrderByIdDesc(org, PageRequest.of(0, 100)).getContent()
                .stream().map(e -> new SettlementDTOs.EntryRow(e.getId(), e.getEntryType(), e.getRef(), e.getMemo(),
                        e.getDebitAmount(), e.getCreditAmount(), e.getEffectiveAt())).toList();
        List<SettlementDTOs.PayoutView> ps = payouts.findByOrganizationIdOrderByRequestedAtDesc(org, PageRequest.of(0, 20))
                .getContent().stream().map(x -> view(x, operator)).toList();
        Optional<MarketplacePayout> open = payouts.findFirstByOrganizationIdAndStatusIn(org, IN_FLIGHT);
        BigDecimal balance = Money.scale(Money.nz(entries.balance(org)));
        return new SettlementDTOs.AccountView(org, sellerName(org), balance, open.map(x -> view(x, operator)).orElse(null),
                rows, ps, cod.standing(org, balance, TenantClock.today()));
    }

    // ── operator: accounts, payouts, corrections ───────────────────────────────────────────────────────────

    /** Every seller with a ledger, and its balance. */
    @Transactional(readOnly = true)
    public List<SettlementDTOs.AccountRow> accounts() {
        access.assertOperator();
        List<SettlementDTOs.AccountRow> out = new ArrayList<>();
        for (Object[] row : entries.balances()) {
            Long org = ((Number) row[0]).longValue();
            BigDecimal bal = row[1] == null ? Money.ZERO : Money.scale(new BigDecimal(row[1].toString()));
            out.add(new SettlementDTOs.AccountRow(org, sellerName(org), bal,
                    payouts.findFirstByOrganizationIdAndStatusIn(org, IN_FLIGHT).map(x -> view(x, true)).orElse(null)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PageResponse<SettlementDTOs.PayoutView> payoutQueue(String status, Integer page, Integer size) {
        access.assertOperator();
        List<String> st = status == null || status.isBlank() ? IN_FLIGHT : List.of(status.trim().toUpperCase(Locale.ROOT));
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page, size == null || size < 1 ? 50 : Math.min(size, 100));
        return PageResponse.of(payouts.findByStatusInOrderByRequestedAtDesc(st, p), x -> view(x, true));
    }

    /**
     * Request a payout of the seller's whole positive balance. The same key returns the same payout; a seller with a
     * payout still in flight gets that one named, never a second.
     */
    @Transactional
    public SettlementDTOs.PayoutView requestPayout(SettlementDTOs.PayoutRequest req) {
        access.assertOperator();
        if (req == null || req.organizationId() == null) throw new ValidationException("Choose the seller to pay.");
        String key = req.idempotencyKey() == null ? "" : req.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > 80) throw new ValidationException("The request is missing its idempotency key.");
        Optional<MarketplacePayout> same = payouts.findByIdempotencyKey(key);
        if (same.isPresent()) {
            if (!same.get().getOrganizationId().equals(req.organizationId()))
                throw new ValidationException("That request key belongs to another payout.");
            return view(same.get(), true);
        }
        Long org = req.organizationId();
        Optional<MarketplacePayout> open = payouts.findFirstByOrganizationIdAndStatusIn(org, IN_FLIGHT);
        if (open.isPresent())
            throw new ValidationException("Payout " + open.get().getPayoutNo() + " for this seller is still "
                    + open.get().getStatus().toLowerCase(Locale.ROOT) + ". Finish it before requesting another.");
        BigDecimal balance = Money.scale(Money.nz(entries.balance(org)));
        if (balance.signum() <= 0)
            throw new ValidationException("There is nothing to pay this seller: the balance is Rs " + money(balance) + ".");
        ensureBooks();
        MarketplacePayout po = new MarketplacePayout();
        po.setPayoutNo(String.format(Locale.ROOT, "PO-%06d", numbers.next(MarketplaceCheckoutService.PLATFORM_ORG, PAYOUT_DOC)));
        po.setOrganizationId(org);
        po.setRequestedAmount(balance);
        po.setStatus(MarketplacePayout.REQUESTED);
        po.setIdempotencyKey(key);
        po.setRequestedByUserId(access.userId());
        po.setRequestedAt(LocalDateTime.now());
        MarketplacePayout saved = payouts.save(po);
        for (MarketplaceOrderLine l : lines.findBySellerOrganizationIdAndSettlementStatusAndPayoutIdIsNull(org, Settlement.ELIGIBLE.name()))
            l.setPayoutId(saved.getId());
        audit.event("MKT_PAYOUT_REQUESTED", "MKT_PAYOUT", saved.getPayoutNo(), org, Actor.OPERATOR, null,
                MarketplacePayout.REQUESTED, balance, null);
        return view(saved, true);
    }

    /** Four eyes: the person who requested a payout can never approve it. */
    @Transactional
    public SettlementDTOs.PayoutView approvePayout(Long id) {
        access.assertOperator();
        MarketplacePayout po = payout(id);
        if (MarketplacePayout.APPROVED.equals(po.getStatus()) || MarketplacePayout.PAID.equals(po.getStatus()))
            return view(po, true);   // a second press changes nothing
        Long me = access.userId();
        if (me == null || me.equals(po.getRequestedByUserId()))
            throw new ValidationException("Another person must approve this payout: you requested it.");
        po.setStatus(MarketplacePayout.APPROVED);
        po.setApprovedAmount(po.getRequestedAmount());
        po.setApprovedByUserId(me);
        po.setApprovedAt(LocalDateTime.now());
        for (MarketplaceOrderLine l : lines.findByPayoutId(po.getId())) move(l, Settlement.APPROVED);
        audit.event("MKT_PAYOUT_APPROVED", "MKT_PAYOUT", po.getPayoutNo(), po.getOrganizationId(), Actor.OPERATOR,
                MarketplacePayout.REQUESTED, MarketplacePayout.APPROVED, po.getApprovedAmount(), null);
        return view(payouts.save(po), true);
    }

    /**
     * Record the bank transfer. Writes the PAYOUT row and the operator's journal; refused when the balance no longer
     * covers the payout (a correction in between), so the ledger can never pay out more than it owes.
     */
    @Transactional
    public SettlementDTOs.PayoutView markPaid(SettlementDTOs.PayoutDecision req) {
        access.assertOperator();
        MarketplacePayout po = payout(req == null ? null : req.id());
        if (MarketplacePayout.PAID.equals(po.getStatus())) return view(po, true);
        if (!MarketplacePayout.APPROVED.equals(po.getStatus()))
            throw new ValidationException("Payout " + po.getPayoutNo() + " must be approved before it is paid.");
        String bankRef = req.bankReference() == null ? "" : req.bankReference().trim();
        if (bankRef.isEmpty()) throw new ValidationException("Enter the bank's reference for the transfer.");
        if (bankRef.length() > 80) throw new ValidationException("The bank reference is at most 80 characters.");
        BigDecimal amount = po.getApprovedAmount();
        BigDecimal balance = Money.scale(Money.nz(entries.balance(po.getOrganizationId())));
        if (amount.compareTo(balance) > 0)
            throw new ValidationException("The seller's balance is now Rs " + money(balance) + ", less than this payout of Rs "
                    + money(amount) + ". Request a new payout.");
        Books books = ensureBooks();
        append(po.getOrganizationId(), null, po.getId(), LedgerEntryType.PAYOUT, amount, Money.ZERO, po.getPayoutNo(),
                "Bank transfer " + bankRef, "payout:" + po.getId(), access.userId());
        po.setStatus(MarketplacePayout.PAID);
        po.setBankReference(bankRef);
        po.setPaidByUserId(access.userId());
        po.setPaidAt(LocalDateTime.now());
        for (MarketplaceOrderLine l : lines.findByPayoutId(po.getId())) {
            move(l, Settlement.PROCESSING);
            move(l, Settlement.PAID);
        }
        gl.enqueue(books.organizationId(), books.userId(), PostingEventRequest.builder()
                .eventType("MKT_PAYOUT").eventKey("MKT-PAY-" + po.getId()).date(TenantClock.today()).ref(po.getPayoutNo())
                .grandTotal(amount).method("BANK").build());
        audit.event("MKT_PAYOUT_PAID", "MKT_PAYOUT", po.getPayoutNo(), po.getOrganizationId(), Actor.OPERATOR,
                MarketplacePayout.APPROVED, MarketplacePayout.PAID, amount, "bank ref " + bankRef);
        return view(payouts.save(po), true);
    }

    /** A correction: a NEW row, never an edit (R15.6). The same key records it once. */
    @Transactional
    public SettlementDTOs.AccountView adjust(SettlementDTOs.AdjustmentRequest req) {
        access.assertOperator();
        if (req == null || req.organizationId() == null) throw new ValidationException("Choose the seller.");
        BigDecimal amount = req.amount() == null ? Money.ZERO : Money.scale(req.amount());
        if (amount.signum() == 0 || amount.abs().compareTo(MAX_ADJUSTMENT) > 0)
            throw new ValidationException("Enter a correction between Rs 0.01 and Rs 1,000,000, with a minus sign to take money back.");
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.length() < 3) throw new ValidationException("Say why: the reason is shown on the seller's statement.");
        String key = req.idempotencyKey() == null ? "" : req.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > 70) throw new ValidationException("The request is missing its idempotency key.");
        if (entries.existsByIdempotencyKey("adj:" + key)) return account(req.organizationId(), true);
        Books books = ensureBooks();
        String ref = String.format(Locale.ROOT, "ADJ-%06d", numbers.next(MarketplaceCheckoutService.PLATFORM_ORG, ADJUSTMENT_DOC));
        append(req.organizationId(), null, null, LedgerEntryType.ADJUSTMENT,
                amount.signum() < 0 ? amount.negate() : Money.ZERO, amount.signum() > 0 ? amount : Money.ZERO,
                ref, clip(reason, 300), "adj:" + key, access.userId());
        gl.enqueue(books.organizationId(), books.userId(), PostingEventRequest.builder()
                .eventType("MKT_ADJUSTMENT").eventKey("MKT-ADJ-" + ref).date(TenantClock.today()).ref(ref)
                .grandTotal(amount).build());
        audit.event("MKT_LEDGER_ADJUSTED", "MKT_LEDGER_ENTRY", ref, req.organizationId(), Actor.OPERATOR, null, null,
                amount, clip(reason, 200));
        return account(req.organizationId(), true);
    }

    // ── MKT-2d: cash orders: what sellers owe, and the money they pay ───────────────────────────────────────

    /**
     * Every seller that ever collected cash for a marketplace order, or owes: what its riders collected, what it paid
     * MaxTheService, what it owes now and since when. Overdue first, then the largest debt.
     */
    @Transactional(readOnly = true)
    public List<SettlementDTOs.CodRow> codReconciliation() {
        access.assertOperator();
        Map<Long, BigDecimal> collected = sideOf(entries.totalsOfType(LedgerEntryType.COLLECTED_BY_SELLER.name()), 1);
        Map<Long, BigDecimal> remitted = sideOf(entries.totalsOfType(LedgerEntryType.REMITTANCE.name()), 2);
        LocalDate today = TenantClock.today();
        List<SettlementDTOs.CodRow> out = new ArrayList<>();
        for (Object[] row : entries.balances()) {
            Long org = ((Number) row[0]).longValue();
            BigDecimal bal = row[1] == null ? Money.ZERO : Money.scale(new BigDecimal(row[1].toString()));
            BigDecimal cash = collected.getOrDefault(org, Money.ZERO);
            if (cash.signum() == 0 && bal.signum() >= 0) continue;     // never took cash and owes nothing
            out.add(new SettlementDTOs.CodRow(org, sellerName(org), cash, remitted.getOrDefault(org, Money.ZERO), bal,
                    cod.standing(org, bal, today)));
        }
        out.sort((a, b) -> a.standing().overdue() != b.standing().overdue() ? (a.standing().overdue() ? -1 : 1)
                : b.standing().owed().compareTo(a.standing().owed()));
        return out;
    }

    private static Map<Long, BigDecimal> sideOf(List<Object[]> rows, int column) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (Object[] r : rows)
            m.put(((Number) r[0]).longValue(), r[column] == null ? Money.ZERO : Money.scale(new BigDecimal(r[column].toString())));
        return m;
    }

    /**
     * The seller paid MaxTheService (a bank transfer, or cash at the office) for its cash orders: a REMITTANCE credit
     * and the operator's journal (Dr bank, Cr sellers' control account). Never more than it owes; a payment that is less
     * says why, as the shop's cash-up does for a short drawer. The same key records it once.
     */
    @Transactional
    public SettlementDTOs.AccountView recordRemittance(SettlementDTOs.RemittanceRequest req) {
        access.assertOperator();
        if (req == null || req.organizationId() == null) throw new ValidationException("Choose the seller.");
        String key = req.idempotencyKey() == null ? "" : req.idempotencyKey().trim();
        if (key.isEmpty() || key.length() > 70) throw new ValidationException("The request is missing its idempotency key.");
        if (entries.existsByIdempotencyKey("rem:" + key)) return account(req.organizationId(), true);
        Long org = req.organizationId();
        BigDecimal amount = req.amount() == null ? Money.ZERO : Money.scale(req.amount());
        if (amount.signum() <= 0) throw new ValidationException("Enter the amount the seller paid.");
        BigDecimal owed = Money.scale(Money.nz(entries.balance(org))).negate().max(Money.ZERO);
        if (owed.signum() == 0) throw new ValidationException("This seller owes nothing for cash orders.");
        if (amount.compareTo(owed) > 0)
            throw new ValidationException("The seller owes Rs " + money(owed) + ". Enter at most that; record anything more "
                    + "as a correction.");
        String reference = req.reference() == null ? "" : req.reference().trim();
        if (reference.isEmpty()) throw new ValidationException("Enter the bank's reference, or the receipt number for cash.");
        if (reference.length() > 80) throw new ValidationException("The reference is at most 80 characters.");
        String note = req.note() == null ? "" : req.note().trim();
        if (amount.compareTo(owed) < 0 && note.length() < 3)
            throw new ValidationException("The seller owes Rs " + money(owed) + " and paid Rs " + money(amount)
                    + ". Say why it paid less: the note is shown on the seller's statement.");
        Books books = ensureBooks();
        String ref = String.format(Locale.ROOT, "RM-%06d", numbers.next(MarketplaceCheckoutService.PLATFORM_ORG, REMITTANCE_DOC));
        String memo = "Paid to MaxTheService for cash orders, ref " + clip(reference, 80) + (note.isEmpty() ? "" : ". " + note);
        append(org, null, null, LedgerEntryType.REMITTANCE, Money.ZERO, amount, ref, clip(memo, 300), "rem:" + key, access.userId());
        gl.enqueue(books.organizationId(), books.userId(), PostingEventRequest.builder()
                .eventType("MKT_REMITTANCE").eventKey("MKT-REM-" + ref).date(TenantClock.today()).ref(ref)
                .grandTotal(amount).method("BANK").build());
        audit.event("MKT_REMITTANCE_RECORDED", "MKT_LEDGER_ENTRY", ref, org, Actor.OPERATOR, null, null, amount,
                clip("ref " + reference + (note.isEmpty() ? "" : "; " + note), 200));
        return account(org, true);
    }

    // ── settings ───────────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SettlementDTOs.SettingsView settingsView() {
        access.assertOperator();
        Optional<Books> b = settings.books();
        return new SettlementDTOs.SettingsView(settings.tPlusDays(), b.map(Books::organizationId).orElse(null),
                b.map(x -> x.organizationId().equals(access.org())).orElse(false), settings.codRemitDays(),
                settings.codStopWhenOverdue());
    }

    @Transactional
    public SettlementDTOs.SettingsView saveSettings(SettlementDTOs.SettingsRequest req) {
        access.assertOperator();
        if (req == null) throw new ValidationException("Nothing to save.");
        if (req.tPlusDays() != null) settings.setTPlusDays(req.tPlusDays());
        if (Boolean.TRUE.equals(req.useMyBooks())) settings.useMyBooks();
        if (req.codRemitDays() != null) settings.setCodRemitDays(req.codRemitDays());
        if (req.codStopWhenOverdue() != null) settings.setCodStopWhenOverdue(req.codStopWhenOverdue());
        return settingsView();
    }

    /** The operator's books; an operator's first settlement action chooses its own organisation. */
    private Books ensureBooks() {
        return settings.books().orElseGet(settings::useMyBooks);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────

    private MarketplacePayout payout(Long id) {
        if (id == null) throw new ValidationException("Choose the payout.");
        return payouts.findById(id).orElseThrow(() -> new ResourceNotFoundException("No such payout."));
    }

    private SettlementDTOs.PayoutView view(MarketplacePayout p, boolean operator) {
        Long me = operator ? access.userId() : null;
        return new SettlementDTOs.PayoutView(p.getId(), p.getPayoutNo(), p.getOrganizationId(), sellerName(p.getOrganizationId()),
                p.getRequestedAmount(), p.getApprovedAmount(), p.getStatus(), p.getBankReference(), p.getRequestedAt(),
                p.getApprovedAt(), p.getPaidAt(), me != null && me.equals(p.getRequestedByUserId()), p.getVersion());
    }

    private String sellerName(Long org) {
        return sellerAccounts.findByOrganizationId(org).map(a -> a.getDisplayName()).orElse(null);
    }

    private static String ref(MarketplaceOrder o, MarketplaceOrderLine l) {
        return (o == null ? "MKT" : o.getOrderNo()) + "/L" + l.getId();
    }

    static String money(BigDecimal v) {
        return String.format(Locale.ENGLISH, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
