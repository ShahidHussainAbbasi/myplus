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
    static final int BACKDATE_DAYS = 365;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private final ExpenseVoucherRepo repo;
    private final ExpenseCategoryService categories;
    private final ExpenseOutboxService outbox;
    private final ExpenseAuditService audit;
    private final ExpenseAccess access;
    private final DocumentNumberService numbers;
    private final ExpenseTagService tags;
    private final com.myplus.expense.repository.ExpenseBillPaymentRepo billPayments;

    @Transactional(readOnly = true)
    public PageResponse<VoucherView> list(LocalDate from, LocalDate to, String status, int page, int size) {
        int s = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        return PageResponse.of(repo.search(access.org(), access.visibleUserId(), blankToNull(status), from, to,
                PageRequest.of(Math.max(page, 0), s)), VoucherView::of);
    }

    @Transactional(readOnly = true)
    public VoucherView get(Long id) {
        return VoucherView.of(visible(id));
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
        if (post) postInTx(v);
        return VoucherView.of(v);
    }

    @Transactional
    public VoucherView post(Long id) {
        access.assertModuleOn();
        ExpenseVoucher v = visible(id);
        postInTx(v);
        return VoucherView.of(v);
    }

    @Transactional
    public VoucherView voidVoucher(Long id, String reason) {
        access.assertModuleOn();
        if (!access.seesAll()) throw new AccessDeniedException("Only an owner or admin can void an expense.");
        ExpenseVoucher v = visible(id);
        if (v.isBill()) {
            // FP-3 — locked like a payment, so a void and a payment to the same bill cannot interleave; a payment
            // still waiting for finance's answer blocks the void exactly as a recorded one does.
            v = repo.lockForPayment(id, access.org()).orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
            if (billPayments.sumPending(v.getId()).signum() > 0)
                throw new ValidationException("A payment to this bill is still being recorded. Try again in a minute.");
        }
        try {
            v.voidWith(reason, access.userId(), LocalDateTime.now());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        if (v.voidNeedsReversal()) outbox.enqueue(v, VoucherPostings.reversal(v, TenantClock.today()));
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
        ExpenseCategory c = categories.activeCategory(org, r.getCategoryId());
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

    /** A DRAFT may be discarded; anything posted stays, and is voided instead. */
    @Transactional
    public void deleteDraft(Long id) {
        access.assertModuleOn();
        ExpenseVoucher v = visible(id);
        if (!ExpenseVoucher.DRAFT.equals(v.getStatus()))
            throw new ValidationException("A posted expense cannot be deleted. Void it instead, with a reason.");
        repo.delete(v);
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────────────

    private void postInTx(ExpenseVoucher v) {
        // the number is taken LAST before the outbox: the counter row stays locked until this commits
        long seq = numbers.next(v.getOrganizationId(), DOC_TYPE);
        try {
            v.post(String.format("EXP-%06d", seq), LocalDateTime.now());
        } catch (IllegalStateException e) {
            throw new ValidationException(e.getMessage());
        }
        repo.saveAndFlush(v);
        outbox.enqueue(v, VoucherPostings.post(v));
        if (v.isBill()) outbox.enqueuePayable(v);     // FP-3: the bill joins finance's payables subledger
        audit.record("EXPENSE_POSTED", "EXPENSE", v.getVoucherNo(), v.getTotal(), v.getPaidFrom(), null);
    }

    /** The voucher, if this caller may see it. Another tenant's — or, for a user, a colleague's — is "not found". */
    private ExpenseVoucher visible(Long id) {
        ExpenseVoucher v = repo.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        Long only = access.visibleUserId();
        if (only != null && !only.equals(v.getUserId())) throw new ResourceNotFoundException("Expense not found");
        return v;
    }

    private ExpenseVoucher build(Long org, VoucherRequest r) {
        if (r == null) throw new ValidationException("Nothing to save.");
        LocalDate today = TenantClock.today();
        LocalDate date = r.voucherDate() == null ? today : r.voucherDate();
        if (date.isAfter(today)) throw new ValidationException("An expense cannot be dated in the future.");
        if (date.isBefore(today.minusDays(BACKDATE_DAYS)))
            throw new ValidationException("An expense can be dated at most " + BACKDATE_DAYS + " days back.");
        PaidFrom from;
        try { from = PaidFrom.of(r.paidFrom()); }
        catch (IllegalArgumentException e) { throw new ValidationException(e.getMessage()); }
        // EX-3 — a DRAWER voucher exists only BECAUSE a till movement does; typing one here would put an expense
        // in the books with no cash leaving any drawer, and (being a till expense) it could never be voided.
        if (from == PaidFrom.DRAWER)
            throw new ValidationException("A pay-out from the till is recorded at the till (Till → Cash Drawer).");
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
        v.setStoreId(r.storeId());
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
