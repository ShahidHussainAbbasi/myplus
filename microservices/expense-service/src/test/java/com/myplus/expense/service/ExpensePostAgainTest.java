package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseOutbox;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseOutboxRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/**
 * EX-1b — a refusal from the books is final (dead-lettered at once, in the books' own words); an outage is not; a voided
 * voucher's posting is never sent; "Post again" re-queues exactly what the voucher still owes the books.
 */
class ExpensePostAgainTest {

    private static final String CLOSED = "This period is closed (locked through 2026-10-04). Reopen it to make changes.";

    private final ExpenseOutboxRepo outboxRepo = mock(ExpenseOutboxRepo.class);
    private final ExpenseVoucherRepo vouchers = mock(ExpenseVoucherRepo.class);
    private final FinanceClient finance = mock(FinanceClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FinanceClient> financeProvider = mock(ObjectProvider.class);
    private final OutboxRelay relay = new OutboxRelay();
    private ExpenseOutboxService outbox;

    @BeforeEach
    void setUp() {
        when(financeProvider.getIfAvailable()).thenReturn(finance);
        when(financeProvider.getObject()).thenReturn(finance);
        when(outboxRepo.save(any(ExpenseOutbox.class))).thenAnswer(i -> i.getArgument(0));
        outbox = new ExpenseOutboxService(outboxRepo, vouchers, relay, mock(ApplicationEventPublisher.class), financeProvider);
        outbox.initChannel();
    }

    private static ExpenseVoucher voucher(String status, String postingStatus) {
        ExpenseVoucher v = new ExpenseVoucher();
        v.setId(5L);
        v.setOrganizationId(7L);
        v.setUserId(3L);
        v.setVoucherNo("EXP-000005");
        v.setVoucherDate(LocalDate.of(2026, 10, 4));
        v.setPaidFrom("CASH");
        v.setStatus(status);
        v.setPostingStatus(postingStatus);
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setCategoryId(1L);
        l.setAccountCode("6000");
        l.setAmount(new BigDecimal("7.00"));
        v.addLine(l);
        return v;
    }

    private ExpenseOutbox row(ExpenseVoucher v, String type) {
        ExpenseOutbox o = new ExpenseOutbox();
        o.setId(100L);
        o.setOrganizationId(7L);
        o.setUserId(3L);
        o.setVoucherId(v.getId());
        o.setEventType(type);
        o.setEventKey("EXP-7-5-POST");
        o.setPayload(new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .valueToTree(VoucherPostings.post(v)).toString());
        o.setStatus("PENDING");
        o.setAttempts(0);
        when(outboxRepo.findById(100L)).thenReturn(Optional.of(o));
        when(vouchers.findById(v.getId())).thenReturn(Optional.of(v));
        return o;
    }

    private static HttpClientErrorException refusal(int code, String message) {
        String body = "{\"success\":false,\"message\":\"" + message + "\",\"data\":null,\"statusCode\":" + code + "}";
        return HttpClientErrorException.create(HttpStatus.valueOf(code), HttpStatus.valueOf(code).getReasonPhrase(),
                new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("⭐ a closed period (400) is FAILED after ONE attempt, in the books' own words — and the voucher says so")
    void refusalIsFinalAtOnce() {
        ExpenseVoucher v = voucher(ExpenseVoucher.POSTED, ExpenseVoucher.PS_PENDING);
        ExpenseOutbox o = row(v, VoucherPostings.EXPENSE);
        doThrow(refusal(400, CLOSED)).when(finance).postEvent(any());

        relay.deliver(outbox.channel(), 100L);

        assertThat(o.getStatus()).isEqualTo("FAILED");
        assertThat(o.getAttempts()).isEqualTo(1);
        assertThat(o.getLastError()).isEqualTo(CLOSED);
        verify(vouchers).stampPosting(5L, ExpenseVoucher.PS_FAILED, CLOSED);
    }

    @Test
    @DisplayName("an outage (500) or a 403 (a secret not loaded yet) is NOT final: still PENDING, retried")
    void outageIsRetried() {
        ExpenseVoucher v = voucher(ExpenseVoucher.POSTED, ExpenseVoucher.PS_PENDING);
        ExpenseOutbox o = row(v, VoucherPostings.EXPENSE);
        doThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", new HttpHeaders(),
                new byte[0], StandardCharsets.UTF_8)).when(finance).postEvent(any());
        relay.deliver(outbox.channel(), 100L);
        assertThat(o.getStatus()).isEqualTo("PENDING");

        doThrow(refusal(403, "Access denied")).when(finance).postEvent(any());
        relay.deliver(outbox.channel(), 100L);
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ a voided voucher's posting is never sent — not by Post again, not by the operator's re-drive")
    void voidedIsNeverSent() {
        ExpenseVoucher v = voucher(ExpenseVoucher.VOIDED, ExpenseVoucher.PS_FAILED);
        ExpenseOutbox o = row(v, VoucherPostings.EXPENSE);

        relay.deliver(outbox.channel(), 100L);

        verify(finance, never()).postEvent(any());
        assertThat(o.getStatus()).isEqualTo("FAILED");
        assertThat(o.getLastError()).contains("voided before it reached the books");
    }

    @Test
    @DisplayName("a reversal that lands clears 'Void not yet in the books' (nothing used to)")
    void landedReversalClearsTheError() {
        ExpenseVoucher v = voucher(ExpenseVoucher.VOIDED, ExpenseVoucher.PS_POSTED_GL);
        row(v, VoucherPostings.EXPENSE_REVERSAL);

        relay.deliver(outbox.channel(), 100L);

        verify(vouchers).stampPosting(5L, ExpenseVoucher.PS_POSTED_GL, null);
    }

    @Test
    @DisplayName("redrive re-queues only the FAILED rows of the asked kinds, attempts back to 0")
    void redriveRequeuesTheAskedKinds() {
        ExpenseVoucher v = voucher(ExpenseVoucher.POSTED, ExpenseVoucher.PS_FAILED);
        ExpenseOutbox posting = row(v, VoucherPostings.EXPENSE);
        posting.setStatus("FAILED"); posting.setAttempts(1); posting.setLastError(CLOSED);
        ExpenseOutbox reversal = new ExpenseOutbox();
        reversal.setEventType(VoucherPostings.EXPENSE_REVERSAL); reversal.setStatus("FAILED");
        when(outboxRepo.findByVoucherIdAndStatus(5L, "FAILED")).thenReturn(List.of(posting, reversal));

        int n = outbox.redrive(5L, Set.of(VoucherPostings.EXPENSE, VoucherPostings.PAYABLE));

        assertThat(n).isEqualTo(1);
        assertThat(posting.getStatus()).isEqualTo("PENDING");
        assertThat(posting.getAttempts()).isZero();
        assertThat(posting.getLastError()).isNull();
        assertThat(reversal.getStatus()).isEqualTo("FAILED");
    }

    // ── ExpenseVoucherService.postAgain ────────────────────────────────────────────────────────────────

    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseOutboxService outboxMock = mock(ExpenseOutboxService.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);

    private ExpenseVoucherService service() {
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(3L);
        when(access.visibleUserId()).thenReturn(null);   // owner/admin: sees everyone's (Mockito would answer 0)
        when(repo.saveAndFlush(any(ExpenseVoucher.class))).thenAnswer(i -> i.getArgument(0));
        return new ExpenseVoucherService(repo, mock(ExpenseCategoryService.class), outboxMock, mock(ExpenseAuditService.class),
                access, mock(DocumentNumberService.class), mock(ExpenseTagService.class), mock(ExpenseBillPaymentRepo.class));
    }

    @Test
    @DisplayName("⭐ post again on a refused expense: its posting is re-queued, the row says Posting… again, the reason clears")
    @SuppressWarnings("unchecked")
    void postAgainRequeuesThePosting() {
        ExpenseVoucherService s = service();
        ExpenseVoucher v = voucher(ExpenseVoucher.POSTED, ExpenseVoucher.PS_FAILED);
        v.setPostingError(CLOSED);
        when(repo.findByIdAndOrganizationId(5L, 7L)).thenReturn(Optional.of(v));
        ArgumentCaptor<Set<String>> kinds = ArgumentCaptor.forClass(Set.class);
        when(outboxMock.redrive(eq(5L), kinds.capture())).thenReturn(1);

        var view = s.postAgain(5L);

        assertThat(kinds.getValue()).containsExactlyInAnyOrder(VoucherPostings.EXPENSE, VoucherPostings.PAYABLE);
        assertThat(view.postingStatus()).isEqualTo(ExpenseVoucher.PS_PENDING);
        assertThat(view.postingError()).isNull();
    }

    @Test
    @DisplayName("on a voided expense only the reversal (and a bill's snapshot) may go again — never the posting")
    @SuppressWarnings("unchecked")
    void postAgainOnAVoidSendsOnlyTheReversal() {
        ExpenseVoucherService s = service();
        ExpenseVoucher v = voucher(ExpenseVoucher.VOIDED, ExpenseVoucher.PS_POSTED_GL);
        when(repo.findByIdAndOrganizationId(5L, 7L)).thenReturn(Optional.of(v));
        ArgumentCaptor<Set<String>> kinds = ArgumentCaptor.forClass(Set.class);
        when(outboxMock.redrive(eq(5L), kinds.capture())).thenReturn(1);

        s.postAgain(5L);

        assertThat(kinds.getValue()).containsExactlyInAnyOrder(VoucherPostings.EXPENSE_REVERSAL, VoucherPostings.PAYABLE)
                .doesNotContain(VoucherPostings.EXPENSE);
    }

    @Test
    @DisplayName("nothing waiting → refused, and nothing is saved")
    void nothingWaiting() {
        ExpenseVoucherService s = service();
        when(repo.findByIdAndOrganizationId(5L, 7L)).thenReturn(Optional.of(voucher(ExpenseVoucher.POSTED, ExpenseVoucher.PS_POSTED_GL)));
        when(outboxMock.redrive(anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> s.postAgain(5L)).isInstanceOf(ValidationException.class).hasMessageContaining("Nothing is waiting");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a colleague's expense (for a user) or another business's is not found — and nothing is re-queued")
    void scoped() {
        ExpenseVoucherService s = service();
        ExpenseVoucher theirs = voucher(ExpenseVoucher.POSTED, ExpenseVoucher.PS_FAILED);
        theirs.setUserId(99L);
        when(access.visibleUserId()).thenReturn(3L);
        when(repo.findByIdAndOrganizationId(5L, 7L)).thenReturn(Optional.of(theirs));
        when(repo.findByIdAndOrganizationId(6L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> s.postAgain(5L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> s.postAgain(6L)).isInstanceOf(ResourceNotFoundException.class);
        verify(outboxMock, never()).redrive(anyLong(), any());
    }
}
