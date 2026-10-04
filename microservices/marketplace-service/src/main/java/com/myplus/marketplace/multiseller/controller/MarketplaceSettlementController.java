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
 *   GET/POST /mkt/operator/settlement/settings            {tPlusDays, useMyBooks}
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

    // ── operator ──
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
