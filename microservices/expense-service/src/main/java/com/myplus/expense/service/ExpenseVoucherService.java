package com.myplus.expense.service;

import com.myplus.common.security.time.TenantClock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.dto.ExpenseDtos.LineRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherView;
import com.myplus.expense.entity.ExpenseCategory;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseVoucherRepo;

import lombok.RequiredArgsConstructor;

/**
 * The direct expense voucher (EX-1): record what was paid, post it to the books, void it with a reason.
 *
 * <h3>Order inside the posting transaction — allocate LATE</h3>
 * Validate everything, save the voucher, THEN take the EXP- number (common-docnum holds a row lock on the
 * tenant's counter until commit), then capture the ledger posting in the outbox. Nothing remote happens inside:
 * finance is reached after commit, so a slow ledger never holds the counter lock.
 *
 * <h3>Duplicate protection is the UNIQUE index, not a pre-check</h3>
 * The Idempotency-Key is stored with a UNIQUE (organization_id, idempotency_key). A replay finds the first
 * voucher and returns it; a concurrent duplicate loses on the index and is answered with the winner (DUP-1).
 */
@Service
@RequiredArgsConstructor
public class ExpenseVoucherService {

    static final String DOC_TYPE = "EXPENSE";
    static final int MAX_LINES = 50;   // keeps the outbox payload far inside its column (V1)
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private final ExpenseVoucherRepo repo;
    private final ExpenseCategoryService categories;
    private final ExpenseOutboxService outbox;
    private final ExpenseAuditService audit;
    private final ExpenseAccess access;
    private final DocumentNumberService numbers;
    private final ExpenseTagService tags;
    private final com.myplus.expense.repository.ExpenseBillPaymentRepo billPayments;
    private final ExpenseSettings expenseSettings;   // EX-2f
    private final ReceiptService receipts;            // EX-5

    @Transactional(readOnly = true)
    public PageResponse<VoucherView> list(LocalDate from, LocalDate to, String status, int page, int size) {
        return list(from, to, status, null, page, size);
    }

    /** EX-6 — {@code claim}: only claims in that state (the approvers' queue: SUBMITTED). */
    @Transactional(readOnly = true)
    public PageResponse<VoucherView> list(LocalDate from, LocalDate to, String status, String claim, int page, int size) {
        int s = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        String c = blankToNull(claim);
        PageResponse<VoucherView> out = PageResponse.of(repo.search(access.org(), access.visibleUserId(), blankToNull(status), from, to,
                c == null ? null : c.toUpperCase(java.util.Locale.ROOT), PageRequest.of(Math.max(page, 0), s)), VoucherView::of);
        // EX-5 — how many receipts each row has, in one query for the page
        if (out.getContent() != null && !out.getContent().isEmpty()) {
            java.util.Map<Long, Integer> n = receipts.counts(out.getContent().stream().map(VoucherView::id).toList());
            out.setContent(out.getContent().stream().map(v -> v.withReceipts(n.getOrDefault(v.id(), 0))).toList());
        }
        return out;
    }

    /**
     * EX-2d / E4 — the total under the list, over the SAME rows the list can show this caller (a user: their own),
     * not only the page on screen. Posted expenses only; a void or a draft is not money spent.
     */
    @Transactional(readOnly = true)
    public com.myplus.expense.dto.ExpenseDtos.VoucherTotals totals(LocalDate from, LocalDate to) {
        var t = repo.totals(access.org(), access.visibleUserId(), from, to);
        return new com.myplus.expense.dto.ExpenseDtos.VoucherTotals(t == null ? 0 : t.getCount(),
                t == null || t.getTotal() == null ? java.math.BigDecimal.ZERO : t.getTotal());
    }

    @Transactional(readOnly = true)
    public VoucherView get(Long id) {
        ExpenseVoucher v = visible(id);
        return VoucherView.of(v).withReceipts(receipts.counts(java.util.List.of(v.getId())).getOrDefault(v.getId(), 0));
    }

