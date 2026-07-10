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
@RequiredArgsConstructor
public class GlService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    /** The seeded default chart of accounts: {code, name, type, normalSide}. */
    private static final Object[][] DEFAULT_COA = {
            {"1000", "Cash", AccountType.ASSET, NormalSide.DEBIT},
            {"1010", "Bank", AccountType.ASSET, NormalSide.DEBIT},
            {"1100", "Accounts Receivable", AccountType.ASSET, NormalSide.DEBIT},
            {"1200", "Inventory", AccountType.ASSET, NormalSide.DEBIT},
            {"2000", "Accounts Payable", AccountType.LIABILITY, NormalSide.CREDIT},
            {"2100", "Tax Payable", AccountType.LIABILITY, NormalSide.CREDIT},
            {"3000", "Owner's Equity", AccountType.EQUITY, NormalSide.CREDIT},
            {"3100", "Retained Earnings", AccountType.EQUITY, NormalSide.CREDIT},
            {"4000", "Sales", AccountType.INCOME, NormalSide.CREDIT},
            {"5000", "Cost of Goods Sold", AccountType.EXPENSE, NormalSide.DEBIT},
            {"5100", "Purchases / Expenses", AccountType.EXPENSE, NormalSide.DEBIT},
    };

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    // ---- Chart of accounts ------------------------------------------------------------------------------------

    /** Create the default chart of accounts for the org if it has none, then return the full list. Idempotent. */
    @Transactional
    public List<AccountDTO> ensureDefaults() {
        Long org = CurrentUser.organizationId();
        if (accountRepository.countByOrganizationId(org) == 0) {
            for (Object[] a : DEFAULT_COA) {
                accountRepository.save(Account.builder()
                        .code((String) a[0]).name((String) a[1])
                        .type((AccountType) a[2]).normalSide((NormalSide) a[3])
                        .organizationId(org).build());
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

    private AccountDTO toDTO(Account a) {
        return AccountDTO.builder().id(a.getId()).code(a.getCode()).name(a.getName())
                .type(a.getType().name()).normalSide(a.getNormalSide().name()).build();
    }
}
