package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.commerce.contracts.dto.PostingLine;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;

/** EX-1 — the journal a voucher produces, and the voucher's state machine. */
class VoucherPostingsTest {

    private static ExpenseVoucherLine line(String account, String amt) {
        ExpenseVoucherLine l = new ExpenseVoucherLine();
        l.setCategoryId(1L);
        l.setAccountCode(account);
        l.setAmount(new BigDecimal(amt));
        return l;
    }

    private static ExpenseVoucher voucher(String paidFrom, ExpenseVoucherLine... lines) {
        ExpenseVoucher v = new ExpenseVoucher();
        v.setId(42L);
        v.setOrganizationId(6L);
        v.setVoucherDate(LocalDate.of(2026, 10, 2));
        v.setPaidFrom(paidFrom);
        for (ExpenseVoucherLine l : lines) v.addLine(l);
        return v;
    }

    private static Map<String, BigDecimal> net(PostingEventRequest r) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (PostingLine l : r.getLines()) {
            BigDecimal d = l.getDebit() == null ? BigDecimal.ZERO : l.getDebit();
            BigDecimal c = l.getCredit() == null ? BigDecimal.ZERO : l.getCredit();
            m.merge(l.getAccountCode(), d.subtract(c), BigDecimal::add);
        }
        return m;
    }

    @Test @DisplayName("rent in cash: Dr 6000 / Cr 1000, balanced, keyed once per voucher")
    void cash_rent() {
        ExpenseVoucher v = voucher("CASH", line("6000", "2500"));
        v.post("EXP-000001", LocalDateTime.now());
        PostingEventRequest r = VoucherPostings.post(v);
        assertThat(r.getEventType()).isEqualTo("EXPENSE");
        assertThat(r.getEventKey()).isEqualTo("EXP-6-42-POST");
        assertThat(r.getRef()).isEqualTo("EXP-000001");
        assertThat(net(r).get("6000")).isEqualByComparingTo("2500");
        assertThat(net(r).get("1000")).isEqualByComparingTo("-2500");
        assertThat(net(r).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
    }

    @Test @DisplayName("bank credits 1010; two lines on one account become one debit line")
    void bank_grouped() {
        ExpenseVoucher v = voucher("BANK", line("6200", "100.50"), line("6200", "49.50"), line("6100", "10"));
        v.post("EXP-000002", LocalDateTime.now());
        PostingEventRequest r = VoucherPostings.post(v);
        assertThat(r.getLines()).hasSize(3);   // 6200, 6100, 1010
        assertThat(net(r).get("6200")).isEqualByComparingTo("150.00");
        assertThat(net(r).get("1010")).isEqualByComparingTo("-160.00");
        assertThat(net(r)).doesNotContainKey("1000");
    }

    @Test @DisplayName("the reversal carries NO lines — finance mirrors what it posted — and is dated the void day")
    void reversal() {
        ExpenseVoucher v = voucher("CASH", line("6000", "5"));
        v.post("EXP-000003", LocalDateTime.now());
        PostingEventRequest r = VoucherPostings.reversal(v, LocalDate.of(2026, 10, 5));
        assertThat(r.getEventType()).isEqualTo("EXPENSE_REVERSAL");
        assertThat(r.getEventKey()).isEqualTo("EXP-6-42-VOID");
        assertThat(r.getLines()).isNull();
        assertThat(r.getDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test @DisplayName("⭐ the outbox payload round-trips every field — the silent-drop regression")
    void payload_round_trip() throws Exception {
        ExpenseVoucher v = voucher("BANK", line("6300", "77.25"));
        v.setPayeeName("Ali Repairs");
        v.post("EXP-000004", LocalDateTime.now());
        PostingEventRequest out = VoucherPostings.post(v);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PostingEventRequest back = json.readValue(json.writeValueAsString(out), PostingEventRequest.class);
        assertThat(back).usingRecursiveComparison().isEqualTo(out);
    }

    @Test @DisplayName("total is stamped from the lines, rounded to 2 places")
    void total_stamped() {
        ExpenseVoucher v = voucher("CASH", line("6000", "10.005"), line("6100", "0.005"));
        assertThat(v.getTotal()).isEqualByComparingTo("10.01");
    }

    @Test @DisplayName("state machine: only a draft posts; posting never claims the books")
    void post_rules() {
        ExpenseVoucher v = voucher("CASH", line("6000", "1"));
        v.post("EXP-000005", LocalDateTime.now());
        assertThat(v.getStatus()).isEqualTo("POSTED");
        assertThat(v.getPostingStatus()).as("the ledger has not answered yet").isEqualTo("PENDING");
        assertThatThrownBy(() -> v.post("EXP-000006", LocalDateTime.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> voucher("CASH").post("X", LocalDateTime.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("void: needs a reason, waits for the books, and reverses only what reached them")
    void void_rules() {
        ExpenseVoucher v = voucher("CASH", line("6000", "1"));
        assertThatThrownBy(() -> v.voidWith("x", 1L, LocalDateTime.now())).as("a draft is deleted, not voided")
                .isInstanceOf(IllegalStateException.class);
        v.post("EXP-000007", LocalDateTime.now());
        assertThatThrownBy(() -> v.voidWith(" ", 1L, LocalDateTime.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.voidWith("dup", 1L, LocalDateTime.now())).as("still posting")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("still being posted");

        v.setPostingStatus("POSTED_GL");
        v.voidWith("entered twice", 1L, LocalDateTime.now());
        assertThat(v.getStatus()).isEqualTo("VOIDED");
        assertThat(v.voidNeedsReversal()).isTrue();

        ExpenseVoucher failed = voucher("CASH", line("6000", "1"));
        failed.post("EXP-000008", LocalDateTime.now());
        failed.setPostingStatus("FAILED");
        failed.voidWith("never reached the books", 1L, LocalDateTime.now());
        assertThat(failed.voidNeedsReversal()).as("nothing to reverse").isFalse();
    }

    @Test @DisplayName("paid-from accepts cash and bank only, with an owner-readable refusal")
    void paid_from() {
        assertThat(PaidFrom.of("cash").creditAccount()).isEqualTo("1000");
        assertThat(PaidFrom.of("BANK").creditAccount()).isEqualTo("1010");
        assertThatThrownBy(() -> PaidFrom.of("CARD")).hasMessageContaining("cash or bank");
        assertThatThrownBy(() -> PaidFrom.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("EX-3 — a till pay-out credits 1000 Cash: the drawer IS the business's cash")
    void drawer_credits_cash() {
        ExpenseVoucher v = voucher("DRAWER", line("6100", "1200"));
        v.post("EXP-000009", LocalDateTime.now());
        PostingEventRequest r = VoucherPostings.post(v);
        assertThat(net(r).get("6100")).isEqualByComparingTo("1200");
        assertThat(net(r).get("1000")).isEqualByComparingTo("-1200");
    }

    @Test @DisplayName("EX-3 — a drawer expense is corrected at the till, never voided here")
    void drawer_not_voidable() {
        ExpenseVoucher v = voucher("DRAWER", line("6100", "15"));
        v.setSource(ExpenseVoucher.SOURCE_DRAWER);
        v.post("EXP-000010", LocalDateTime.now());
        v.setPostingStatus("POSTED_GL");
        assertThatThrownBy(() -> v.voidWith("try", 1L, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("till");
        // positive control: the same voucher typed on the Expenses screen IS voidable
        ExpenseVoucher manual = voucher("CASH", line("6100", "15"));
        manual.post("EXP-000011", LocalDateTime.now());
        manual.setPostingStatus("POSTED_GL");
        manual.voidWith("mistake", 1L, LocalDateTime.now());
        assertThat(manual.getStatus()).isEqualTo("VOIDED");
    }
}