    /** Record (and, when {@code post}, post) a voucher. A replayed Idempotency-Key returns the first voucher. */
    @Transactional
    public VoucherView record(VoucherRequest r, boolean post, String idempotencyKey) {
        access.assertModuleOn();
        Long org = access.org();
        String key = blankToNull(idempotencyKey);
        if (key == null) throw new ValidationException("Missing Idempotency-Key header — the screen sends one per save.");
        if (key.length() > 80) throw new ValidationException("Idempotency-Key is too long.");

        Optional<ExpenseVoucher> replay = repo.findByOrganizationIdAndIdempotencyKey(org, key);
        if (replay.isPresent()) return VoucherView.of(replay.get());

        ExpenseVoucher v = build(org, r);
        v.setIdempotencyKey(key);
        try {
            v = repo.saveAndFlush(v);
        } catch (DataIntegrityViolationException raced) {
            // a concurrent save with the same key won on the UNIQUE index — answer with its voucher
            throw new ValidationException("This expense is already being saved. Refresh the list.");
        }
        audit.record("EXPENSE_RECORDED", "EXPENSE", String.valueOf(v.getId()), v.getTotal(), v.getPaidFrom(), null);
        receipts.attachOnSave(v, r.receiptIds());           // EX-5: the receipts, and "required above X", in this save
        if (post) postInTx(v);
        return VoucherView.of(v).withReceipts(r.receiptIds() == null ? 0 : (int) r.receiptIds().stream().distinct().count());
    }

    @Transactional
    public VoucherView post(Long id) {
        access.assertModuleOn();
        ExpenseVoucher v = visible(id);
        // EX-6 — a claim is posted by APPROVING it (owner/admin, not the claimant); this command would skip that.
        if (v.isClaim()) throw new ValidationException("A claim goes to the books when an owner or admin approves it.");
        postInTx(v);
        return VoucherView.of(v);
    }

