package com.myplus.expense.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherView;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseVoucherRepo;

import lombok.RequiredArgsConstructor;

/**
 * EX-6 — expense claims: money a member paid from their own pocket.
 *
 * <h3>A claim is an expense voucher paid from EMPLOYEE</h3>
 * It is built and checked exactly like any expense (lines, categories, what it was for, the date window, receipts and
 * "a receipt is required above X"), and stays a DRAFT while it waits. APPROVING it posts it — the number, the journal
 * (Dr the category / Cr 2300 Employee Reimbursement Payable), the outbox — through the same path every expense uses.
 * One aggregate rather than a second one (the design's first sketch): a separate claim would duplicate all of that.
 *
 * <h3>Who decides</h3>
 * Any member submits (and may withdraw while it waits). An owner or admin approves or rejects — never their own claim,
 * and never the platform operator. Rejecting needs a reason the claimant sees.
 */
@Service
@RequiredArgsConstructor
public class ExpenseClaimService {

    private final ExpenseVoucherService vouchers;
    private final ExpenseVoucherRepo repo;
    private final ExpenseAccess access;
    private final ReceiptService receipts;
    private final ExpenseAuditService audit;

    @Transactional
    public VoucherView submit(VoucherRequest r, String idempotencyKey) {
        access.assertClaimsOn();
        Long org = access.org();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key == null) throw new ValidationException("Missing Idempotency-Key header — the screen sends one per save.");
        if (key.length() > 80) throw new ValidationException("Idempotency-Key is too long.");
        Optional<ExpenseVoucher> replay = repo.findByOrganizationIdAndIdempotencyKey(org, key);
        if (replay.isPresent()) return VoucherView.of(replay.get());

        ExpenseVoucher v = vouchers.build(org, r, true);
        v.setIdempotencyKey(key);
        v.setClaimStatus(ExpenseVoucher.CLAIM_SUBMITTED);
        String who = CurrentUser.email();
        v.setClaimantName(who == null ? null : (who.length() > 160 ? who.substring(0, 160) : who));
        try {
            v = repo.saveAndFlush(v);
        } catch (DataIntegrityViolationException raced) {
            throw new ValidationException("This claim is already being saved. Refresh the list.");
        }
        receipts.attachOnSave(v, r.receiptIds());
        audit.record("CLAIM_SUBMITTED", "EXPENSE", "claim:" + v.getId(), v.getTotal(), v.getClaimantName(), null);
        return VoucherView.of(v);
    }

    /** Approve: the claim is posted (Cr 2300) and the business now owes it to the claimant. */
    @Transactional
    public VoucherView approve(Long id) {
        access.assertClaimsOn();
        if (!access.canApprove()) throw new AccessDeniedException("Only an owner or admin can approve a claim.");
        ExpenseVoucher v = waiting(id);
        if (v.getUserId() != null && v.getUserId().equals(access.userId()))
            throw new ValidationException("You cannot approve your own claim. Another owner or admin must approve it.");
        v.setClaimStatus(ExpenseVoucher.CLAIM_APPROVED);
        v.setDecidedBy(access.userId());
        v.setDecidedAt(LocalDateTime.now());
        vouchers.postInTx(v);                     // number, journal (Cr 2300), outbox — the path every expense takes
        audit.record("CLAIM_APPROVED", "EXPENSE", v.getVoucherNo(), v.getTotal(), v.getClaimantName(), null);
        return VoucherView.of(v);
    }

    @Transactional
    public VoucherView reject(Long id, String reason) {
        access.assertClaimsOn();
        if (!access.canApprove()) throw new AccessDeniedException("Only an owner or admin can reject a claim.");
        if (reason == null || reason.isBlank()) throw new ValidationException("Say why the claim is rejected — the claimant will see it.");
        ExpenseVoucher v = waiting(id);
        if (v.getUserId() != null && v.getUserId().equals(access.userId()))
            throw new ValidationException("You cannot decide your own claim. Withdraw it instead.");
        v.setClaimStatus(ExpenseVoucher.CLAIM_REJECTED);
        v.setDecidedBy(access.userId());
        v.setDecidedAt(LocalDateTime.now());
        v.setDecisionNote(reason.trim().length() > 255 ? reason.trim().substring(0, 255) : reason.trim());
        v.setUpdatedAt(LocalDateTime.now());
        audit.record("CLAIM_REJECTED", "EXPENSE", "claim:" + v.getId(), v.getTotal(), v.getClaimantName(), v.getDecisionNote());
        return VoucherView.of(repo.saveAndFlush(v));
    }

    /** The claimant takes back a claim that is still waiting (an owner/admin may too). Kept, never deleted. */
    @Transactional
    public VoucherView withdraw(Long id) {
        access.assertClaimsOn();
        ExpenseVoucher v = waiting(id);
        if (!access.seesAll() && (v.getUserId() == null || !v.getUserId().equals(access.userId())))
            throw new AccessDeniedException("Only the person who made the claim can withdraw it.");
        v.setClaimStatus(ExpenseVoucher.CLAIM_WITHDRAWN);
        v.setDecidedBy(access.userId());
        v.setDecidedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        audit.record("CLAIM_WITHDRAWN", "EXPENSE", "claim:" + v.getId(), v.getTotal(), v.getClaimantName(), null);
        return VoucherView.of(repo.saveAndFlush(v));
    }

    /** A claim of this business the caller may see, still waiting for a decision. */
    private ExpenseVoucher waiting(Long id) {
        ExpenseVoucher v = vouchers.visible(id);
        if (!v.isClaim()) throw new ValidationException("That expense is not a claim.");
        if (!ExpenseVoucher.CLAIM_SUBMITTED.equals(v.getClaimStatus()))
            throw new ValidationException("This claim was already decided (" + v.getClaimStatus().toLowerCase() + ").");
        return v;
    }
}
