package com.myplus.expense.service;

import com.myplus.common.security.time.TenantClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PaymentRecordRequest;
import com.myplus.commerce.contracts.dto.PaymentRecordResult;
import com.myplus.commerce.contracts.dto.PaymentView;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseAdvanceBalance;
import com.myplus.expense.entity.ExpenseAdvanceMovement;
import com.myplus.expense.repository.ExpenseAdvanceBalanceRepo;
import com.myplus.expense.repository.ExpenseAdvanceMovementRepo;

/**
 * EX-7b — advances to staff. Giving one is Dr 1300 / Cr cash·bank; taking back what is unused is Dr cash·bank / Cr 1300;
 * a claim settled from it is the claim's own payment (method ADVANCE, {@link ExpenseBillService}). finance records the
 * money as an EMPLOYEE payment with purpose ADVANCE; this service makes sure each is recorded ONCE and keeps the member's
 * balance, with the same reserve, call and confirm steps a bill payment takes:
 * <ol>
 *   <li><b>Reserve</b> (own transaction): a PENDING movement keyed by the Idempotency-Key. A take-back also takes the
 *       amount off the member's balance here, under the balance row's lock, so it cannot be spent twice.</li>
 *   <li><b>Call</b> finance, outside any transaction, with {@code reference = EXPA-<org>-<id>}.</li>
 *   <li><b>Confirm</b>: RECORDED with the PV-/RCPT- number; a give adds to the balance only now, once the money is out.</li>
 * </ol>
 * A lost answer stays PENDING: pressing again, or the reconciler, LOOKS FOR the reference in finance before sending.
 * A refusal releases the reservation (a take-back's amount goes back on the balance).
 */