    @Transactional
    public VoucherView voidVoucher(Long id, String reason) {
        access.assertModuleOn();
        if (!access.seesAll()) throw new AccessDeniedException("Only an owner or admin can void an expense.");
        ExpenseVoucher v = visible(id);
        if (v.isOwed()) {
            // FP-3 — locked like a payment, so a void and a payment to the same bill cannot interleave; a payment
            // still waiting for finance's answer blocks the void exactly as a recorded one does.
            v = repo.lockForPayment(id, access.org()).orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
            if (billPayments.sumPending(v.getId()).signum() > 0)
                throw new ValidationException("A payment to this bill is still being recorded. Try again in a minute.");
        }
        try {
            v.voidWith(reason, access.userId(), LocalDateTime.now());
            v.setVoidPostedOn(TenantClock.today());          // EX-8a — the reversal's own date, kept
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        if (v.voidNeedsReversal()) outbox.enqueue(v, VoucherPostings.reversal(v, v.getVoidPostedOn()));
        if (v.isBill()) outbox.enqueuePayable(v);     // FP-3: the subledger document goes VOID
        audit.record("EXPENSE_VOIDED", "EXPENSE", v.getVoucherNo(), v.getTotal(), null, v.getVoidReason());
        return VoucherView.of(v);
    }

    /**
     * EX-3 — the idempotent receiver for a till pay-out. business-service has already recorded the drawer movement
     * and decided the module is on (where the cash moved); this makes the matching POSTED voucher, paid from the
     * DRAWER (Cr 1000), and returns it. A redelivery of the same movement finds the first voucher and returns it:
     * UNIQUE (organization_id, source, source_ref) carries the guarantee, the lookup is only the fast path.
     */
    @Transactional
    public com.myplus.commerce.contracts.dto.ExpenseVoucherRef recordFromDrawer(
            com.myplus.commerce.contracts.dto.DrawerExpenseRequest r) {
        Long org = access.org();
        if (r == null || r.getMovementId() == null) throw new ValidationException("A pay-out needs its movement id.");
        String ref = String.valueOf(r.getMovementId());
        Optional<ExpenseVoucher> replay = repo.findByOrganizationIdAndSourceAndSourceRef(org, ExpenseVoucher.SOURCE_DRAWER, ref);
        if (replay.isPresent()) return new com.myplus.commerce.contracts.dto.ExpenseVoucherRef(replay.get().getId(), replay.get().getVoucherNo());

        LocalDate date = r.getDate() == null ? TenantClock.today() : r.getDate();
        if (r.getAmount() == null || r.getAmount().signum() <= 0) throw new ValidationException("A pay-out needs an amount.");
        ExpenseCategory c = categories.categoryForDrawer(org, r.getCategoryId());   // EX-2e: switched off since is fine
        ExpenseVoucher v = new ExpenseVoucher();
        v.setOrganizationId(org);
        v.setUserId(access.userId());
        v.setStoreId(r.getStoreId());
        v.setVoucherDate(date);
        v.setPaidFrom(PaidFrom.DRAWER.name());
        v.setPayeeName(limit(r.getReason(), 160));
        v.setNote("Till pay-out");
        v.setSource(ExpenseVoucher.SOURCE_DRAWER);
        v.setSourceRef(ref);
        v.setCreatedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setCategoryId(c.getId());
        l.setAccountCode(c.getAccountCode());
        l.setCategoryName(c.getName());
        l.setDescription(limit(r.getReason(), 255));
        l.setAmount(r.getAmount().setScale(2, RoundingMode.HALF_UP));
        v.addLine(l);
        try {
            v = repo.saveAndFlush(v);
        } catch (DataIntegrityViolationException raced) {
            // a concurrent delivery of the same movement won on the UNIQUE index — this one is a redelivery
            throw new ValidationException("This pay-out is already recorded.");
        }
        audit.record("EXPENSE_RECORDED", "EXPENSE", String.valueOf(v.getId()), v.getTotal(), "DRAWER " + ref, null);
        postInTx(v);
        return new com.myplus.commerce.contracts.dto.ExpenseVoucherRef(v.getId(), v.getVoucherNo());
    }

    /**
     * EX-1b — send again what the books refused or never took: the posting of a POSTED voucher, the reversal of a
     * VOIDED one, and a bill's subledger snapshot. Same event keys, so finance books each once however often it is
     * sent. Never the posting of a voided voucher (it voided with no reversal). Anyone who may see the expense may ask —
     * it changes no amount; whether the books accept it is finance's decision (a period still closed is refused again,
     * with its reason). Saved through the entity, so a void racing it gets the version conflict, not a silent overlap.
     */
    @Transactional
    public VoucherView postAgain(Long id) {
        access.assertModuleOn();
        ExpenseVoucher v = visible(id);
        java.util.Set<String> kinds = ExpenseVoucher.POSTED.equals(v.getStatus())
                ? java.util.Set.of(VoucherPostings.EXPENSE, VoucherPostings.PAYABLE)
                : ExpenseVoucher.VOIDED.equals(v.getStatus())
                    ? java.util.Set.of(VoucherPostings.EXPENSE_REVERSAL, VoucherPostings.PAYABLE)
                    : java.util.Set.of();
        int requeued = outbox.redrive(v.getId(), kinds);
        if (requeued == 0) throw new ValidationException("Nothing is waiting to be sent to the books for this expense.");
        if (ExpenseVoucher.POSTED.equals(v.getStatus()) && ExpenseVoucher.PS_FAILED.equals(v.getPostingStatus()))
            v.setPostingStatus(ExpenseVoucher.PS_PENDING);
        v.setPostingError(null);
        v.setUpdatedAt(LocalDateTime.now());
        v = repo.saveAndFlush(v);
        audit.record("EXPENSE_POSTED_AGAIN", "EXPENSE", v.getVoucherNo(), v.getTotal(), requeued + " row(s) re-sent", null);
        return VoucherView.of(v);
    }

    /**
     * EX-8b — what this expense may duplicate: the same payee, date and amount already recorded in the business. A
     * warning, never a refusal (two identical taxi fares on one day are real); the screen asks before saving. Named by
     * number only, so a member learns no more about a colleague's expense than that it exists.
     */
    @Transactional(readOnly = true)
    public List<String> possibleDuplicates(LocalDate date, java.math.BigDecimal amount, String payee) {
        access.assertModuleOn();
        if (date == null || amount == null || amount.signum() <= 0 || payee == null || payee.isBlank()) return List.of();
        return repo.sameExpense(access.org(), date, amount.setScale(2, java.math.RoundingMode.HALF_UP), payee.trim().toLowerCase())
                .stream().map(v -> v.getVoucherNo() != null ? v.getVoucherNo() : "a claim waiting for approval")
                .distinct().toList();
    }

    /** A DRAFT may be discarded; anything posted stays, and is voided instead. */
    @Transactional
    public void deleteDraft(Long id) {
        access.assertModuleOn();
        ExpenseVoucher v = visible(id);
        if (!ExpenseVoucher.DRAFT.equals(v.getStatus()))
            throw new ValidationException("A posted expense cannot be deleted. Void it instead, with a reason.");
        if (v.isClaim()) throw new ValidationException("A claim is withdrawn, not deleted — its trail is kept.");   // EX-6
        repo.delete(v);
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────────────

    void postInTx(ExpenseVoucher v) {   // EX-6: also the claim's approval
        // the number is taken LAST before the outbox: the counter row stays locked until this commits
        long seq = numbers.next(v.getOrganizationId(), DOC_TYPE);
        try {
            v.post(String.format("EXP-%06d", seq), LocalDateTime.now());
        } catch (IllegalStateException e) {
            throw new ValidationException(e.getMessage());
        }
        repo.saveAndFlush(v);
        outbox.enqueue(v, VoucherPostings.post(v));
        // FP-3/E11 — a bill joins finance's payables subledger when its journal LANDS (ExpenseOutboxService.stampVoucher),
        // so the ledger never holds a bill that GL 2000 does not.
        audit.record("EXPENSE_POSTED", "EXPENSE", v.getVoucherNo(), v.getTotal(), v.getPaidFrom(), null);
    }

    /** The voucher, if this caller may see it. Another tenant's — or, for a user, a colleague's — is "not found". */
    ExpenseVoucher visible(Long id) {
        ExpenseVoucher v = repo.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        Long only = access.visibleUserId();
        if (only != null && !only.equals(v.getUserId())) throw new ResourceNotFoundException("Expense not found");
        return v;
    }

    private ExpenseVoucher build(Long org, VoucherRequest r) { return build(org, r, false); }

    /** EX-6 — {@code asClaim}: the claim path, the only one that may build an EMPLOYEE voucher. */
    ExpenseVoucher build(Long org, VoucherRequest r, boolean asClaim) {
        if (r == null) throw new ValidationException("Nothing to save.");
        LocalDate today = TenantClock.today();
        LocalDate date = r.voucherDate() == null ? today : r.voucherDate();
        if (date.isAfter(today)) throw new ValidationException("An expense cannot be dated in the future.");
        int back = expenseSettings.backdateDays();          // EX-2f: the owner's setting (default 30), was a constant 365
        if (date.isBefore(today.minusDays(back)))
            throw new ValidationException(back == 0 ? "An expense must be dated today."
                    : "An expense can be dated at most " + back + " days back. An owner can change this in Expenses → Settings.");
        PaidFrom from;
        try { from = PaidFrom.of(r.paidFrom()); }
        catch (IllegalArgumentException e) { throw new ValidationException(e.getMessage()); }
        // EX-3 — a DRAWER voucher exists only BECAUSE a till movement does; typing one here would put an expense
        // in the books with no cash leaving any drawer, and (being a till expense) it could never be voided.
        if (from == PaidFrom.DRAWER)
            throw new ValidationException("A pay-out from the till is recorded at the till (Till → Cash Drawer).");
        // EX-6 — money paid from your own pocket is a CLAIM: it is owed back only once an owner or admin approves it,
        // so it can never be recorded (and posted) straight in.
        if (from == PaidFrom.EMPLOYEE && !asClaim)
            throw new ValidationException("Money you paid yourself is a claim: choose \"Me (claim)\" so it goes for approval.");
        if (asClaim && from != PaidFrom.EMPLOYEE)
            throw new ValidationException("A claim is money you paid yourself.");
        // FP-3 — a bill is owed to a supplier business-service confirms for THIS caller; nothing else carries one.
        String supplierName = null;
        if (from == PaidFrom.AP) {
            supplierName = tags.confirmSupplier(r.supplierId());
            if (r.dueDate() != null && r.dueDate().isBefore(date))
                throw new ValidationException("A bill cannot be due before its own date.");
        } else if (r.supplierId() != null || r.dueDate() != null) {
            throw new ValidationException("A supplier and due date belong to a bill (pay later). Choose \"Bill\", or clear them.");
        }

        List<LineRequest> lines = r.lines();
        if (lines == null || lines.isEmpty()) throw new ValidationException("Add at least one expense line.");
        if (lines.size() > MAX_LINES) throw new ValidationException("An expense can have at most " + MAX_LINES + " lines.");

        ExpenseVoucher v = new ExpenseVoucher();
        v.setOrganizationId(org);
        v.setUserId(access.userId());
        // E8 — a branch is not taken from the request until it can be checked against the caller's own branches (EX-8e).
        // The till's pay-outs keep theirs: business-service sets it from the drawer that paid (recordFromDrawer).
        if (r.storeId() != null)
            throw new ValidationException("Choosing a branch for an expense is not available yet. A pay-out from the till carries its branch.");
        v.setVoucherDate(date);
        v.setPaidFrom(from.name());
        v.setPayeeName(limit(r.payeeName(), 160));
        if (from == PaidFrom.AP) {
            v.setSupplierId(r.supplierId());
            v.setSupplierName(limit(supplierName, 160));
            v.setDueDate(r.dueDate());
            if (v.getPayeeName() == null) v.setPayeeName(v.getSupplierName());
        }
        v.setNote(limit(r.note(), 500));
        v.setCreatedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        java.util.Map<String, java.util.List<com.myplus.commerce.contracts.dto.ExpenseTagView>> tagLists = new java.util.HashMap<>();
        for (LineRequest lr : lines) {
            if (lr == null || lr.amount() == null || lr.amount().signum() <= 0)
                throw new ValidationException("Each line needs an amount greater than zero.");
            BigDecimal amt = lr.amount().setScale(2, RoundingMode.HALF_UP);
            if (amt.compareTo(MAX_AMOUNT) > 0) throw new ValidationException("That amount is too large.");
            ExpenseCategory c = categories.activeCategory(org, lr.categoryId());
            ExpenseVoucherLine l = new ExpenseVoucherLine();
            l.setCategoryId(c.getId());
            l.setAccountCode(c.getAccountCode());     // snapshot
            l.setCategoryName(c.getName());           // snapshot
            l.setDescription(limit(lr.description(), 255));
            l.setAmount(amt);
            // EX-2b — confirmed by the owning module with the caller's identity; the label is the module's.
            String label = tags.confirm(lr.tagType(), lr.tagId(), tagLists);
            if (label != null) {
                l.setTagType(ExpenseTagService.normaliseType(lr.tagType()));
                l.setTagId(lr.tagId());
                l.setTagLabel(limit(label, 160));
            }
            v.addLine(l);
        }
        return v;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String limit(String s, int max) {
        String t = blankToNull(s);
        return t == null ? null : (t.length() > max ? t.substring(0, max) : t);
    }
}
