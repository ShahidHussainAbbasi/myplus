package com.myplus.marketplace.multiseller.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.common.web.PageResponse;
import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.dto.SupportDTOs;
import com.myplus.marketplace.multiseller.service.MarketplaceCustomerService;
import com.myplus.marketplace.multiseller.service.MarketplacePolicyService;
import com.myplus.marketplace.multiseller.service.MarketplaceSettingsService;
import com.myplus.marketplace.multiseller.service.MarketplaceSupportService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1f — support cases and returns. Rules live in {@link MarketplaceSupportService}.
 *
 * <pre>
 *   customer (X-Mkt-Session, set by the monolith from the HttpOnly cookie)
 *   POST /public/mkt/account/orders/{no}/cases        {topic, note, lineId?, quantity?, reason?}
 *   GET  /public/mkt/account/cases
 *   GET  /public/mkt/account/cases/{caseNo}
 *   POST /public/mkt/account/cases/{caseNo}/messages  {body}
 *   operator (ROLE_ADMIN)
 *   GET  /mkt/operator/cases?status=&amp;page=&amp;size=        urgent first, then oldest
 *   GET  /mkt/operator/cases/{caseNo}                  the whole thread, internal notes marked
 *   POST /mkt/operator/cases/reply | task | resolve
 *   POST /mkt/operator/returns/decision                {returnNo, decision: APPROVED|REJECTED, note}
 *   GET/POST /mkt/operator/settings/change-of-mind-fee {amount}
 *   POST /mkt/operator/policies/return-days            {policyId, returnDays}
 *   seller (own org)
 *   GET  /mkt/seller/tasks
 *   POST /mkt/seller/tasks/reply                       {caseNo, body}
 *   POST /mkt/seller/returns/received                  {returnNo, outcome, cashHandedBack}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceSupportController {

    static final String SESSION = MarketplaceAccountController.SESSION;

    private final MarketplaceSupportService support;
    private final MarketplaceCustomerService customers;
    private final MarketplaceSettingsService settings;
    private final MarketplacePolicyService policies;

    // ── customer ──
    @PostMapping("/public/mkt/account/orders/{orderNo}/cases")
    public ApiResponse<SupportDTOs.CaseView> open(@RequestHeader(value = SESSION, required = false) String token,
            @PathVariable String orderNo, @RequestBody(required = false) SupportDTOs.OpenCaseRequest body) {
        return ApiResponse.success(support.open(customers.authenticate(token), orderNo, body), MarketplaceSupportService.LOOKING_INTO_IT);
    }

    @GetMapping("/public/mkt/account/cases")
    public ApiResponse<List<SupportDTOs.CaseView>> myCases(@RequestHeader(value = SESSION, required = false) String token) {
        return ApiResponse.success(support.myCases(customers.authenticate(token)));
    }

    @GetMapping("/public/mkt/account/cases/{caseNo}")
    public ApiResponse<SupportDTOs.CaseView> myCase(@RequestHeader(value = SESSION, required = false) String token,
            @PathVariable String caseNo) {
        return ApiResponse.success(support.myCase(customers.authenticate(token), caseNo));
    }

    @PostMapping("/public/mkt/account/cases/{caseNo}/messages")
    public ApiResponse<SupportDTOs.CaseView> message(@RequestHeader(value = SESSION, required = false) String token,
            @PathVariable String caseNo, @RequestBody(required = false) SupportDTOs.MessageRequest body) {
        return ApiResponse.success(support.customerMessage(customers.authenticate(token), caseNo, body == null ? null : body.body()),
                "Sent to MaxTheService support.");
    }

    // ── operator ──
    @GetMapping("/mkt/operator/cases")
    public ApiResponse<PageResponse<SupportDTOs.CaseRow>> queue(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.success(support.queue(status, page, size));
    }

    @GetMapping("/mkt/operator/cases/{caseNo}")
    public ApiResponse<SupportDTOs.CaseView> operatorView(@PathVariable String caseNo) {
        return ApiResponse.success(support.operatorView(caseNo));
    }

    @PostMapping("/mkt/operator/cases/reply")
    public ApiResponse<SupportDTOs.CaseView> reply(@RequestBody SupportDTOs.ReplyRequest body) {
        SupportDTOs.CaseView v = support.reply(body);
        return ApiResponse.success(v, Boolean.TRUE.equals(body.internal()) ? "Internal note saved." : "Reply sent to the customer.");
    }

    @PostMapping("/mkt/operator/cases/task")
    public ApiResponse<SupportDTOs.CaseView> task(@RequestBody SupportDTOs.TaskRequest body) {
        return ApiResponse.success(support.task(body), "The seller has been tasked.");
    }

    @PostMapping("/mkt/operator/cases/resolve")
    public ApiResponse<SupportDTOs.CaseView> resolve(@RequestBody SupportDTOs.ResolveRequest body) {
        return ApiResponse.success(support.resolve(body), "Case resolved.");
    }

    @PostMapping("/mkt/operator/returns/decision")
    public ApiResponse<SupportDTOs.ReturnView> decide(@RequestBody SupportDTOs.DecisionRequest body) {
        SupportDTOs.ReturnView v = support.decide(body);
        return ApiResponse.success(v, "APPROVED".equals(v.status()) ? "Approved. The seller's rider collects it." : "Return " + v.status().toLowerCase() + ".");
    }

    @GetMapping("/mkt/operator/settings/change-of-mind-fee")
    public ApiResponse<Map<String, BigDecimal>> fee() {
        return ApiResponse.success(Map.of("amount", settings.changeOfMindFee()));
    }

    @PostMapping("/mkt/operator/settings/change-of-mind-fee")
    public ApiResponse<Map<String, BigDecimal>> setFee(@RequestBody(required = false) SupportDTOs.FeeRequest body) {
        return ApiResponse.success(Map.of("amount", settings.setChangeOfMindFee(body == null ? null : body.amount())),
                "Change-of-mind fee saved.");
    }

    @PostMapping("/mkt/operator/policies/return-days")
    public ApiResponse<OfferDTOs.Policy> returnDays(@RequestBody(required = false) SupportDTOs.ReturnDaysRequest body) {
        return ApiResponse.success(policies.setReturnDays(body == null ? null : body.policyId(), body == null ? null : body.returnDays()),
                "Saved. Orders already placed keep the days they were placed under.");
    }

    // ── seller ──
    @GetMapping("/mkt/seller/tasks")
    public ApiResponse<List<SupportDTOs.SellerTask>> tasks() {
        return ApiResponse.success(support.tasks());
    }

    @PostMapping("/mkt/seller/tasks/reply")
    public ApiResponse<SupportDTOs.SellerTask> taskReply(@RequestBody SupportDTOs.TaskReplyRequest body) {
        return ApiResponse.success(support.taskReply(body), "Sent to MaxTheService support.");
    }

    @PostMapping("/mkt/seller/returns/received")
    public ApiResponse<SupportDTOs.ReturnView> received(@RequestBody SupportDTOs.ReceivedRequest body) {
        SupportDTOs.ReturnView v = support.received(body);
        return ApiResponse.success(v, "REFUNDED".equals(v.status()) ? "Received. The customer's refund is done."
                : "Received. The credit note is raised; the card refund is being processed.");
    }
}
