package com.myplus.finance.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.entity.AccountType;

/**
 * EX-0b — the rules an {@code EXPENSE} event must satisfy before it becomes a journal. Pure (no Spring, no
 * repository), so every rule is a plain unit test. Design: microservices/docs/slices/ex-0b-finance-expense-events.md.
 *
 * <h3>Why finance owns these, not expense-service</h3>
 * finance-service is the only writer of journals, and this is where the <b>purchase/expense boundary</b> is
 * enforced: an "expense" that debits {@code 1200 Inventory} is a purchase booked through the wrong door — stock
 * the books would hold that no stock count ever finds. A caller cannot be trusted to have checked it; the
 * ledger checks it, the same way it checks that a journal balances.
 *
 * <h3>The two sides</h3>
 * <ul>
 *   <li><b>Debit</b>: an account of type {@code EXPENSE}, except {@code 5000 Cost of Goods Sold}, which only the
 *       sale path may move — an expense posted there would silently lower every reported margin.</li>
 *   <li><b>Credit</b>: where the money came from or is owed — {@code 1000 Cash}, {@code 1010 Bank},
 *       {@code 2000 Accounts Payable} (an expense bill), {@code 2300 Employee Reimbursement Payable} (a claim),
 *       {@code 1300 Employee Advance} (a claim settled against an advance).</li>
 * </ul>
 */
public final class ExpensePostingRules {

    /** Cost of Goods Sold — moved by the sale path only. */
    static final String COGS = "5000";

    /** Every account an expense may be paid from or owed on. */
    static final Set<String> CREDIT_ACCOUNTS = Set.of("1000", "1010", "2000", "2300", "1300");

    private ExpensePostingRules() { }

    /**
     * Throws {@link IllegalArgumentException} with an owner-readable message when the lines break a rule.
     * Balance is NOT checked here — {@code GlService.validate} owns that and runs on every journal.
     *
     * @param typeOf the tenant's account type by code; empty when the tenant has no such account
     */
    public static void check(List<JournalLineDTO> lines, Function<String, Optional<AccountType>> typeOf) {
        if (lines == null || lines.size() < 2)
            throw new IllegalArgumentException("An expense needs at least one cost line and one payment line.");
        boolean anyDebit = false, anyCredit = false;
        for (JournalLineDTO l : lines) {
            String code = l.getAccountCode();
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("Each expense line needs an account code.");
            BigDecimal d = nz(l.getDebit()), c = nz(l.getCredit());
            if (d.signum() > 0) {
                anyDebit = true;
                if (COGS.equals(code))
                    throw new IllegalArgumentException(
                            "An expense cannot be posted to 5000 Cost of Goods Sold — only sales move that account.");
                AccountType t = typeOf.apply(code).orElseThrow(
                        () -> new IllegalArgumentException("Account code not found: " + code));
                if (t != AccountType.EXPENSE)
                    throw new IllegalArgumentException("Account " + code + " is not an expense account (" + t
                            + "). Goods for stock are a purchase, not an expense.");
            } else if (c.signum() > 0) {
                anyCredit = true;
                if (!CREDIT_ACCOUNTS.contains(code))
                    throw new IllegalArgumentException("An expense can only be paid from cash, bank, a supplier "
                            + "bill, an employee claim or an advance — not account " + code + ".");
            }
        }
        if (!anyDebit) throw new IllegalArgumentException("An expense needs at least one cost line.");
        if (!anyCredit) throw new IllegalArgumentException("An expense needs a line saying how it was paid.");
    }

    /**
     * The exact mirror of a posted journal: every debit becomes a credit of the same amount on the same account,
     * and vice versa. Used for EXPENSE_REVERSAL, built from what the ledger POSTED — never from lines a caller
     * sends — so a void can never differ from the expense it undoes.
     */
    public static List<JournalLineDTO> mirror(List<JournalLineDTO> posted, String memo) {
        List<JournalLineDTO> out = new ArrayList<>();
        for (JournalLineDTO l : posted) {
            BigDecimal d = nz(l.getDebit()), c = nz(l.getCredit());
            out.add(JournalLineDTO.builder()
                    .accountId(l.getAccountId()).accountCode(l.getAccountCode())
                    .debit(c.signum() > 0 ? c : null)
                    .credit(d.signum() > 0 ? d : null)
                    .lineMemo(memo).build());
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
