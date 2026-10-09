package com.myplus.marketplace.multiseller.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceSettlementService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1g — settlement statement, ledger, payouts. Rules live in {@link MarketplaceSettlementService}.
 *
 * <pre>
 *   seller (own org from the token)
 *   GET  /mkt/settlement/statement?status=&amp;page=&amp;size=   lines with the whole split
 *   GET  /mkt/settlement/account                          balance, ledger rows, payouts
 *   operator (ROLE_ADMIN)
 *   GET  /mkt/operator/settlement/accounts                every seller with a ledger, and its balance
 *   GET  /mkt/operator/settlement/accounts/{org}          one seller's account
 *   POST /mkt/operator/settlement/run                     settle what is due now
 *   POST /mkt/operator/settlement/adjust                  {organizationId, amount (signed), reason, idempotencyKey}
 *   GET/POST /mkt/operator/settlement/settings            {tPlusDays, useMyBooks, codRemitDays, codStopWhenOverdue}
 *   GET  /mkt/operator/settlement/cod                     MKT-2d: cash orders, what each seller owes and since when
 *   POST /mkt/operator/settlement/remittance              MKT-2d: {organizationId, amount, reference, note, idempotencyKey}
 *   GET  /mkt/operator/payouts?status=
 *   POST /mkt/operator/payouts                            {organizationId, idempotencyKey}
 *   POST /mkt/operator/payouts/{id}/approve               a different operator from the requester
 *   POST /mkt/operator/payouts/{id}/mark-paid             {bankReference}
 * </pre>
 *
 * <p>There is deliberately no PUT or DELETE on a ledger row: the ledger has no edit path (R15.6).
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceSettlementController {

    private final MarketplaceSettlementService settlement;
    private final com.myplus.marketplace.multiseller.service.SettlementReportService reports;
    private final com.myplus.marketplace.multiseller.service.SettlementCalendarService calendar;

    // ── seller ──
    @GetMapping("/mkt/settlement/statement")
    public ApiResponse<PageResponse<SettlementDTOs.StatementLine>> statement(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(settlement.statement(status, page, size));
    }

    @GetMapping("/mkt/settlement/account")
    public ApiResponse<SettlementDTOs.AccountView> account() {
        return ApiResponse.success(settlement.myAccount());
    }

    /** MKT-2f — the seller's own settlement report for a period (default: this month to today). */
    @GetMapping("/mkt/settlement/report")
    public ApiResponse<SettlementDTOs.SettlementReport> myReport(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.success(reports.mine(from, to));
    }

    // ── operator ──
    /** MKT-2f — every seller's settlement report for a period, and the totals. */
    @GetMapping("/mkt/operator/settlement/report")
    public ApiResponse<SettlementDTOs.SettlementReport> report(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.success(reports.operator(from, to));
    }

    /** MKT-2f — bank holidays the settlement calendar skips. */
    @GetMapping("/mkt/operator/settlement/holidays")
    public ApiResponse<List<SettlementDTOs.HolidayView>> holidays() {
        return ApiResponse.success(calendar.list());
    }

    @PostMapping("/mkt/operator/settlement/holidays")
    public ApiResponse<List<SettlementDTOs.HolidayView>> addHoliday(@RequestBody(required = false) SettlementDTOs.HolidayRequest body) {
        return ApiResponse.success(calendar.add(body), "Holiday added. Lines due that day are paid on the next business day.");
    }

    @PostMapping("/mkt/operator/settlement/holidays/remove")
    public ApiResponse<List<SettlementDTOs.HolidayView>> removeHoliday(@RequestBody(required = false) SettlementDTOs.HolidayRequest body) {
        return ApiResponse.success(calendar.remove(body), "Holiday removed.");
    }

    @GetMapping("/mkt/operator/settlement/accounts")
    public ApiResponse<List<SettlementDTOs.AccountRow>> accounts() {
        return ApiResponse.success(settlement.accounts());
    }

    @GetMapping("/mkt/operator/settlement/accounts/{org}")
    public ApiResponse<SettlementDTOs.AccountView> sellerAccount(@PathVariable Long org) {
        return ApiResponse.success(settlement.sellerAccount(org));
    }

    @PostMapping("/mkt/operator/settlement/run")
    public ApiResponse<SettlementDTOs.RunResult> run() {
        SettlementDTOs.RunResult r = settlement.runNow();
        return ApiResponse.success(r, r.settled() == 1 ? "1 line settled." : r.settled() + " lines settled.");
    }

    @PostMapping("/mkt/operator/settlement/adjust")
    public ApiResponse<SettlementDTOs.AccountView> adjust(@RequestBody(required = false) SettlementDTOs.AdjustmentRequest body) {
        return ApiResponse.success(settlement.adjust(body), "Correction recorded as a new ledger line.");
    }

    @GetMapping("/mkt/operator/settlement/cod")
    public ApiResponse<List<SettlementDTOs.CodRow>> codReconciliation() {
        return ApiResponse.success(settlement.codReconciliation());
    }

    @PostMapping("/mkt/operator/settlement/remittance")
    public ApiResponse<SettlementDTOs.AccountView> recordRemittance(@RequestBody SettlementDTOs.RemittanceRequest body) {
        return ApiResponse.success(settlement.recordRemittance(body), "Payment recorded on the seller's statement.");
    }

    @GetMapping("/mkt/operator/settlement/settings")
    public ApiResponse<SettlementDTOs.SettingsView> settings() {
        return ApiResponse.success(settlement.settingsView());
    }

    @PostMapping("/mkt/operator/settlement/settings")
    public ApiResponse<SettlementDTOs.SettingsView> saveSettings(@RequestBody(required = false) SettlementDTOs.SettingsRequest body) {
        return ApiResponse.success(settlement.saveSettings(body), "Settlement settings saved.");
    }

    @GetMapping("/mkt/operator/payouts")
    public ApiResponse<PageResponse<SettlementDTOs.PayoutView>> payouts(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(settlement.payoutQueue(status, page, size));
    }

    @PostMapping("/mkt/operator/payouts")
    public ApiResponse<SettlementDTOs.PayoutView> requestPayout(@RequestBody(required = false) SettlementDTOs.PayoutRequest body) {
        SettlementDTOs.PayoutView v = settlement.requestPayout(body);
        return ApiResponse.success(v, "Payout " + v.payoutNo() + " requested. Another operator must approve it.");
    }

    @PostMapping("/mkt/operator/payouts/{id}/approve")
    public ApiResponse<SettlementDTOs.PayoutView> approve(@PathVariable Long id) {
        SettlementDTOs.PayoutView v = settlement.approvePayout(id);
        return ApiResponse.success(v, "Payout " + v.payoutNo() + " approved.");
    }

    @PostMapping("/mkt/operator/payouts/{id}/mark-paid")
    public ApiResponse<SettlementDTOs.PayoutView> markPaid(@PathVariable Long id,
            @RequestBody(required = false) SettlementDTOs.PayoutDecision body) {
        SettlementDTOs.PayoutView v = settlement.markPaid(new SettlementDTOs.PayoutDecision(id,
                body == null ? null : body.bankReference()));
        return ApiResponse.success(v, "Payout " + v.payoutNo() + " paid.");
    }
}
