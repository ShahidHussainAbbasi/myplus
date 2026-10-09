package com.myplus.expense.service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseReceipt;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.repository.ExpenseReceiptRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

import lombok.RequiredArgsConstructor;

/**
 * EX-5 — receipts: the evidence behind an expense.
 *
 * <h3>Upload first, attach on save</h3>
 * The screen uploads the photo or PDF, gets a receipt id, and saves the expense with it; the save attaches it in the
 * same transaction — so "a receipt is required above X" is enforced where the money is recorded, not after. A receipt
 * can also be added to an expense already saved (a bill's invoice that arrives later).
 *
 * <h3>What is accepted</h3>
 * JPEG, PNG, WEBP or PDF, recognised by their first bytes (never by the name or the browser's word), at most
 * {@value #MAX_BYTES} bytes — the screen shrinks photos well below that before sending. The same file twice is the
 * same receipt (sha256): uploading it again answers with the first, and a receipt already on ANOTHER expense is named,
 * so the screen can warn before the same bill is claimed twice.
 *
 * <h3>Scope</h3>
 * A receipt is read through its expense: another tenant's, or (for a user) a colleague's, is "not found". Viewing is
 * audited. Removing is owner/admin and soft — the file is kept.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptService.class);
    static final int MAX_BYTES = 6 * 1024 * 1024;

    private final ExpenseReceiptRepo receipts;
    private final ReceiptStore store;
    private final ExpenseVoucherRepo vouchers;
    private final ExpenseAccess access;
    private final ExpenseAuditService audit;
    private final ExpenseSettings settings;

    public record ReceiptView(Long id, Long voucherId, String name, String contentType, long size, LocalDateTime uploadedAt,
                              List<String> alsoOn) {
        static ReceiptView of(ExpenseReceipt r, List<String> alsoOn) {
            return new ReceiptView(r.getId(), r.getVoucherId(), r.getOriginalName(), r.getContentType(),
                    r.getSizeBytes() == null ? 0 : r.getSizeBytes(), r.getUploadedAt(), alsoOn);
        }
    }

    public record Content(byte[] bytes, String contentType, String name) { }

    /** Upload a receipt — for an expense being saved (voucherId null) or for one already saved. */
    @Transactional
    public ReceiptView upload(byte[] bytes, String originalName, Long voucherId) {
        access.assertModuleOn();
        Long org = access.org();
        if (bytes == null || bytes.length == 0) throw new ValidationException("Choose a photo or PDF of the receipt.");
        if (bytes.length > MAX_BYTES) throw new ValidationException("That file is too large (at most 6 MB). A photo is made smaller automatically on the screen.");
        String type = sniff(bytes);
        if (type == null) throw new ValidationException("A receipt must be a photo (JPEG, PNG or WEBP) or a PDF.");
        ExpenseVoucher v = voucherId == null ? null : writable(voucherId);
        String sha = sha256(bytes);

        List<ExpenseReceipt> same = receipts.findByOrganizationIdAndSha256AndRemovedAtIsNull(org, sha);
        // the same file again, for the same expense (or, unattached, by the same person): it is the same receipt
        for (ExpenseReceipt r : same) {
            boolean sameTarget = v != null ? v.getId().equals(r.getVoucherId())
                    : r.getVoucherId() == null && access.userId() != null && access.userId().equals(r.getUploadedBy());
            if (sameTarget) return ReceiptView.of(r, alsoOn(same, r.getVoucherId()));
        }

        ExpenseReceipt r = new ExpenseReceipt();
        r.setOrganizationId(org);
        r.setVoucherId(v == null ? null : v.getId());
        r.setContentType(type);
        r.setSizeBytes((long) bytes.length);
        r.setSha256(sha);
        r.setOriginalName(cleanName(originalName));
        r.setUploadedBy(access.userId());
        r.setUploadedAt(LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        r.setStoreKey(String.format("org/%d/%d/%02d/%s.%s", org, now.getYear(), now.getMonthValue(), UUID.randomUUID(), ext(type)));
        store.put(r.getStoreKey(), bytes);          // the file first: a row never points at nothing
        r = receipts.saveAndFlush(r);
        if (v != null) audit.record("RECEIPT_ADDED", "EXPENSE", v.getVoucherNo(), null, r.getOriginalName(), null);
        return ReceiptView.of(r, alsoOn(same, r.getVoucherId()));
    }

    /**
     * Called by the save, inside its transaction: attach the uploaded receipts and enforce the owner's rule. Only the
     * caller's own unattached uploads of this tenant can be attached.
     */
    @Transactional
    public void attachOnSave(ExpenseVoucher v, Collection<Long> receiptIds) {
        List<Long> ids = receiptIds == null ? List.of() : receiptIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        BigDecimal above = settings.receiptRequiredAbove();
        if (ids.isEmpty() && above.signum() > 0 && v.getTotal() != null && v.getTotal().compareTo(above) > 0)
            throw new ValidationException("A receipt is required for an expense above " + above.toPlainString()
                    + ". Attach a photo or PDF of the receipt.");
        for (Long id : ids) {
            ExpenseReceipt r = receipts.findByIdAndOrganizationId(id, v.getOrganizationId())
                    .orElseThrow(() -> new ValidationException("That receipt was not found. Attach it again."));
            if (r.getRemovedAt() != null) throw new ValidationException("That receipt was removed. Attach it again.");
            if (r.getVoucherId() != null && !r.getVoucherId().equals(v.getId()))
                throw new ValidationException("That receipt is already on another expense.");
            if (r.getUploadedBy() != null && !r.getUploadedBy().equals(access.userId()) && !access.seesAll())
                throw new ValidationException("That receipt was not found. Attach it again.");
            r.setVoucherId(v.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<ReceiptView> list(Long voucherId) {
        ExpenseVoucher v = visible(voucherId);
        return receipts.findByVoucherIdAndRemovedAtIsNullOrderByIdAsc(v.getId()).stream()
                .map(r -> ReceiptView.of(r, alsoOn(receipts.findByOrganizationIdAndSha256AndRemovedAtIsNull(r.getOrganizationId(), r.getSha256()), r.getVoucherId())))
                .toList();
    }

    /** The file, for someone who may see its expense (or its uploader, before the save). Audited — so NOT read-only. */
    @Transactional
    public Content content(Long receiptId) {
        ExpenseReceipt r = receipts.findByIdAndOrganizationId(receiptId, access.org())
                .filter(x -> x.getRemovedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
        String ref;
        if (r.getVoucherId() != null) ref = visible(r.getVoucherId()).getVoucherNo();
        else if (access.userId() != null && access.userId().equals(r.getUploadedBy())) ref = "(not yet saved)";
        else throw new ResourceNotFoundException("Receipt not found");
        audit.record("RECEIPT_VIEWED", "EXPENSE", ref, null, r.getOriginalName(), null);
        return new Content(store.get(r.getStoreKey()), r.getContentType(), r.getOriginalName());
    }

    /** Owner/admin: take a receipt off its expense. The file is kept (evidence); the trail says who and when. */
    @Transactional
    public void remove(Long receiptId) {
        if (!access.seesAll()) throw new AccessDeniedException("Only an owner or admin can remove a receipt.");
        ExpenseReceipt r = receipts.findByIdAndOrganizationId(receiptId, access.org())
                .filter(x -> x.getRemovedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
        String ref = r.getVoucherId() == null ? null : visible(r.getVoucherId()).getVoucherNo();
        r.setRemovedAt(LocalDateTime.now());
        r.setRemovedBy(access.userId());
        audit.record("RECEIPT_REMOVED", "EXPENSE", ref, null, r.getOriginalName(), null);
    }

    /** How many receipts each expense on a page has (one query). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> counts(Collection<Long> voucherIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (voucherIds == null || voucherIds.isEmpty()) return out;
        for (Object[] row : receipts.countByVoucherIds(voucherIds)) out.put((Long) row[0], ((Number) row[1]).intValue());
        return out;
    }

    /** Hourly: uploads never attached to a saved expense within a day were never evidence — file and row go. */
    @Scheduled(fixedDelayString = "${expense.receipts.sweep-ms:3600000}", initialDelay = 300000)
    public void sweepUnattached() {
        for (ExpenseReceipt r : receipts.findTop100ByVoucherIdIsNullAndUploadedAtBefore(LocalDateTime.now().minusDays(1))) {
            try {
                store.delete(r.getStoreKey());
                receipts.delete(r);
            } catch (Exception e) {
                LOG.warn("unattached receipt {} could not be swept yet", r.getId(), e);
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** The expense, if this caller may see it (another tenant's, or a colleague's for a user, is "not found"). */
    private ExpenseVoucher visible(Long id) {
        ExpenseVoucher v = vouchers.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        Long only = access.visibleUserId();
        if (only != null && !only.equals(v.getUserId())) throw new ResourceNotFoundException("Expense not found");
        return v;
    }

    private ExpenseVoucher writable(Long id) {
        ExpenseVoucher v = visible(id);
        if (ExpenseVoucher.VOIDED.equals(v.getStatus())) throw new ValidationException("A voided expense takes no new receipts.");
        // EX-6 — a claim that was turned down or taken back is finished: it never reaches the books
        if (v.isClaim() && !ExpenseVoucher.CLAIM_SUBMITTED.equals(v.getClaimStatus()) && !ExpenseVoucher.CLAIM_APPROVED.equals(v.getClaimStatus()))
            throw new ValidationException("A " + v.getClaimStatus().toLowerCase() + " claim takes no new receipts.");
        return v;
    }

    /**
     * The other expenses this same file is already on, by number. EX-6 — a claim still waiting has no number yet; it is
     * named as such (it WILL reach the books if approved, so the same bill claimed and recorded is a real duplicate).
     * A voided expense, and a rejected or withdrawn claim, never reached the books and is not counted.
     */
    static final String WAITING_CLAIM = "a claim waiting for approval";

    private List<String> alsoOn(List<ExpenseReceipt> same, Long exceptVoucher) {
        List<String> out = new ArrayList<>();
        for (ExpenseReceipt r : same) {
            if (r.getVoucherId() == null || r.getVoucherId().equals(exceptVoucher)) continue;
            vouchers.findById(r.getVoucherId())
                    .filter(x -> !ExpenseVoucher.VOIDED.equals(x.getStatus()))
                    .filter(x -> !x.isClaim() || ExpenseVoucher.CLAIM_SUBMITTED.equals(x.getClaimStatus())
                            || ExpenseVoucher.CLAIM_APPROVED.equals(x.getClaimStatus()))
                    .map(x -> x.getVoucherNo() != null ? x.getVoucherNo() : (x.isClaim() ? WAITING_CLAIM : null))
                    .filter(no -> no != null && !out.contains(no))           // an ordinary draft, as before: not named
                    .ifPresent(out::add);
        }
        return out;
    }

    /** The file's type from its first bytes; null if it is not one we keep. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        return null;
    }

    static String ext(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "pdf";
        };
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A display name only: path parts and control characters dropped, length capped. */
    static String cleanName(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        return n.isEmpty() ? null : (n.length() > 160 ? n.substring(0, 160) : n);
    }
}