@Service
public class ExpenseAdvanceService {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseAdvanceService.class);

    public record BalanceView(Long userId, String memberName, BigDecimal balance) {
        static BalanceView of(ExpenseAdvanceBalance b) { return new BalanceView(b.getUserId(), b.getMemberName(), b.getBalance()); }
    }

    public record MovementView(Long id, Long userId, String memberName, String kind, BigDecimal amount, String method,
                               LocalDate movedOn, String status, String receiptNo, String lastError) {
        static MovementView of(ExpenseAdvanceMovement m) {
            return new MovementView(m.getId(), m.getUserId(), m.getMemberName(), m.getKind(), m.getAmount(), m.getMethod(),
                    m.getMovedOn(), m.getStatus(), m.getReceiptNo(), m.getLastError());
        }
    }

    public record AdvanceRequest(Long userId, BigDecimal amount, String method, String note) { }

    private final ExpenseAdvanceBalanceRepo balances;
    private final ExpenseAdvanceMovementRepo movements;
    private final ExpenseAccess access;
    private final StaffDirectory staff;
    private final ExpenseAuditService audit;
    private final ObjectProvider<FinanceClient> finance;
    private final TransactionTemplate tx;

    public ExpenseAdvanceService(ExpenseAdvanceBalanceRepo balances, ExpenseAdvanceMovementRepo movements, ExpenseAccess access,
                                 StaffDirectory staff, ExpenseAuditService audit, ObjectProvider<FinanceClient> finance,
                                 PlatformTransactionManager txm) {
        this.balances = balances;
        this.movements = movements;
        this.access = access;
        this.staff = staff;
        this.audit = audit;
        this.finance = finance;
        this.tx = new TransactionTemplate(txm);
    }

    /** An owner or admin sees every member's balance; anyone else, only their own. */
    public List<BalanceView> balances() {
        access.assertClaimsOn();
        Long org = access.org();
        return tx.execute(st -> {
            if (access.canApprove())
                return balances.findByOrganizationIdOrderByMemberNameAsc(org).stream().map(BalanceView::of).toList();
            return balances.findByOrganizationIdAndUserId(org, access.userId()).map(BalanceView::of).map(List::of).orElse(List.of());
        });
    }

    /** One member's advances given and taken back, newest first (owner/admin; a member, their own). */
    public List<MovementView> movements(Long userId) {
        access.assertClaimsOn();
        if (!access.canApprove() && (userId == null || !userId.equals(access.userId())))
            throw new AccessDeniedException("Only an owner or admin can see another member's advances.");
        return tx.execute(st -> movements.findTop50ByOrganizationIdAndUserIdOrderByIdDesc(access.org(), userId)
                .stream().map(MovementView::of).toList());
    }

    /** The staff an advance may be given to (owner/admin). */
    public List<StaffDirectory.Member> staff() {
        access.assertClaimsOn();
        decider();
        return staff.staff();
    }

    public MovementView give(AdvanceRequest r, String key) {
        return move(ExpenseAdvanceMovement.GIVE, r, key);
    }

    public MovementView takeBack(AdvanceRequest r, String key) {
        return move(ExpenseAdvanceMovement.TAKE_BACK, r, key);
    }

    private MovementView move(String kind, AdvanceRequest r, String idempotencyKey) {
        access.assertClaimsOn();
        decider();
        Long org = access.org();
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (key == null || key.isEmpty()) throw new ValidationException("Missing Idempotency-Key header — the screen sends one per advance.");
        if (key.length() > 80) throw new ValidationException("Idempotency-Key is too long.");
        Optional<ExpenseAdvanceMovement> earlier = tx.execute(st -> movements.findByOrganizationIdAndIdempotencyKey(org, key));
        if (earlier.isPresent()) {
            ExpenseAdvanceMovement e = earlier.get();
            if (!ExpenseAdvanceMovement.PENDING.equals(e.getStatus())) return MovementView.of(e);   // a replay answers
            return MovementView.of(settle(e));
        }
        if (r == null || r.userId() == null) throw new ValidationException("Choose the member.");
        if (r.userId().equals(access.userId()))
            throw new ValidationException("You cannot " + (ExpenseAdvanceMovement.GIVE.equals(kind) ? "give yourself an advance" : "take back your own advance")
                    + ". Another owner or admin does it.");
        if (r.amount() == null || r.amount().signum() <= 0) throw new ValidationException("Enter an amount greater than zero.");
        BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        String method = r.method() == null ? "CASH" : r.method().trim().toUpperCase();
        if (!method.equals("CASH") && !method.equals("BANK")) throw new ValidationException("An advance moves in cash or by bank.");
        // the server's own answer to "is this person staff here", from auth — never the screen's word
        StaffDirectory.Member who = staff.staffMember(r.userId())
                .orElseThrow(() -> new ValidationException("That person is not staff of this business."));

        ExpenseAdvanceMovement row;
        try {
            row = tx.execute(st -> reserve(org, kind, who, amount, method, r.note(), key));
        } catch (DataIntegrityViolationException raced) {
            throw new ValidationException("This advance is already being recorded. Refresh the list.");
        }
        return MovementView.of(settle(row));
    }

    /**
     * Every minute: a movement whose caller never learned the outcome. Found in finance → confirmed; not there →
     * released. Runs as the user who made it, so finance's scoped read sees their payments.
     */
    @Scheduled(fixedDelayString = "${expense.advance.reconcile-ms:60000}", initialDelay = 60000)
    public void reconcilePending() {
        if (finance.getIfAvailable() == null) return;
        for (ExpenseAdvanceMovement m : movements.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(
                ExpenseAdvanceMovement.PENDING, LocalDateTime.now().minusMinutes(2))) {
            try {
                GatewayIdentityForwarding.runAs(m.getCreatedBy(), m.getOrganizationId(), () -> {
                    Optional<PaymentView> found = findInFinance(m);
                    if (found.isPresent()) confirm(m.getId(), found.get().getId(), found.get().getReceiptNo());
                    else release(m.getId(), "Not recorded in the books — the advance did not go through.");
                });
            } catch (Exception e) {
                LOG.warn("advance movement {} could not be reconciled yet", m.getId(), e);
            }
        }
    }

    // ── steps ───────────────────────────────────────────────────────────────────────────────────────────

    private ExpenseAdvanceMovement reserve(Long org, String kind, StaffDirectory.Member who, BigDecimal amount, String method,
                                           String note, String key) {
        if (ExpenseAdvanceMovement.TAKE_BACK.equals(kind)) {
            ExpenseAdvanceBalance b = balances.lock(org, who.userId())
                    .orElseThrow(() -> new ValidationException("This member holds no advance."));
            try {
                b.take(amount);                 // reserved: the same advance can never be handed back twice
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new ValidationException(e.getMessage());
            }
            b.setUpdatedAt(LocalDateTime.now());
            balances.saveAndFlush(b);
        }
        ExpenseAdvanceMovement m = new ExpenseAdvanceMovement();
        m.setOrganizationId(org);
        m.setUserId(who.userId());
        m.setMemberName(trim(who.name(), 160));
        m.setKind(kind);
        m.setAmount(amount);
        m.setMethod(method);
        m.setMovedOn(TenantClock.today());
        m.setStatus(ExpenseAdvanceMovement.PENDING);
        m.setIdempotencyKey(key);
        m.setNote(trim(note, 255));
        m.setCreatedBy(access.userId());
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());
        m = movements.saveAndFlush(m);
        m.setReference("EXPA-" + org + "-" + m.getId());
        return movements.saveAndFlush(m);
    }

    private ExpenseAdvanceMovement settle(ExpenseAdvanceMovement row) {
        FinanceClient f = finance.getIfAvailable();
        if (f == null) throw new ValidationException("The books are not reachable right now. Try again in a moment — it will not be recorded twice.");
        Optional<PaymentView> already;
        try {
            already = findInFinance(row);
        } catch (Exception e) {
            throw new ValidationException("Could not check the books for this advance. Try again — it will not be recorded twice.");
        }
        if (already.isPresent()) return confirm(row.getId(), already.get().getId(), already.get().getReceiptNo());

        PaymentRecordResult res;
        try {
            res = f.recordPayment(request(row));
        } catch (org.springframework.web.client.HttpClientErrorException refused) {
            release(row.getId(), "The books refused this advance: " + refused.getStatusText());
            throw new ValidationException("The books refused this advance. " + ExpenseBillService.messageOf(refused.getResponseBodyAsString()));
        } catch (Exception lost) {
            LOG.warn("advance movement {} sent, answer lost — kept PENDING for the reconciler", row.getId(), lost);
            tx.executeWithoutResult(st -> movements.findById(row.getId()).ifPresent(m -> {
                m.setLastError("Waiting for the books to confirm: " + lost.getMessage());
                m.setUpdatedAt(LocalDateTime.now());
            }));
            throw new ValidationException("The books did not answer in time. Press again in a moment — it will not be recorded twice.");
        }
        return confirm(row.getId(), res.getId(), res.getReceiptNo());
    }

    /** Finance has it: RECORDED; a give now adds to what the member holds. Idempotent. */
    private ExpenseAdvanceMovement confirm(Long rowId, Long financeId, String receiptNo) {
        return tx.execute(st -> {
            ExpenseAdvanceMovement m = movements.findById(rowId).orElseThrow();
            if (ExpenseAdvanceMovement.RECORDED.equals(m.getStatus())) return m;
            if (m.isGive()) {
                ExpenseAdvanceBalance b = balances.lock(m.getOrganizationId(), m.getUserId()).orElseGet(() -> {
                    ExpenseAdvanceBalance n = new ExpenseAdvanceBalance();
                    n.setOrganizationId(m.getOrganizationId());
                    n.setUserId(m.getUserId());
                    n.setMemberName(m.getMemberName());
                    n.setUpdatedAt(LocalDateTime.now());
                    return n;
                });
                b.add(m.getAmount());
                if (m.getMemberName() != null) b.setMemberName(m.getMemberName());
                b.setUpdatedAt(LocalDateTime.now());
                balances.saveAndFlush(b);
            }
            m.setStatus(ExpenseAdvanceMovement.RECORDED);
            m.setFinancePaymentId(financeId);
            m.setReceiptNo(receiptNo);
            m.setLastError(null);
            m.setUpdatedAt(LocalDateTime.now());
            audit.record(m.isGive() ? "EXPENSE_ADVANCE_GIVEN" : "EXPENSE_ADVANCE_TAKEN_BACK", "EXPENSE",
                    receiptNo, m.getAmount(), m.getMemberName(), m.getMethod());
            return movements.saveAndFlush(m);
        });
    }

    /** Finance refused it (or never got it): FAILED; a take-back's reserved amount goes back on the balance. */
    private void release(Long rowId, String why) {
        tx.executeWithoutResult(st -> movements.findById(rowId).ifPresent(m -> {
            if (!ExpenseAdvanceMovement.PENDING.equals(m.getStatus())) return;
            if (!m.isGive()) balances.lock(m.getOrganizationId(), m.getUserId()).ifPresent(b -> {
                b.add(m.getAmount());
                b.setUpdatedAt(LocalDateTime.now());
                balances.saveAndFlush(b);
            });
            m.setStatus(ExpenseAdvanceMovement.FAILED);
            m.setLastError(why);
            m.setUpdatedAt(LocalDateTime.now());
            movements.saveAndFlush(m);
        }));
    }

    private Optional<PaymentView> findInFinance(ExpenseAdvanceMovement row) {
        List<PaymentView> list = finance.getObject().listPayments("EMPLOYEE", row.getUserId());
        if (list == null) return Optional.empty();
        return list.stream().filter(pv -> row.getReference() != null && row.getReference().equals(pv.getReference())).findFirst();
    }

    static PaymentRecordRequest request(ExpenseAdvanceMovement m) {
        return PaymentRecordRequest.builder()
                .direction(m.isGive() ? "DISBURSEMENT" : "RECEIPT")
                .partyType("EMPLOYEE").partyId(m.getUserId()).partyName(m.getMemberName())
                .purpose("ADVANCE")
                .amount(m.getAmount()).method(m.getMethod()).paidOn(m.getMovedOn())
                .reference(m.getReference())
                .sourceModule(ExpenseBillService.SOURCE_MODULE)
                .note(m.isGive() ? "Advance to " + m.getMemberName() : "Advance taken back from " + m.getMemberName())
                .allocations(List.of())
                .build();
    }

    /** Owner or admin: the people who hand out the business's money. */
    private void decider() {
        if (!access.canApprove()) throw new AccessDeniedException("Only an owner or admin can give or take back an advance.");
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > max ? t.substring(0, max) : t);
    }
}
