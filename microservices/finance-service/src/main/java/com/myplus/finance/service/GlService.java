package com.myplus.finance.service;

import com.myplus.common.security.CurrentUser;
import com.myplus.finance.dto.AccountDTO;
import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.dto.JournalPostRequest;
import com.myplus.finance.dto.TrialBalanceRow;
import com.myplus.finance.entity.*;
import com.myplus.finance.repository.AccountRepository;
import com.myplus.finance.repository.JournalEntryRepository;
import com.myplus.finance.repository.JournalLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * F3 (GL): the double-entry engine. Seeds a default chart of accounts per org, posts BALANCED journals
 * (Σdebit = Σcredit, ≥2 lines, immutable once POSTED) and answers the trial balance + per-account ledger. The
 * balance rule lives in the pure {@link #validate} (unit-testable, no Spring). Tenant-scoped via CurrentUser.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GlService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final PeriodLockService periodLockService;   // period close: reject posting into a closed period

    /** The seeded default chart of accounts: {code, name, type, normalSide}. */
    private static final Object[][] DEFAULT_COA = {
            {"1000", "Cash", AccountType.ASSET, NormalSide.DEBIT},
            {"1010", "Bank", AccountType.ASSET, NormalSide.DEBIT},
            {"1100", "Accounts Receivable", AccountType.ASSET, NormalSide.DEBIT},
            {"1200", "Inventory", AccountType.ASSET, NormalSide.DEBIT},
            {"2000", "Accounts Payable", AccountType.LIABILITY, NormalSide.CREDIT},
            {"2100", "Tax Payable", AccountType.LIABILITY, NormalSide.CREDIT},
            {"2200", "Store Credit", AccountType.LIABILITY, NormalSide.CREDIT},
            {"3000", "Owner's Equity", AccountType.EQUITY, NormalSide.CREDIT},
            {"3100", "Retained Earnings", AccountType.EQUITY, NormalSide.CREDIT},
            {"4000", "Sales", AccountType.INCOME, NormalSide.CREDIT},
            // Slice 0.1: school fee revenue gets its own income line rather than being merged into Sales — a
            // school's P&L should read "Fee Income". ensureDefaults() backfills this for orgs whose chart was
            // seeded earlier (same path 2200 Store Credit took).
            {"4100", "Fee Income", AccountType.INCOME, NormalSide.CREDIT},
            // B2B-P4b / D-4: contra-revenue. INCOME by type so it sits with Sales in the P&L, but its normal
            // side is DEBIT — a discount REDUCES income while keeping gross Sales at the invoices' face value.
            // ensureDefaults() backfills it into charts seeded earlier, the same way 2200 Store Credit arrived.
            {"4200", "Sales Discount", AccountType.INCOME, NormalSide.DEBIT},
            // Delivery charged to the customer gets its own income line rather than being merged into Sales:
            // a shop that cannot separate delivery income from goods income cannot tell whether its delivery
            // operation pays for itself. Backfilled by ensureDefaults() the same way 2200 and 4200 arrived.
            {"4300", "Delivery Income", AccountType.INCOME, NormalSide.CREDIT},
            {"5000", "Cost of Goods Sold", AccountType.EXPENSE, NormalSide.DEBIT},
            {"5100", "Purchases / Expenses", AccountType.EXPENSE, NormalSide.DEBIT},
    };

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    // ---- Chart of accounts ------------------------------------------------------------------------------------

    /** Ensure EACH default account exists for the org, then return the full list. Idempotent PER account (not
     *  all-or-nothing) so an account newly added to {@link #DEFAULT_COA} — e.g. 2200 Store Credit — backfills orgs
     *  whose chart was seeded before it existed. Without this, a new posting rule hits "Account code not found". */
    @Transactional
    public List<AccountDTO> ensureDefaults() {
        Long org = CurrentUser.organizationId();
        for (Object[] a : DEFAULT_COA) {
            String code = (String) a[0];
            if (accountRepository.findByOrganizationIdAndCode(org, code).isEmpty()) {
                try {
                    // Each account is its own transaction-visible insert, so a lost race costs one account,
                    // not the whole chart.
                    accountRepository.saveAndFlush(Account.builder()
                            .code(code).name((String) a[1])
                            .type((AccountType) a[2]).normalSide((NormalSide) a[3])
                            .organizationId(org).build());
                } catch (DataIntegrityViolationException dup) {
                    // LOST THE RACE, and that is a success, not an error.
                    //
                    // check-then-insert is not atomic: two concurrent postings for a tenant with no chart yet
                    // both saw isEmpty() and both inserted. Nothing rejected the second copy, because the table
                    // only had a NON-unique index — and from that moment every findByOrganizationIdAndCode for
                    // the code threw NonUniqueResultException, so GL posting and several reports 500'd for that
                    // tenant permanently. Seen in dev as organization 14 with all 14 codes duplicated and its
                    // fee postings reading 0.00 everywhere.
                    //
                    // V5 adds UNIQUE (organization_id, code), which turns that silent corruption into this
                    // catchable violation. Swallowing it is correct: the row we wanted now exists, put there by
                    // whoever won. Re-reading is what makes ensureDefaults genuinely idempotent under
                    // concurrency rather than merely on a quiet system.
                    log.debug("Account {} for org {} was created concurrently — using the existing row", code, org);
                }
            }
        }
        return listAccounts();
    }

    @Transactional(readOnly = true)
    public List<AccountDTO> listAccounts() {
        List<AccountDTO> out = new ArrayList<>();
        for (Account a : accountRepository.findByOrganizationIdOrderByCodeAsc(CurrentUser.organizationId()))
            out.add(toDTO(a));
        return out;
    }

    @Transactional
    public AccountDTO addAccount(AccountDTO dto) {
        Account a = Account.builder()
                .code(dto.getCode()).name(dto.getName())
                .type(AccountType.valueOf(dto.getType())).normalSide(NormalSide.valueOf(dto.getNormalSide()))
                .organizationId(CurrentUser.organizationId()).build();
        return toDTO(accountRepository.save(a));
    }

    // ---- Posting ----------------------------------------------------------------------------------------------

    /** PURE double-entry rule (unit-testable): ≥2 lines, each line a debit XOR a credit, no negatives, non-zero
     *  total, and Σdebit = Σcredit. Throws with a clear message otherwise. */
    public static void validate(List<JournalLineDTO> lines) {
        if (lines == null || lines.size() < 2) throw new IllegalArgumentException("A journal needs at least 2 lines.");
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (JournalLineDTO l : lines) {
            BigDecimal d = nz(l.getDebit()), c = nz(l.getCredit());
            if (d.signum() < 0 || c.signum() < 0) throw new IllegalArgumentException("Debit/credit cannot be negative.");
            if (d.signum() > 0 && c.signum() > 0) throw new IllegalArgumentException("A line is a debit OR a credit, not both.");
            dr = dr.add(d); cr = cr.add(c);
        }
        if (dr.signum() == 0) throw new IllegalArgumentException("A journal must move a non-zero amount.");
        if (dr.compareTo(cr) != 0) throw new IllegalArgumentException("Unbalanced journal: debits " + dr + " != credits " + cr + ".");
    }

    /** Post a balanced journal; returns the entry id. Immutable once saved (POSTED). */
    @Transactional
    public Long postJournal(JournalPostRequest req) {
        Long org = CurrentUser.organizationId();
        periodLockService.assertOpen(req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now());   // period close guard
        validate(req.getLines());   // pure rule first — nothing persists on an invalid journal
        JournalEntry e = JournalEntry.builder()
                .entryDate(req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now())
                .source(req.getSource() != null ? req.getSource() : "MANUAL")
                .sourceRef(req.getSourceRef()).memo(req.getMemo())
                .status("POSTED").organizationId(org).userId(CurrentUser.userId())
                .createdAt(LocalDateTime.now()).lines(new ArrayList<>()).build();
        for (JournalLineDTO l : req.getLines()) {
            Long accountId = resolveAccount(l, org);
            e.addLine(JournalLine.builder().accountId(accountId)
                    .debit(nz(l.getDebit())).credit(nz(l.getCredit())).lineMemo(l.getLineMemo()).build());
        }
        return journalEntryRepository.save(e).getId();
    }

    private Long resolveAccount(JournalLineDTO l, Long org) {
        if (l.getAccountId() != null)
            return accountRepository.findByIdAndOrganizationId(l.getAccountId(), org)
                    .map(Account::getId).orElseThrow(() -> new IllegalArgumentException("Account not found: " + l.getAccountId()));
        if (l.getAccountCode() != null)
            return accountRepository.findByOrganizationIdAndCode(org, l.getAccountCode())
                    .map(Account::getId).orElseThrow(() -> new IllegalArgumentException("Account code not found: " + l.getAccountCode()));
        throw new IllegalArgumentException("Each line needs an accountId or accountCode.");
    }

    // ---- Reports ----------------------------------------------------------------------------------------------

    /** Trial balance as-of a date: each account netted to its balance side; total debits must equal total credits. */
    @Transactional(readOnly = true)
    public Map<String, Object> trialBalance(LocalDate asOf) {
        Long org = CurrentUser.organizationId();
        LocalDate d = asOf != null ? asOf : LocalDate.now();
        Map<Long, Account> byId = new HashMap<>();
        for (Account a : accountRepository.findByOrganizationIdOrderByCodeAsc(org)) byId.put(a.getId(), a);

        List<TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal totDr = BigDecimal.ZERO, totCr = BigDecimal.ZERO;
        for (Object[] r : journalLineRepository.trialBalance(org, d)) {
            Long accId = (Long) r[0];
            BigDecimal net = nz((BigDecimal) r[1]).subtract(nz((BigDecimal) r[2]));   // Σdebit − Σcredit
            BigDecimal debit = net.signum() >= 0 ? net : BigDecimal.ZERO;
            BigDecimal credit = net.signum() < 0 ? net.negate() : BigDecimal.ZERO;
            if (debit.signum() == 0 && credit.signum() == 0) continue;   // settled account → omit
            Account a = byId.get(accId);
            totDr = totDr.add(debit); totCr = totCr.add(credit);
            rows.add(TrialBalanceRow.builder().accountId(accId)
                    .code(a != null ? a.getCode() : null).name(a != null ? a.getName() : null)
                    .type(a != null ? a.getType().name() : null).debit(debit).credit(credit).build());
        }
        rows.sort(Comparator.comparing(TrialBalanceRow::getCode, Comparator.nullsLast(String::compareTo)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totalDebit", totDr);
        out.put("totalCredit", totCr);
        out.put("balanced", totDr.compareTo(totCr) == 0);   // the GL's self-check
        return out;
    }

    /** Per-account ledger detail (oldest first) with a running balance in the account's normal direction. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> accountLedger(Long accountId) {
        Long org = CurrentUser.organizationId();
        Account a = accountRepository.findByIdAndOrganizationId(accountId, org).orElse(null);
        boolean debitNormal = a == null || a.getNormalSide() == NormalSide.DEBIT;
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal bal = BigDecimal.ZERO;
        for (JournalLine jl : journalLineRepository.ledgerForAccount(accountId, org)) {
            BigDecimal signed = nz(jl.getDebit()).subtract(nz(jl.getCredit()));   // +debit, −credit
            bal = bal.add(debitNormal ? signed : signed.negate());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", jl.getEntry() != null ? jl.getEntry().getEntryDate() : null);
            row.put("source", jl.getEntry() != null ? jl.getEntry().getSource() : null);
            row.put("ref", jl.getEntry() != null ? jl.getEntry().getSourceRef() : null);
            row.put("debit", nz(jl.getDebit()));
            row.put("credit", nz(jl.getCredit()));
            row.put("balance", bal);
            out.add(row);
        }
        return out;
    }

    /**
     * Tax-filing register over [from, to] from the TAX account (2100). Grouped by the journal source so output tax
     * (sales, Cr) nets against returns/voids (Dr), and — when input tax is captured on purchases (Dr) — input nets
     * against purchase returns (Cr). netPayable = netOutput − netInput. Accrual-accurate + audit-defensible (the
     * TAX account is the single source; input columns are simply 0 until a tenant enables input tax).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> taxRegister(LocalDate from, LocalDate to) {
        Long org = CurrentUser.organizationId();
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f);
        out.put("to", t);

        Account tax = accountRepository.findByOrganizationIdAndCode(org, "2100").orElse(null);
        BigDecimal outputTax = BigDecimal.ZERO, outputAdjusted = BigDecimal.ZERO,
                inputTax = BigDecimal.ZERO, inputAdjusted = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        if (tax != null) {
            for (Object[] r : journalLineRepository.sumByAccountSourceInRange(org, tax.getId(), f, t)) {
                String source = (String) r[0];
                BigDecimal debit = nz((BigDecimal) r[1]), credit = nz((BigDecimal) r[2]);
                if ("SALE".equals(source)) outputTax = outputTax.add(credit);
                else if ("SALE_RETURN".equals(source)) outputAdjusted = outputAdjusted.add(debit);
                else if ("PURCHASE".equals(source)) inputTax = inputTax.add(debit);
                else if ("PURCHASE_RETURN".equals(source)) inputAdjusted = inputAdjusted.add(credit);
            }
            for (JournalLine jl : journalLineRepository.ledgerForAccountInRange(tax.getId(), org, f, t)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", jl.getEntry().getEntryDate());
                row.put("source", jl.getEntry().getSource());
                row.put("ref", jl.getEntry().getSourceRef());
                row.put("debit", nz(jl.getDebit()));
                row.put("credit", nz(jl.getCredit()));
                lines.add(row);
            }
        }
        BigDecimal netOutput = outputTax.subtract(outputAdjusted);
        BigDecimal netInput = inputTax.subtract(inputAdjusted);
        out.put("outputTax", outputTax);
        out.put("outputAdjusted", outputAdjusted);
        out.put("netOutput", netOutput);
        out.put("inputTax", inputTax);
        out.put("inputAdjusted", inputAdjusted);
        out.put("netInput", netInput);
        out.put("netPayable", netOutput.subtract(netInput));
        out.put("lines", lines);
        return out;
    }

    /** F3c — Profit & Loss over [from, to]: income (credit − debit) − expense (debit − credit) = net profit. */
    @Transactional(readOnly = true)
    public Map<String, Object> profitAndLoss(LocalDate from, LocalDate to) {
        Long org = CurrentUser.organizationId();
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to != null ? to : LocalDate.now();
        Map<Long, Account> byId = accountsById(org);
        List<Map<String, Object>> income = new ArrayList<>(), expense = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO, totalExpense = BigDecimal.ZERO;
        for (Object[] r : journalLineRepository.sumByAccountInRange(org, f, t)) {
            Account a = byId.get((Long) r[0]);
            if (a == null) continue;
            BigDecimal debit = nz((BigDecimal) r[1]), credit = nz((BigDecimal) r[2]);
            if (a.getType() == AccountType.INCOME) {
                BigDecimal amt = credit.subtract(debit);
                if (amt.signum() != 0) { income.add(line(a, amt)); totalIncome = totalIncome.add(amt); }
            } else if (a.getType() == AccountType.EXPENSE) {
                BigDecimal amt = debit.subtract(credit);
                if (amt.signum() != 0) { expense.add(line(a, amt)); totalExpense = totalExpense.add(amt); }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f); out.put("to", t);
        out.put("income", income); out.put("expense", expense);
        out.put("totalIncome", totalIncome); out.put("totalExpense", totalExpense);
        out.put("netProfit", totalIncome.subtract(totalExpense));
        return out;
    }

    /** F3c — Balance Sheet as-of a date: assets = liabilities + equity (+ current net income, until it's closed). */
    @Transactional(readOnly = true)
    public Map<String, Object> balanceSheet(LocalDate asOf) {
        Long org = CurrentUser.organizationId();
        LocalDate d = asOf != null ? asOf : LocalDate.now();
        Map<Long, Account> byId = accountsById(org);
        List<Map<String, Object>> assets = new ArrayList<>(), liabilities = new ArrayList<>(), equity = new ArrayList<>();
        BigDecimal totalAssets = BigDecimal.ZERO, totalLiab = BigDecimal.ZERO, totalEquity = BigDecimal.ZERO, netIncome = BigDecimal.ZERO;
        for (Object[] r : journalLineRepository.trialBalance(org, d)) {
            Account a = byId.get((Long) r[0]);
            if (a == null) continue;
            BigDecimal net = nz((BigDecimal) r[1]).subtract(nz((BigDecimal) r[2]));   // debit − credit
            switch (a.getType()) {
                case ASSET -> { if (net.signum() != 0) { assets.add(line(a, net)); totalAssets = totalAssets.add(net); } }
                case LIABILITY -> { BigDecimal b = net.negate(); if (b.signum() != 0) { liabilities.add(line(a, b)); totalLiab = totalLiab.add(b); } }
                case EQUITY -> { BigDecimal b = net.negate(); if (b.signum() != 0) { equity.add(line(a, b)); totalEquity = totalEquity.add(b); } }
                case INCOME, EXPENSE -> netIncome = netIncome.add(net.negate());   // roll into retained earnings
            }
        }
        BigDecimal equityWithIncome = totalEquity.add(netIncome);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", d);
        out.put("assets", assets); out.put("liabilities", liabilities); out.put("equity", equity);
        out.put("netIncome", netIncome);              // current-period net income (not yet closed to equity)
        out.put("totalAssets", totalAssets);
        out.put("totalLiabilities", totalLiab);
        out.put("totalEquity", equityWithIncome);     // includes retained/current net income
        out.put("balanced", totalAssets.compareTo(totalLiab.add(equityWithIncome)) == 0);   // the accounting equation
        return out;
    }

    private Map<Long, Account> accountsById(Long org) {
        Map<Long, Account> byId = new HashMap<>();
        for (Account a : accountRepository.findByOrganizationIdOrderByCodeAsc(org)) byId.put(a.getId(), a);
        return byId;
    }

    private Map<String, Object> line(Account a, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.getCode()); m.put("name", a.getName()); m.put("type", a.getType().name()); m.put("amount", amount);
        return m;
    }

    private AccountDTO toDTO(Account a) {
        return AccountDTO.builder().id(a.getId()).code(a.getCode()).name(a.getName())
                .type(a.getType().name()).normalSide(a.getNormalSide().name()).build();
    }
}
