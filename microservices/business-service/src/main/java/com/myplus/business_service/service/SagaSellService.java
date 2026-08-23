package com.myplus.business_service.service;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.*;
import com.myplus.common.credit.CreditLimitPolicy;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sell↔stock saga orchestrator (slice 33, U3b), active when {@code trade.saga.enabled}. For a sale:
 * translate itemId→productId, price from catalog (D1), {@code reserve} inventory, write the PENDING sale
 * (committed), then {@code confirm}. Compensation: a write failure releases the hold; a confirm failure
 * leaves the invoice PENDING for the recovery relay (U3c) to re-drive (confirm is idempotent). Reserve is
 * idempotent on the per-sale key.
 */
@Service
@RequiredArgsConstructor
public class SagaSellService {

    private static final Logger LOG = LoggerFactory.getLogger(SagaSellService.class);

    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final SagaSaleWriter saleWriter;
    private final RequestUtil requestUtil;
    private final TaxService taxService;
    private final com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;   // SF-3 dedup
    private final com.myplus.business_service.repository.PurchaseRepo purchaseRepo;                 // SF-10 line cost

    @org.springframework.beans.factory.annotation.Autowired
    private GlOutboxService glOutboxService;   // #4: durable GL posting via the outbox (replaces direct FinanceClient)

    @org.springframework.beans.factory.annotation.Autowired
    private AuditService auditService;   // #6: append-only audit trail

    @org.springframework.beans.factory.annotation.Autowired
    private PeriodLockGuard periodLockGuard;   // period close: reject a sale dated in a closed period

    @org.springframework.beans.factory.annotation.Autowired
    private StoreCreditService storeCreditService;   // SF-5 Model B: redeem store credit at checkout

    /** O7 D2: the ONE definition of "whose limit governs" + "what does that group owe" — read and write share it. */
    @org.springframework.beans.factory.annotation.Autowired
    private CreditStandingService creditStandingService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.myplus.common.settings.SettingsService settingsService;   // B1: per-org pharmacy rx policy

    // B2B-P1 (#9): the customer's running balance + credit limit. A FIELD, not a constructor argument —
    // MarginPolicyTest constructs this service with an exact list of nulls, and widening the constructor
    // would break a passing test for no benefit.
    @org.springframework.beans.factory.annotation.Autowired
    private com.myplus.business_service.repository.CustomerRepo customerRepo;

    /** Cap a STORE_CREDIT tender to the customer's balance (never trust the client / overdraw). Mutates the tender
     *  amount in the dto so settle uses the real value; returns the amount that will be redeemed (0 if none / no
     *  identified customer / zero balance). */
    private BigDecimal capStoreCreditTender(com.myplus.business_service.dto.CustomerHistoryDTO dto, Long customerId) {
        if (dto.getTenders() == null) return BigDecimal.ZERO;
        for (com.myplus.business_service.dto.TenderDTO t : dto.getTenders()) {
            if (t != null && "STORE_CREDIT".equalsIgnoreCase(t.getMethod())) {
                BigDecimal want = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
                BigDecimal cap = (customerId != null && want.signum() > 0)
                        ? want.min(storeCreditService.balance(customerId)) : BigDecimal.ZERO;
                t.setAmount(cap);   // settle counts STORE_CREDIT as paid → the capped value is the real paid amount
                return cap;
            }
        }
        return BigDecimal.ZERO;
    }

    /** @return the invoice number of the recorded sale. */
    public String addSell(CustomerHistoryDTO dto) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        periodLockGuard.assertOpen(java.time.LocalDate.now());   // period close: a new sale is a today-dated entry

        // SF-3: idempotent submission — one key per checkout attempt (client-supplied; fall back to a generated one
        // for legacy callers). If an invoice already exists for (org, key), this is a double-click / retry: return
        // the SAME invoice, with no second reserve and no second write.
        String idempotencyKey = (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank())
                ? dto.getIdempotencyKey() : UUID.randomUUID().toString();
        java.util.Optional<CustomerHistory> already =
                customerHistoryRepo.findFirstByOrganizationIdAndIdempotencyKey(user.getOrganizationId(), idempotencyKey);
        if (already.isPresent()) {
            LOG.info("addSell idempotent replay for key {} -> existing invoice {}", idempotencyKey, already.get().getInvoiceNo());
            return already.get().getInvoiceNo();
        }

        // Store credit (SF-5 Model B): a STORE_CREDIT tender is capped to the customer's balance BEFORE settling
        // (never trust the client amount, never overdraw). Requires an identified (existing) customer — a fresh
        // customer has no credit. The tender is settled at the capped amount; we redeem exactly that after the write.
        Long scCustomerId = (dto.getCustomer() != null) ? dto.getCustomer().getCustomerId() : null;
        BigDecimal scRedeem = capStoreCreditTender(dto, scCustomerId);

