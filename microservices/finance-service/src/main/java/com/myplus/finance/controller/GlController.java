package com.myplus.finance.controller;

import com.myplus.finance.dto.AccountDTO;
import com.myplus.finance.dto.JournalPostRequest;
import com.myplus.finance.dto.PostEventRequest;
import com.myplus.finance.dto.TrialBalanceRow;
import com.myplus.finance.service.GlService;
import com.myplus.finance.service.PostingService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * F3 (GL): the General Ledger API. Chart of accounts + balanced journal posting + trial balance + per-account
 * ledger. All reads/writes are tenant-scoped in GlService (CurrentUser). Raw bodies for inter-service + UI callers.
 */
@RestController
@RequestMapping("/api/finance/gl")
@RequiredArgsConstructor
public class GlController {

    private final GlService glService;
    private final PostingService postingService;

    /** F3b: auto-post a SALE/PURCHASE event to the GL (posting rules applied here). Called by business-service. */
    @PostMapping("/post-event")
    public java.util.Map<String, Object> postEvent(@RequestBody PostEventRequest req) {
        postingService.postEvent(req);
        return java.util.Map.of("posted", true);
    }

    /** Ensure the tenant has the default chart of accounts (idempotent), then return it. */
    @PostMapping("/accounts/ensure-defaults")
    public List<AccountDTO> ensureDefaults() {
        return glService.ensureDefaults();
    }

    @GetMapping("/accounts")
    public List<AccountDTO> accounts() {
        return glService.listAccounts();
    }

    @PostMapping("/accounts")
    public AccountDTO addAccount(@RequestBody AccountDTO dto) {
        return glService.addAccount(dto);
    }

    /** Post a balanced journal (Σdr = Σcr enforced). Returns {entryId}. */
    @PostMapping("/journal")
    public Map<String, Object> postJournal(@RequestBody JournalPostRequest req) {
        return Map.of("entryId", glService.postJournal(req));
    }

    /** Trial balance as-of a date (default today) — {rows, totalDebit, totalCredit, balanced}. */
    @GetMapping("/trial-balance")
    public Map<String, Object> trialBalance(
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return glService.trialBalance(asOf);
    }

    /** One account's ledger detail with a running balance. */
    @GetMapping("/accounts/{id}/ledger")
    public List<Map<String, Object>> ledger(@PathVariable("id") Long accountId) {
        return glService.accountLedger(accountId);
    }

    /** F3c — Profit & Loss over a period (defaults: this month → today). */
    @GetMapping("/pnl")
    public Map<String, Object> pnl(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return glService.profitAndLoss(from, to);
    }

    /** F3c — Balance Sheet as-of a date (default today). */
    @GetMapping("/balance-sheet")
    public Map<String, Object> balanceSheet(
            @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return glService.balanceSheet(asOf);
    }
}
