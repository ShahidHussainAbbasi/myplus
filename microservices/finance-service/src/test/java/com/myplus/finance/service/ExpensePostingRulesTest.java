package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.entity.AccountType;

/**
 * EX-0b — what an EXPENSE event may and may not post. Each refusal names the real-world mistake it stops.
 */
class ExpensePostingRulesTest {

    /** A tenant chart: the defaults that matter here. */
    private static final Map<String, AccountType> CHART = Map.of(
            "1000", AccountType.ASSET, "1010", AccountType.ASSET, "1200", AccountType.ASSET,
            "2000", AccountType.LIABILITY, "2300", AccountType.LIABILITY, "1300", AccountType.ASSET,
            "4000", AccountType.INCOME, "5000", AccountType.EXPENSE, "6000", AccountType.EXPENSE,
            "6200", AccountType.EXPENSE);
    private static final Function<String, Optional<AccountType>> TYPE = c -> Optional.ofNullable(CHART.get(c));

    private static JournalLineDTO dr(String code, String amt) {
        return JournalLineDTO.builder().accountCode(code).debit(new BigDecimal(amt)).build();
    }
    private static JournalLineDTO cr(String code, String amt) {
        return JournalLineDTO.builder().accountCode(code).credit(new BigDecimal(amt)).build();
    }

    @Test @DisplayName("rent paid in cash posts: Dr 6000 / Cr 1000")
    void cash_expense_is_allowed() {
        assertThatCode(() -> ExpensePostingRules.check(List.of(dr("6000", "25000"), cr("1000", "25000")), TYPE))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("one voucher, two categories, paid from bank")
    void split_voucher_is_allowed() {
        assertThatCode(() -> ExpensePostingRules.check(
                List.of(dr("6000", "100"), dr("6200", "50"), cr("1010", "150")), TYPE)).doesNotThrowAnyException();
    }

    @Test @DisplayName("every allowed paying account is accepted: cash, bank, AP, reimbursement, advance")
    void every_allowed_credit() {
        for (String c : List.of("1000", "1010", "2000", "2300", "1300")) {
            assertThatCode(() -> ExpensePostingRules.check(List.of(dr("6000", "1"), cr(c, "1")), TYPE))
                    .as(c).doesNotThrowAnyException();
        }
    }

    @Test @DisplayName("⭐ THE BOUNDARY — goods for stock (Dr 1200 Inventory) are refused: that is a purchase")
    void inventory_is_refused() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("1200", "18000"), cr("1000", "18000")), TYPE))
                .hasMessageContaining("not an expense account").hasMessageContaining("purchase");
    }

    @Test @DisplayName("COGS is refused — only sales move it, or every margin quietly falls")
    void cogs_is_refused() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("5000", "10"), cr("1000", "10")), TYPE))
                .hasMessageContaining("5000");
    }

    @Test @DisplayName("an income account cannot be debited as an expense")
    void income_is_refused() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("4000", "10"), cr("1000", "10")), TYPE))
                .hasMessageContaining("not an expense account");
    }

    @Test @DisplayName("paying from anywhere but the allowed set is refused (e.g. crediting Sales)")
    void odd_credit_is_refused() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("6000", "10"), cr("4000", "10")), TYPE))
                .hasMessageContaining("4000");
    }

    @Test @DisplayName("an account the tenant does not have is refused by name")
    void unknown_account() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("6999", "10"), cr("1000", "10")), TYPE))
                .hasMessageContaining("6999");
    }

    @Test @DisplayName("a cost line and a payment line are both required")
    void both_sides_required() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("6000", "10"), dr("6200", "10")), TYPE))
                .hasMessageContaining("how it was paid");
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(cr("1000", "10"), cr("1010", "10")), TYPE))
                .hasMessageContaining("cost line");
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr("6000", "10")), TYPE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpensePostingRules.check(null, TYPE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("a line with no account code is refused")
    void code_required() {
        assertThatThrownBy(() -> ExpensePostingRules.check(List.of(dr(null, "10"), cr("1000", "10")), TYPE))
                .hasMessageContaining("account code");
    }

    // ── the reversal ─────────────────────────────────────────────────────────────────────────────

    private static Map<String, BigDecimal> net(List<JournalLineDTO> lines) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (JournalLineDTO l : lines) {
            BigDecimal d = l.getDebit() == null ? BigDecimal.ZERO : l.getDebit();
            BigDecimal c = l.getCredit() == null ? BigDecimal.ZERO : l.getCredit();
            m.merge(l.getAccountCode(), d.subtract(c), BigDecimal::add);
        }
        return m;
    }

    @Test @DisplayName("⭐ expense + its void net EVERY account to zero, and the void still balances")
    void mirror_nets_to_zero() {
        List<JournalLineDTO> posted = List.of(dr("6000", "100.00"), dr("6200", "50.00"), cr("1010", "150.00"));
        List<JournalLineDTO> voided = ExpensePostingRules.mirror(posted, "Void EXP-000001");

        assertThatCode(() -> GlService.validate(voided)).as("the void is a valid journal on its own")
                .doesNotThrowAnyException();
        Map<String, BigDecimal> m = net(voided);
        assertThat(m.get("6000")).isEqualByComparingTo("-100.00");   // the expense is credited back
        assertThat(m.get("1010")).isEqualByComparingTo("150.00");    // the bank is debited back

        List<JournalLineDTO> both = new java.util.ArrayList<>(posted);
        both.addAll(voided);
        net(both).forEach((acct, v) -> assertThat(v.signum()).as("account %s nets to zero", acct).isZero());
    }

    @Test @DisplayName("the mirror keeps the account id, so it reverses the account actually posted to")
    void mirror_keeps_account_id() {
        JournalLineDTO posted = JournalLineDTO.builder().accountId(42L).debit(new BigDecimal("7")).build();
        JournalLineDTO back = ExpensePostingRules.mirror(List.of(posted), "Void").get(0);
        assertThat(back.getAccountId()).isEqualTo(42L);
        assertThat(back.getCredit()).isEqualByComparingTo("7");
        assertThat(back.getDebit()).isNull();
    }
}