        // SF-1/SF-2: the ONE authoritative line build (catalog price + sold rate + discount + tax + catalog
        // snapshot), shared with updateSell so add and edit produce identical lines.
        java.util.Map<Long, String> productNames = new java.util.HashMap<>();   // for a friendly out-of-stock message
        List<SagaLine> lines = buildLines(dto, productNames);

        // B2B-P0 (#3): whole-invoice margin policy, checked BEFORE anything is reserved or written — a sale
        // refused here has touched no stock and no ledger. The per-line warning on the sell screen cannot do
        // this: an invoice-level discount is applied after the lines are entered, so the sale can finish at or
        // below cost without any single line looking wrong.
        assertMarginPolicy(lines, dto);

        // B2B-P1 (#9): the credit-limit guard, also BEFORE any reservation or write. Under `warn` this throws
        // CreditConfirmationRequiredException so the cashier is asked while the decision is still reversible;
        // nothing has been written, so cancelling costs nothing and holds no stock.
        assertCreditPolicy(dto, lines, null);

        List<StockReservationLine> reservationLines = new ArrayList<>();
        for (SagaLine l : lines) {
            reservationLines.add(new StockReservationLine(l.productId(), BigDecimal.valueOf(l.quantity())));
        }

        // 3: reserve (FEFO). OUT_OF_STOCK -> reject the sale (nothing held, nothing written).
        StockReservationResponse reservation =
                inventoryClient.reserve(new StockReservationRequest(idempotencyKey, reservationLines));
        if (reservation == null || reservation.getStatus() != ReservationStatus.RESERVED) {
            String reason = (reservation != null) ? reservation.getMessage() : null;
            throw new InsufficientStockException(friendlyOutOfStock(reason, productNames));
        }
        String reservationId = reservation.getReservationId();

        // 4: write the PENDING sale (its own committed tx). On failure, release the hold and abort.
        CustomerHistory ch;
        try {
            // B2B-P3b-2 (#4): the reservation already told us WHICH batches it took. Hand them to the
            // writer so the sale records them; they have been returned and discarded on every sale until now.
            ch = saleWriter.writePending(dto, reservationId, idempotencyKey, user, lines,
                    reservation.getPicks());
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // TWO DIFFERENT RACES ARRIVE HERE AND THEY NEED OPPOSITE ANSWERS. Until this branch existed both
            // were treated as the idempotency race, so a sale that merely lost the race for an INVOICE NUMBER
            // was looked up by a key nobody had used, found nothing, and rethrew — the customer's sale gone,
            // reported as "Transaction silently rolled back because it has been marked as rollback-only".
            if (com.myplus.business_service.util.SequenceRetry.isCollision(dup)) {
                // Lost the race for invoice_seq: another till read the same MAX(seq) a moment earlier. Nothing
                // is wrong with this sale — it just needs the next number. writePending is REQUIRES_NEW, so
                // each attempt gets a genuinely new transaction; retrying inside the poisoned one cannot work.
                final CustomerHistoryDTO retryDto = dto;
                final String retryReservationId = reservationId;
                try {
                    ch = com.myplus.business_service.util.SequenceRetry.withRetry("invoice", () ->
                            saleWriter.writePending(retryDto, retryReservationId, idempotencyKey, user, lines,
                                    reservation.getPicks()));
                } catch (RuntimeException stillColliding) {
                    safeRelease(retryReservationId);
                    throw stillColliding;
                }
            } else {
                // SF-3 race: a concurrent retry inserted this invoice first (unique idempotency index). The reservation
                // is idempotent per key (a shared hold owned by the winner) — do NOT release it; just return their invoice.
                LOG.info("addSell idempotent race for key {} -> returning the winner's invoice", idempotencyKey);
                return customerHistoryRepo.findFirstByOrganizationIdAndIdempotencyKey(user.getOrganizationId(), idempotencyKey)
                        .map(CustomerHistory::getInvoiceNo).orElseThrow(() -> dup);
            }
        } catch (RuntimeException writeFailure) {
            safeRelease(reservationId);
            throw writeFailure;
        }

        // 5 + 6: confirm -> mark CONFIRMED. A confirm failure leaves the invoice PENDING for the relay (U3c);
        // the sale is recorded and the held stock stays held until confirmed.
        try {
            inventoryClient.confirm(reservationId);
            saleWriter.markStatus(ch.getCustomer_history_id(), "CONFIRMED");
        } catch (RuntimeException confirmFailure) {
            LOG.warn("Saga confirm failed for reservation {} (invoice {}); left PENDING for the recovery relay",
                    reservationId, ch.getInvoiceNo(), confirmFailure);
        }

