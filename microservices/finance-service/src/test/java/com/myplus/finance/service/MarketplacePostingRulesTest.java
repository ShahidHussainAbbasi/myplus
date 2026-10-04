package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.JournalLineDTO;

/**
 * MKT-1g — the operator's marketplace journals. Each one balances through the same {@code GlService.validate} the
 * real posting path runs, and commission is the ONLY thing that reaches income (the gate's trial-balance assertion).
 */
class MarketplacePostingRulesTest {

    private static BigDecimal m(String v) { return new BigDecimal(v); }

    private static BigDecimal debit(List<JournalLineDTO> lines, String code) {
        return lines.stream().filter(l -> code.equals(l.getAccountCode()) && l.getDebit() != null)
                .map(JournalLineDTO::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal credit(List<JournalLineDTO> lines, String code) {
        return lines.stream().filter(l -> code.equals(l.getAccountCode()) && l.getCredit() != null)
                .map(JournalLineDTO::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** validate() resolves accounts by id; the codes are numeric, so the id stands in for the code. */
    private static void balances(List<JournalLineDTO> lines) {
        List<JournalLineDTO> byId = lines.stream().map(l -> JournalLineDTO.builder()
                .accountId(Long.parseLong(l.getAccountCode())).debit(l.getDebit()).credit(l.getCredit()).build()).toList();
        assertDoesNotThrow(() -> GlService.validate(byId));
    }

    @Test
    @DisplayName("paid online: the bank holds the customer's money, 4500 takes the commission, the rest is owed to the seller")
    void cardLine() {
        List<JournalLineDTO> j = MarketplacePostingRules.settlement(m("5000.00"), m("480.00"));
        balances(j);
        assertThat(debit(j, "1010")).isEqualByComparingTo("5000.00");
        assertThat(credit(j, "4500")).isEqualByComparingTo("480.00");
        assertThat(credit(j, "2400")).isEqualByComparingTo("4520.00");
    }

    @Test
    @DisplayName("cash on delivery: no cash moves on the platform; the seller owes the commission (Dr 2400)")
    void codLine() {
        List<JournalLineDTO> j = MarketplacePostingRules.settlement(BigDecimal.ZERO, m("480.00"));
        balances(j);
        assertThat(debit(j, "1010")).isZero();
        assertThat(debit(j, "2400")).isEqualByComparingTo("480.00");
        assertThat(credit(j, "4500")).isEqualByComparingTo("480.00");
    }

    @Test
    @DisplayName("a fully refunded cash line moves nothing: no journal at all")
    void nothingMoved() {
        assertThat(MarketplacePostingRules.settlement(BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("an adjustment never touches commission: ± against 4510, and it balances either way")
    void adjustments() {
        List<JournalLineDTO> down = MarketplacePostingRules.adjustment(m("-100.00"));
        balances(down);
        assertThat(debit(down, "2400")).isEqualByComparingTo("100.00");
        assertThat(credit(down, "4510")).isEqualByComparingTo("100.00");
        List<JournalLineDTO> up = MarketplacePostingRules.adjustment(m("100.00"));
        balances(up);
        assertThat(credit(up, "2400")).isEqualByComparingTo("100.00");
        assertThat(credit(up, "4500")).isZero();
        assertThatThrownBy(() -> MarketplacePostingRules.adjustment(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a payout clears the seller's balance from the bank; zero or negative is refused")
    void payout() {
        List<JournalLineDTO> j = MarketplacePostingRules.payout(m("4520.00"));
        balances(j);
        assertThat(debit(j, "2400")).isEqualByComparingTo("4520.00");
        assertThat(credit(j, "1010")).isEqualByComparingTo("4520.00");
        assertThatThrownBy(() -> MarketplacePostingRules.payout(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative inputs are refused rather than posted as the opposite side")
    void negativeRefused() {
        assertThatThrownBy(() -> MarketplacePostingRules.settlement(m("-1.00"), m("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketplacePostingRules.settlement(m("1.00"), m("-1.00"))).isInstanceOf(IllegalArgumentException.class);
    }
}
