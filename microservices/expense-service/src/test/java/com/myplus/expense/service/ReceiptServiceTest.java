package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseReceipt;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseReceiptRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-5 — receipts: what is accepted, the same file twice, the owner's rule at save, scope, and the disk adapter. */
class ReceiptServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.4 receipt".getBytes();

    private final ExpenseReceiptRepo repo = mock(ExpenseReceiptRepo.class);
    private final ReceiptStore store = mock(ReceiptStore.class);
    private final ExpenseVoucherRepo vouchers = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final ExpenseSettings settings = mock(ExpenseSettings.class);
    private ReceiptService service;

    @BeforeEach
    void setUp() {
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(3L);
        when(access.visibleUserId()).thenReturn(null);
        when(access.seesAll()).thenReturn(true);
        when(settings.receiptRequiredAbove()).thenReturn(BigDecimal.ZERO);
        when(repo.saveAndFlush(any(ExpenseReceipt.class))).thenAnswer(i -> { ExpenseReceipt r = i.getArgument(0); r.setId(50L); return r; });
        service = new ReceiptService(repo, store, vouchers, access, mock(ExpenseAuditService.class), settings);
    }

    private static ExpenseVoucher voucher(long id, String no, String amount) {
        ExpenseVoucher v = new ExpenseVoucher();
        v.setId(id);
        v.setOrganizationId(7L);
        v.setUserId(3L);
        v.setVoucherNo(no);
        v.setStatus(ExpenseVoucher.POSTED);
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setAmount(new BigDecimal(amount));
        v.addLine(l);
        return v;
    }

    private static ExpenseReceipt receipt(long id, Long voucherId, Long by) {
        ExpenseReceipt r = new ExpenseReceipt();
        r.setId(id);
        r.setOrganizationId(7L);
        r.setVoucherId(voucherId);
        r.setUploadedBy(by);
        r.setStoreKey("org/7/2026/10/x.jpg");
        r.setContentType("image/jpeg");
        r.setSizeBytes(7L);
        r.setSha256("abc");
        r.setUploadedAt(LocalDateTime.now());
        return r;
    }

    @Test
    @DisplayName("⭐ the type comes from the file's first bytes: JPEG/PNG/WEBP/PDF yes; a script renamed .jpg no")
    void sniffing() {
        assertThat(ReceiptService.sniff(JPEG)).isEqualTo("image/jpeg");
        assertThat(ReceiptService.sniff(PDF)).isEqualTo("application/pdf");
        assertThat(ReceiptService.sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})).isEqualTo("image/png");
        assertThat(ReceiptService.sniff("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(ReceiptService.sniff("<script>alert(1)</script>".getBytes())).isNull();
        assertThatThrownBy(() -> service.upload("<html>".getBytes(), "evil.jpg", null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("photo (JPEG, PNG or WEBP) or a PDF");
        verify(store, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("an empty file and one over 6 MB are refused in words")
    void sizes() {
        assertThatThrownBy(() -> service.upload(new byte[0], "x.jpg", null)).isInstanceOf(ValidationException.class);
        byte[] big = new byte[ReceiptService.MAX_BYTES + 1];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> service.upload(big, "x.jpg", null)).hasMessageContaining("too large");
    }

    @Test
    @DisplayName("⭐ kept under org/<tenant>/…, the file BEFORE the row; the display name loses any path")
    void storesUnderTheTenant() {
        var v = service.upload(JPEG, "C:\\Users\\me\\Pictures\\bill.jpg", null);
        verify(store).put(org.mockito.ArgumentMatchers.startsWith("org/7/"), eq(JPEG));
        assertThat(v.name()).isEqualTo("bill.jpg");
        assertThat(v.contentType()).isEqualTo("image/jpeg");
        assertThat(v.alsoOn()).isEmpty();
    }

    @Test
    @DisplayName("⭐ the same file already on ANOTHER expense is named (the duplicate warning); on the same, it is the same receipt")
    void duplicates() {
        ExpenseReceipt onOther = receipt(9L, 88L, 3L);
        when(repo.findByOrganizationIdAndSha256AndRemovedAtIsNull(eq(7L), anyString())).thenReturn(List.of(onOther));
        when(vouchers.findById(88L)).thenReturn(Optional.of(voucher(88L, "EXP-000088", "40")));
        var v = service.upload(JPEG, "bill.jpg", null);
        assertThat(v.alsoOn()).containsExactly("EXP-000088");

        when(vouchers.findByIdAndOrganizationId(88L, 7L)).thenReturn(Optional.of(voucher(88L, "EXP-000088", "40")));
        var again = service.upload(JPEG, "bill.jpg", 88L);
        assertThat(again.id()).isEqualTo(9L);                        // the first receipt answers; nothing new kept
        verify(store, org.mockito.Mockito.times(1)).put(anyString(), any());
    }

    @Test
    @DisplayName("⭐ EX-6 — the same file on a WAITING claim is warned about (it has no number yet); on a rejected one it is not")
    void duplicatesOnClaims() {
        ExpenseVoucher waiting = voucher(88L, null, "40");
        waiting.setPaidFrom("EMPLOYEE");
        waiting.setStatus(ExpenseVoucher.DRAFT);
        waiting.setClaimStatus(ExpenseVoucher.CLAIM_SUBMITTED);
        when(repo.findByOrganizationIdAndSha256AndRemovedAtIsNull(eq(7L), anyString())).thenReturn(List.of(receipt(9L, 88L, 3L)));
        when(vouchers.findById(88L)).thenReturn(Optional.of(waiting));
        assertThat(service.upload(JPEG, "bill.jpg", null).alsoOn()).containsExactly("a claim waiting for approval");

        waiting.setClaimStatus(ExpenseVoucher.CLAIM_REJECTED);
        assertThat(service.upload(JPEG, "bill.jpg", null).alsoOn()).isEmpty();

        ExpenseVoucher draft = voucher(88L, null, "40");            // an ordinary unposted draft: not named, as before
        draft.setStatus(ExpenseVoucher.DRAFT);
        when(vouchers.findById(88L)).thenReturn(Optional.of(draft));
        assertThat(service.upload(JPEG, "bill.jpg", null).alsoOn()).isEmpty();
    }

    @Test
    @DisplayName("EX-6 — a rejected or withdrawn claim takes no new receipts; a waiting one does")
    void decidedClaimTakesNoReceipt() {
        ExpenseVoucher c = voucher(60L, null, "18");
        c.setPaidFrom("EMPLOYEE");
        c.setStatus(ExpenseVoucher.DRAFT);
        c.setClaimStatus(ExpenseVoucher.CLAIM_REJECTED);
        when(vouchers.findByIdAndOrganizationId(60L, 7L)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.upload(JPEG, "bill.jpg", 60L)).hasMessageContaining("rejected claim takes no new receipts");
        c.setClaimStatus(ExpenseVoucher.CLAIM_WITHDRAWN);
        assertThatThrownBy(() -> service.upload(JPEG, "bill.jpg", 60L)).hasMessageContaining("withdrawn claim");
        c.setClaimStatus(ExpenseVoucher.CLAIM_SUBMITTED);
        assertThat(service.upload(JPEG, "bill.jpg", 60L).name()).isEqualTo("bill.jpg");
    }

    @Test
    @DisplayName("⭐ above the owner's amount an expense cannot be saved without a receipt; at or below it can")
    void requiredAbove() {
        when(settings.receiptRequiredAbove()).thenReturn(new BigDecimal("500"));
        assertThatThrownBy(() -> service.attachOnSave(voucher(1L, null, "500.01"), List.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("required for an expense above 500");
        service.attachOnSave(voucher(2L, null, "500"), List.of());       // not above → fine
        ExpenseReceipt mine = receipt(10L, null, 3L);
        when(repo.findByIdAndOrganizationId(10L, 7L)).thenReturn(Optional.of(mine));
        service.attachOnSave(voucher(3L, null, "900"), List.of(10L));
        assertThat(mine.getVoucherId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("attach refuses another tenant's, one already on another expense, a removed one, and (for a user) a colleague's")
    void attachRules() {
        when(repo.findByIdAndOrganizationId(11L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.attachOnSave(voucher(1L, null, "5"), List.of(11L))).hasMessageContaining("not found");
        when(repo.findByIdAndOrganizationId(12L, 7L)).thenReturn(Optional.of(receipt(12L, 99L, 3L)));
        assertThatThrownBy(() -> service.attachOnSave(voucher(1L, null, "5"), List.of(12L))).hasMessageContaining("another expense");
        ExpenseReceipt removed = receipt(13L, null, 3L);
        removed.setRemovedAt(LocalDateTime.now());
        when(repo.findByIdAndOrganizationId(13L, 7L)).thenReturn(Optional.of(removed));
        assertThatThrownBy(() -> service.attachOnSave(voucher(1L, null, "5"), List.of(13L))).hasMessageContaining("removed");
        when(access.seesAll()).thenReturn(false);
        when(repo.findByIdAndOrganizationId(14L, 7L)).thenReturn(Optional.of(receipt(14L, null, 4L)));
        assertThatThrownBy(() -> service.attachOnSave(voucher(1L, null, "5"), List.of(14L))).hasMessageContaining("not found");
    }

    @Test
    @DisplayName("⭐ a receipt is read through its expense: a colleague's (for a user) is not found; another tenant's is not found")
    void contentScope() {
        when(repo.findByIdAndOrganizationId(20L, 7L)).thenReturn(Optional.of(receipt(20L, 88L, 4L)));
        ExpenseVoucher colleagues = voucher(88L, "EXP-000088", "40");
        colleagues.setUserId(4L);
        when(vouchers.findByIdAndOrganizationId(88L, 7L)).thenReturn(Optional.of(colleagues));
        when(access.visibleUserId()).thenReturn(3L);                  // a user: own expenses only
        assertThatThrownBy(() -> service.content(20L)).isInstanceOf(ResourceNotFoundException.class);
        when(repo.findByIdAndOrganizationId(21L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.content(21L)).isInstanceOf(ResourceNotFoundException.class);
        verify(store, never()).get(anyString());
    }

    @Test
    @DisplayName("removing is owner/admin and soft: the row is marked, the file is kept")
    void remove() {
        ExpenseReceipt r = receipt(30L, null, 3L);
        when(repo.findByIdAndOrganizationId(30L, 7L)).thenReturn(Optional.of(r));
        when(access.seesAll()).thenReturn(false);
        assertThatThrownBy(() -> service.remove(30L)).isInstanceOf(AccessDeniedException.class);
        when(access.seesAll()).thenReturn(true);
        service.remove(30L);
        assertThat(r.getRemovedAt()).isNotNull();
        verify(store, never()).delete(anyString());
    }

    @Test
    @DisplayName("the disk adapter writes, reads back, and refuses a key that would leave its directory")
    void localStore(@TempDir Path dir) throws Exception {
        LocalFsReceiptStore fs = new LocalFsReceiptStore(dir.toString());
        fs.put("org/7/2026/10/a.pdf", PDF);
        assertThat(fs.get("org/7/2026/10/a.pdf")).isEqualTo(PDF);
        assertThat(Files.list(dir.resolve("org/7/2026/10"))).hasSize(1);   // no temp file left behind
        assertThatThrownBy(() -> fs.put("../../etc/x", PDF)).isInstanceOf(IllegalArgumentException.class);
        fs.delete("org/7/2026/10/a.pdf");
        assertThat(Files.exists(dir.resolve("org/7/2026/10/a.pdf"))).isFalse();
    }
}