        // Store credit (SF-5 Model B): redeem the capped amount from the customer's balance now that the sale is
        // written (ledger −amount + cached balance). Best-effort — the sale is already recorded.
        if (scRedeem != null && scRedeem.signum() > 0 && scCustomerId != null) {
            try { storeCreditService.redeem(scCustomerId, scRedeem, ch.getInvoiceNo()); }
            catch (Exception ex) { LOG.warn("store-credit redeem failed for {} (sale recorded)", ch.getInvoiceNo(), ex); }
        }

        // F3b: auto-post the sale to the General Ledger (Dr Cash/AR, Cr Sales+Tax; + COGS from the line cost).
        // Best-effort — a GL hiccup must never fail the sale (reconcile later). Only on a NEW sale (not edits).
        try {
            BigDecimal cost = BigDecimal.ZERO;
            for (SagaLine l : lines)
                if (l.costPrice() != null) cost = cost.add(l.costPrice().multiply(BigDecimal.valueOf(l.quantity())));
            glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                    .eventType("SALE").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
                    .grandTotal(ch.getGrandTotal()).subTotal(ch.getSubTotal()).taxTotal(ch.getTaxTotal())
                    .cost(cost).paidAmount(ch.getPaidAmount()).method(ch.getPaymentMode())
                    .storeCredit(scRedeem)             // store-credit portion → Dr 2200 (not Cash)
                    // D-4: the whole-document trade discount posts as CONTRA-REVENUE (Dr 4200), so Sales stays
                    // at the invoice's face value. Captured on the invoice since 3g but never posted until now,
                    // which meant a discount printed on the document and appeared nowhere in the books.
                    .discountTotal(ch.getTradeDiscount())
                    // Delivery charged to the customer → Cr 4300 Delivery Income. It rides inside grandTotal
                    // but not inside subTotal/taxTotal, so finance must be told the split or it would credit
                    // the fee to Sales and the journal would not balance.
                    .shippingFee(ch.getShippingFee())
                    .build());
        } catch (Exception ex) {
            LOG.warn("GL enqueue failed for sale {} (sale recorded)", ch.getInvoiceNo(), ex);
        }

        // #6: append-only audit of the sale (who/when/what/ref/amount).
        auditService.record("SALE", "INVOICE", ch.getInvoiceNo(), ch.getGrandTotal(), "items=" + lines.size());
        return ch.getInvoiceNo();
    }

    /**
     * SF-1/SF-2: THE authoritative per-line build — used by addSell AND updateSell so a new sale and an edit
     * produce identical lines. Each line is priced from catalog (soldRate = cashier's rate or catalog fallback),
     * the DISCOUNT (amount/%) is resolved and the tax is applied on the DISCOUNTED base (qty×rate − discount), and
     * the catalog price is snapshotted. {@code productNames} (nullable) is filled productId→name for a friendly
     * out-of-stock message on the reserve step.
     */
    /** The margin-policy values; anything else in config resolves to WARN (standard C3 — fail ON). */
    private static final java.util.Set<String> CREDIT_POLICIES = java.util.Set.of(
            CreditLimitPolicy.OFF, CreditLimitPolicy.WARN, CreditLimitPolicy.BLOCK);

    private static final java.util.Set<String> MARGIN_POLICIES = java.util.Set.of("off", "warn", "block");

    /**
     * O7 D1b — run the sale's checks and REPORT, without writing anything.
     *
     * <p>This is {@code addSell}'s first three steps and then stop:
     *
     * <pre>
     *   buildLines(dto)          resolve products, prices, costs
     *   assertMarginPolicy(...)  whole-invoice margin
     *   assertCreditPolicy(...)  credit limit
     *   ── addSell would now reserve; this returns ──
     * </pre>
     *
     * <p><b>The same methods, deliberately.</b> Not a re-implementation returning booleans. The two checks are
     * the only definition of these rules in the system, and a second copy would eventually disagree with the
     * first — silently, because the panel would say fine and dispatch would refuse, with nothing in either log
     * to connect them. That both checks already run BEFORE any reservation or write is what makes this
     * possible at all; the ordering in {@code addSell} is load-bearing, not incidental.
     *
     * <p><b>Refusals become data.</b> The checks throw, because in the sale path a refusal must stop a write.
     * Here there is no write to stop, so the throws are caught and reported. {@code blocked} is set only when
     * the tenant's policy actually refuses — a {@code warn} tenant gets {@code ok=false, blocked=false}, which
     * is "you should know", not "this cannot happen".
     *
     * <p><b>The DTO is a scratch copy in effect.</b> {@code assertMarginPolicy} appends to
     * {@code dto.getWarnings()} under {@code warn}; that is how the warning is collected here. Since nothing is
     * persisted from this call and the DTO was built for this request alone, the mutation cannot escape.
     *
     * <p>Advisory. Prices move, other orders consume the same credit, costs change — dispatch remains
     * authoritative and a caller must present this as a forecast.
     */
    public com.myplus.commerce.contracts.dto.PolicyCheckResponse checkPolicy(CustomerHistoryDTO dto) {
        // Resolving products needs the repositories; judging the result does not. Split so the JUDGEMENT can
        // be tested as pure logic on every `mvn test`, instead of only against a deployed stack.
        return checkPolicyForLines(buildLines(dto, new java.util.HashMap<>()), dto);
    }

    /**
     * The judgement half of {@link #checkPolicy}: given built lines, what would the sale path say?
     *
     * <p>Separated for testability, and the seam is honest — {@code addSell} runs exactly these two checks on
     * exactly these lines, in this order.
     */
    com.myplus.commerce.contracts.dto.PolicyCheckResponse checkPolicyForLines(List<SagaLine> lines,
                                                                              CustomerHistoryDTO dto) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        boolean blocked = false;

        /*
         * TWO different sums, and conflating them is a real bug I wrote here once.
         *
         * netTotal is the WHOLE basket — what the customer pays, uncosted lines included.
         *
         * The MARGIN is computed only over lines whose cost is known, excluding the others from BOTH sides.
         * That is not a simplification: assertMarginPolicy `continue`s before adding to either sum, because
         * counting an uncosted line's revenue as pure profit would swamp a real loss and make the guard
         * useless on exactly the legacy and never-purchased products most likely to be mispriced.
         *
         * My first version added every line to `net` and skipped only the cost, which reports a margin the
         * rule would never produce — the panel and the dispatch gate disagreeing, which is the precise drift
         * this whole slice exists to prevent. Caught by the uncosted-line case.
         */
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal costedNet = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        boolean anyCostKnown = false;
        for (SagaLine l : lines) {
            BigDecimal lineNet = (l.netAmount() == null) ? BigDecimal.ZERO : l.netAmount();
            netTotal = netTotal.add(lineNet);
            if (l.costPrice() == null) continue;
            anyCostKnown = true;
            costedNet = costedNet.add(lineNet);
            cost = cost.add(l.costPrice().multiply(BigDecimal.valueOf(l.quantity())));
        }

        int before = dto.getWarnings().size();
        try {
            assertMarginPolicy(lines, dto);
        } catch (com.myplus.common.web.exception.ValidationException blockedByMargin) {
            blocked = true;
            warnings.add(blockedByMargin.getMessage());
        }
        // Under `warn` the rule does not throw — it appends. Collect only what THIS call added, so a caller
        // that populated warnings itself does not have them echoed back as findings.
        for (int i = before; i < dto.getWarnings().size(); i++) warnings.add(dto.getWarnings().get(i));

        try {
            assertCreditPolicy(dto, lines, null);
        } catch (CreditConfirmationRequiredException needsConsent) {
            // `warn` policy: the real sale would ASK rather than refuse. Reported as a warning, not a block —
            // the reviewer is exactly the person entitled to answer that question.
            warnings.add(needsConsent.getMessage());
        } catch (com.myplus.common.web.exception.ValidationException blockedByCredit) {
            blocked = true;
            warnings.add(blockedByCredit.getMessage());
        }

        /*
         * The account the limit belongs to — for a branch of a trade group that is the COMPANY's row, not the
         * row being billed (B2B-P4a shared pool). Resolved through the same helper the guard uses, so the
         * figures reported here describe the account the guard actually judged.
         *
         * Null for a walk-in, and left null rather than defaulted: a basket with no named customer has no
         * credit position, and inventing a zero one would report a limit that does not exist.
         */
        Customer acct = null;
        Long custId = (dto.getCustomer() == null) ? null : dto.getCustomer().getCustomerId();
        if (custId != null && customerRepo != null) {
            Customer billed = customerRepo.findById(custId).orElse(null);
            if (billed != null) acct = creditAccountOf(billed);
        }

        return com.myplus.commerce.contracts.dto.PolicyCheckResponse.builder()
                .ok(warnings.isEmpty() && !blocked)
                .blocked(blocked)
                .warnings(warnings)
                .netTotal(netTotal)
                // null, NOT zero, when no line has a known cost: "no profit" would send someone hunting a
                // pricing error that does not exist. Same reason the margin rule excludes those lines.
                .margin(anyCostKnown ? costedNet.subtract(cost) : null)
                // Null limit means UNCAPPED, which is not the same as a limit of zero — a false "0 of 0"
                // trains a reader to ignore the warning (D2).
                .creditLimit(acct == null ? null : acct.getCreditLimit())
                .projectedDue(acct == null ? null : acct.getDueAmount())
                .build();
    }

    /**
     * Whole-invoice margin guard (#3).
     *
     * <p>Sums {@code net − cost×qty} across the sale. Lines with **no recorded cost** are excluded from both
     * sides rather than counted as pure profit: {@code costPrice} is null for legacy sells and for products
     * never purchased through the system, and treating those as 100% margin would make the guard silently
     * useless on exactly the sales most likely to be mispriced.
     *
     * <p>If NO line has a cost, there is nothing to judge and the sale proceeds untouched — a shop that has
     * never recorded a purchase must not be blocked from selling.
     *
     * <p>{@code block} throws before any reservation or write. {@code warn} records the sale and returns a
     * message through the existing warnings channel. Default and fail-safe value is {@code warn}.
     */
    void assertMarginPolicy(List<SagaLine> lines, CustomerHistoryDTO dto) {
        String policy = settingsService.getChoice("pos.sale.marginPolicy", MARGIN_POLICIES, "warn");
        if ("off".equals(policy) || lines == null || lines.isEmpty()) return;

        BigDecimal net = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        boolean anyCostKnown = false;
        for (SagaLine l : lines) {
            if (l.costPrice() == null) continue;          // unknown cost — excluded from BOTH sides
            anyCostKnown = true;
            net = net.add(l.netAmount() == null ? BigDecimal.ZERO : l.netAmount());
            cost = cost.add(l.costPrice().multiply(BigDecimal.valueOf(l.quantity())));
        }
        if (!anyCostKnown) return;                        // nothing to judge

        BigDecimal margin = net.subtract(cost);
        if (margin.signum() > 0) return;

        String msg = "This sale makes no profit (margin " + margin.setScale(2, java.math.RoundingMode.HALF_UP)
                + " on costed lines).";
        // B2B-P2 (#10, design 2g.1): if any line was priced by a RULE rather than typed, say so. Otherwise a
        // shopkeeper reads "no profit" and hunts for a cashier error that does not exist — the price came from
        // a contract they agreed to, and the fix is the rule, not the till.
        String ruleReason = firstPriceReason(dto);
        if (ruleReason != null) {
            msg = msg + " A price rule applied to this sale (" + ruleReason + ").";
        }
        if ("block".equals(policy)) {
            throw new com.myplus.common.web.exception.ValidationException(msg
                    + " Selling at or below cost is blocked for this organization.");
        }
        LOG.warn("Zero/negative margin sale allowed by policy=warn: margin={} lines={}", margin, lines.size());
        dto.getWarnings().add(msg);
    }

    /**
     * Credit-limit guard (#9) — B2B P1.
     *
     * <p>Projects what the customer will owe once this sale is recorded and compares it against their limit.
     * Runs <b>before any reservation or write</b>, so both outcomes are free: {@code block} refuses having
     * touched nothing, and {@code warn} asks the cashier while the decision is still reversible. A note after
     * the money has moved would not be consent — undoing it would mean a void.
     *
     * <p>Everything about the arithmetic lives in {@code common-credit}'s {@link CreditLimitPolicy} so the
     * other verticals that sell on account get the same answer. This method only gathers the numbers.
     *
     * <p>Inert unless the customer is (a) identified and (b) given a limit by the owner — which is every
     * customer until someone sets one, so nothing changes for an existing shop.
     *
     * @param editingDue when EDITING, the unpaid amount the edited invoice currently contributes to the
     *                   customer's balance; null for a new sale. Without it, reducing an over-limit invoice
     *                   would count that invoice twice and warn on the very act of fixing it.
     */
    // public (unlike assertMarginPolicy, which only addSell calls): the EDIT path in SellController must
    // invoke this too, with the edited invoice's own due, and it lives in a different package.
    public void assertCreditPolicy(CustomerHistoryDTO dto, List<SagaLine> lines, BigDecimal editingDue) {
        String policy = settingsService.getChoice("pos.sale.creditLimitPolicy", CREDIT_POLICIES,
                CreditLimitPolicy.WARN);
        if (CreditLimitPolicy.OFF.equals(policy)) return;

        Long customerId = (dto.getCustomer() != null) ? dto.getCustomer().getCustomerId() : null;
        if (customerId == null || customerRepo == null) return;   // walk-in: no account, nothing to cap
        Customer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return;

        // B2B-P4a SHARED POOL: the limit belongs to the CREDIT ACCOUNT, not to the row being billed. For a branch
        // of a trade group that is the company's row; for everyone else it is the customer itself, so this is
        // arithmetically identical to the pre-4a behaviour for every standalone customer.
        Customer creditAccount = creditAccountOf(customer);
        if (creditAccount.getCreditLimit() == null) return;   // no limit set = no check

        // What this sale leaves unpaid. A STORE_CREDIT tender was already capped to the real balance by
        // capStoreCreditTender and counts as paid, so redeemed credit correctly REDUCES exposure here.
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (SagaLine l : lines) {
            grandTotal = grandTotal.add(l.netAmount() == null ? BigDecimal.ZERO : l.netAmount());
        }
        BigDecimal paid = BigDecimal.ZERO;
        if (dto.getTenders() != null) {
            for (com.myplus.business_service.dto.TenderDTO t : dto.getTenders()) {
                if (t != null && t.getAmount() != null) paid = paid.add(t.getAmount());
            }
        }
        if (paid.signum() == 0 && dto.getPaidAmount() != null) paid = dto.getPaidAmount();   // legacy callers
        BigDecimal unpaid = grandTotal.subtract(paid);

        // The balance is the POOL: every customer drawing on this credit account, summed. For a standalone
        // customer that is a single-member group returning its own due — unchanged. For a trade group it is what
        // makes the limit meaningful: three branches of one company can no longer each spend the company's limit.
        BigDecimal pooledDue = groupExposure(creditAccount, customer);

        CreditLimitPolicy.Verdict verdict = CreditLimitPolicy.evaluate(
                pooledDue, unpaid, editingDue, creditAccount.getCreditLimit());
        boolean acknowledged = Boolean.TRUE.equals(dto.getCreditAcknowledged());

        switch (CreditLimitPolicy.decide(verdict, policy, acknowledged)) {
            case PROCEED -> {
                // Breached but allowed (acknowledged, or policy=off): say so on the receipt message anyway,
                // so an accepted overage is visible afterwards rather than only in the operator's memory.
                if (verdict.breached()) dto.getWarnings().add(overLimitMessage(customer, creditAccount, verdict));
            }
            case CONFIRM -> throw new CreditConfirmationRequiredException(
                    overLimitMessage(customer, creditAccount, verdict) + " Continue?");
            case REFUSE -> throw new com.myplus.common.web.exception.ValidationException(
                    overLimitMessage(customer, creditAccount, verdict)
                            + " Selling beyond the credit limit is blocked for this organization.");
        }
    }

    // O7 D2: `creditAccountOf` and `groupExposure` MOVED to CreditStandingService and are delegated to below.
    //
    // They were private here, and the booker's new "is this outlet over its limit?" read needs the same two
    // rules. Copying them would have created a second definition that answers differently the first time
    // either changes — the booker told one thing at the counter, the sale enforcing another. One definition,
    // used by both the read and the write, is the only version of this that cannot drift.

    /** @see CreditStandingService#creditAccountOf */
    private Customer creditAccountOf(Customer customer) {
        return creditStandingService.creditAccountOf(customer);
    }

    /** @see CreditStandingService#groupExposure */
    private BigDecimal groupExposure(Customer creditAccount, Customer customer) {
        return creditStandingService.groupExposure(creditAccount, customer);
    }

    /**
     * Names the GROUP when the sale is on a branch of one, so the cashier is told whose limit is actually being
     * hit — "Al-Karam Distributors (group) would be 12,000 over…" rather than naming the branch, whose own limit
     * may well be blank and whose own balance is not what breached.
     */
    private static String overLimitMessage(Customer customer, Customer creditAccount, CreditLimitPolicy.Verdict v) {
        boolean grouped = !creditAccount.getCustomerId().equals(customer.getCustomerId());
        String name = grouped ? creditAccount.getName() : customer.getName();
        if (name == null || name.isBlank()) name = grouped ? "This account group" : "This customer";
        return name + (grouped ? " (group)" : "") + " would be "
                + v.over().setScale(2, java.math.RoundingMode.HALF_UP)
                + " over their credit limit of " + v.limit().setScale(2, java.math.RoundingMode.HALF_UP)
                + " (they would owe " + v.exposure().setScale(2, java.math.RoundingMode.HALF_UP) + ").";
    }

    /**
     * B2B-P2 (#10): resolve this basket's contract/tier prices in ONE call to catalog-service.
     *
     * <p>Returns an empty map — meaning "price everything at catalog" — for a walk-in with no account and no
     * tier, and for ANY failure. That fallback is the important part: a pricing outage degrades a sale to
     * today's behaviour rather than stopping a shop from selling. A shop that cannot take money is a far worse
     * outcome than a shop that misses a discount on one invoice.
     */
    private java.util.Map<Long, com.myplus.commerce.contracts.dto.PriceQuoteLine> quoteBasket(
            CustomerHistoryDTO dto) {
        java.util.Map<Long, com.myplus.commerce.contracts.dto.PriceQuoteLine> byProduct = new java.util.HashMap<>();
        if (dto == null || dto.getSales() == null || dto.getSales().isEmpty()) return byProduct;

        Long customerId = (dto.getCustomer() != null) ? dto.getCustomer().getCustomerId() : null;
        String customerType = (dto.getCustomer() != null && dto.getCustomer().getCustomerType() != null)
                ? dto.getCustomer().getCustomerType().name() : null;
        // Nothing to price against: no account and no tier means no rule can match, so skip the call entirely
        // rather than pay a round trip to be told "catalog price".
        if (customerId == null && customerType == null) return byProduct;

        try {
            com.myplus.commerce.contracts.dto.PriceQuote req = new com.myplus.commerce.contracts.dto.PriceQuote();
            req.setCustomerId(customerId);
            req.setCustomerType(customerType);
            java.util.List<com.myplus.commerce.contracts.dto.PriceQuoteLine> reqLines = new ArrayList<>();
            for (SellDTO sd : dto.getSales()) {
                if (sd != null && sd.getProductId() != null) {
                    reqLines.add(com.myplus.commerce.contracts.dto.PriceQuoteLine.of(
                            sd.getProductId(),
                            sd.getQuantity() == null ? BigDecimal.ONE : BigDecimal.valueOf(sd.getQuantity())));
                }
            }
            if (reqLines.isEmpty()) return byProduct;
            req.setLines(reqLines);

            com.myplus.commerce.contracts.dto.PriceQuote resp = catalogClient.quote(req);
            if (resp != null && resp.getLines() != null) {
                for (com.myplus.commerce.contracts.dto.PriceQuoteLine l : resp.getLines()) {
                    // Only a line that actually matched a RULE is worth carrying — a CATALOG line would just
                    // restate the price buildLines already has.
                    if (l != null && l.getProductId() != null && l.getRuleId() != null) {
                        byProduct.put(l.getProductId(), l);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Price quote unavailable — pricing this sale at catalog rates ({})", e.toString());
        }
        return byProduct;
    }

    /** The first rule-sourced price reason on the sale, or null when every line was priced at catalog. */
    private static String firstPriceReason(CustomerHistoryDTO dto) {
        if (dto == null || dto.getSales() == null) return null;
        for (SellDTO s : dto.getSales()) {
            if (s != null && s.getPriceReason() != null && !s.getPriceReason().isBlank()) {
                return s.getPriceReason();
            }
        }
        return null;
    }

    public List<SagaLine> buildLines(CustomerHistoryDTO dto, java.util.Map<Long, String> productNames) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long orgId = user.getOrganizationId();
        Long userId = user.getUserId();
        var taxSetting = taxService.settingsFor(orgId);   // G3: the org's tax policy, once per sale
        // B1: read the pharmacy policy ONCE per sale, not per line. Inert for a non-pharmacy tenant — no product
        // of theirs carries the flag, so the check below never fires.
        boolean requireRx = settingsService.getBool("pharmacy.rx.requirePrescription");
        // B2B-P2 (#10): resolve contract/tier prices for the WHOLE basket in ONE call, before the line loop.
        // Per-line would double the catalog round trips this method already makes, on every sale.
        java.util.Map<Long, com.myplus.commerce.contracts.dto.PriceQuoteLine> quoted = quoteBasket(dto);
        List<SagaLine> lines = new ArrayList<>();
        for (SellDTO s : dto.getSales()) {
            // M4e (slice 101): productId-native — every caller (POS + pharmacy) submits productId now.
            Long productId = s.getProductId();
            if (productId == null) throw new RuntimeException("Sale line has no productId — submit productId-native.");
            ProductRef product = catalogClient.getProduct(productId);
            String pName = (product != null && product.getName() != null) ? product.getName()
                    : (s.getItemName() != null ? s.getItemName() : ("product " + productId));
            // B1: a prescription-only medicine may not leave the counter on a sale that declares no prescription.
            // Server-side and privilege-independent — this is a clinical rule, not a permission, so it binds a
            // super-user too. Costs nothing extra: the flag rides on the ref this loop already fetched.
            if (requireRx && product != null && Boolean.TRUE.equals(product.getRxRequired())
                    && dto.getPrescriptionId() == null) {
                throw new com.myplus.common.web.exception.ValidationException(pName
                        + " is prescription-only — start this sale from the prescription (Dispense), or record the"
                        + " prescription first.");
            }
            if (productNames != null) productNames.put(productId, pName);
            // B2B-P2: a resolved contract/tier price REPLACES the catalog price for this line — it is the
            // price this customer is entitled to, so discount, tax and the margin/credit guards all work off
            // it exactly as they work off the catalog price for a walk-in.
            com.myplus.commerce.contracts.dto.PriceQuoteLine q = quoted.get(productId);
            if (q != null && q.getUnitPrice() != null) {
                s.setPriceReason(q.getReason());
            }
            BigDecimal catalogPrice = (q != null && q.getUnitPrice() != null) ? q.getUnitPrice()
                    : (product != null && product.getSellingPrice() != null)
                    ? product.getSellingPrice() : BigDecimal.ZERO;
            // The rate this line SOLD at = the cashier's rate (may override catalog); fall back to catalog. The
            // catalog price is snapshotted separately so reports show BOTH "catalog price" and "sold at".
            BigDecimal soldRate = (s.getSellRate() != null && s.getSellRate().compareTo(BigDecimal.ZERO) > 0)
                    ? s.getSellRate() : catalogPrice;
            BigDecimal productTaxRate = product != null ? product.getTaxRate() : null;
            // The line total is DERIVED here — qty × the rate this line sold at — never taken from the client.
            // Trusting the submitted totalAmount is how a line came to be stored as rate=1000 (catalog fallback)
            // with total=850 (the cashier's real price): two numbers from different sources, contradicting each
            // other, and no way for the row to be right. Derive it and the contradiction cannot be persisted.
            BigDecimal qty = s.getQuantity() != null ? BigDecimal.valueOf(s.getQuantity()) : BigDecimal.ONE;
            BigDecimal lineTotal = soldRate.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal discount = resolveDiscount(s, lineTotal);
            String discountType = resolveDiscountType(s, discount);
            BigDecimal base = lineTotal.subtract(discount);
            if (base.compareTo(BigDecimal.ZERO) < 0) base = BigDecimal.ZERO;
            TaxResult tax = taxService.taxForLine(base, productTaxRate, taxSetting);
            // SF-10: snapshot the product's latest purchase rate as the unit COGS for margin reporting (null if
            // this product was never purchased in the tenant → margin shows blank for that line).
            BigDecimal costPrice = null;
            List<BigDecimal> recentCosts = purchaseRepo.findRecentCosts(productId, orgId, userId,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            if (!recentCosts.isEmpty()) costPrice = recentCosts.get(0);
            // totalAmount = qty × sold rate (before discount/tax). netAmount = what the customer is actually
            // charged for this line (discounted base + tax) — DERIVED, not the client's figure. The sell form
            // posts its "Net Amount" box, which actually holds PROFIT (total − cost×qty − discount); persisting
            // that made Sell.netAmount mean three different things and made the margin report — which computes
            // netAmount − cost×qty — subtract the cost twice whenever a purchase rate was present.
            lines.add(new SagaLine(productId, s.getQuantity(), soldRate, discount,
                    lineTotal, tax.gross(), s.getSrp(),
                    tax.rate(), tax.tax(), tax.gross(), catalogPrice, discountType, costPrice,
                    (q != null && q.getRuleId() != null) ? q.getReason() : null,
                    // B2B-P3g: free goods ride through untouched — they are printed, not priced. Bonus takes
                    // no part in lineTotal, tax or margin, which is exactly why it can be carried safely
                    // before decision D-2 settles whether it should also move stock.
                    s.getBonusQuantity()));
        }
        return lines;
    }

    /** The line's discount as an absolute amount. A "%"/"1" type is a percent of the line total; anything else is
     *  already an amount. Read from the line's stock (bsellDiscount + bsellDiscountType) — where the sell form binds
     *  the discount (the discounted net #sellrm is display-only and never submitted). */
    private BigDecimal resolveDiscount(SellDTO s, BigDecimal lineTotal) {
        var st = s.getStock();
        if (st == null || st.getBsellDiscount() == null) return BigDecimal.ZERO;
        BigDecimal d = st.getBsellDiscount();
        if (d.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        String type = st.getBsellDiscountType();
        if ("%".equals(type) || "1".equals(type)) {
            return lineTotal.multiply(d).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }
        return d;   // absolute amount
    }

    /** Human-readable discount type for the sell history table (Sell.dt). Blank when there's no discount so the
     *  column stays empty rather than labelling a zero discount. Mirrors resolveDiscount's %/amount decision. */
    private String resolveDiscountType(SellDTO s, BigDecimal resolvedDiscount) {
        if (resolvedDiscount == null || resolvedDiscount.compareTo(BigDecimal.ZERO) <= 0) return "";
        var st = s.getStock();
        String type = st != null ? st.getBsellDiscountType() : null;
        return ("%".equals(type) || "1".equals(type)) ? "%" : "Amount";
    }

    /** Turn the reserve's raw "product 891: only 7 sellable, 10 requested" reason into a name-resolved, cashier-
     *  friendly sentence. Falls back to a generic line when the reserve gave no detail. */
    private String friendlyOutOfStock(String reason, java.util.Map<Long, String> names) {
        if (reason == null || reason.isBlank()) return "Not enough sellable stock to complete the sale.";
        for (java.util.Map.Entry<Long, String> e : names.entrySet()) {
            reason = reason.replace("product " + e.getKey(), "'" + e.getValue() + "'");
        }
        return "Not enough sellable stock — " + reason
                + ". Expired or held stock is not sellable; add a fresh batch to sell more.";
    }

    private void safeRelease(String reservationId) {
        try {
            inventoryClient.release(reservationId);
        } catch (RuntimeException ignore) {
            LOG.warn("Compensating release failed for reservation {} (held stock will lapse/cleanup later)", reservationId);
        }
    }
}
