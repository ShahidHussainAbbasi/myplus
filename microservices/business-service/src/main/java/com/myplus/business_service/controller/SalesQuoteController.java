package com.myplus.business_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.business_service.entity.QuoteStatus;
import com.myplus.business_service.entity.SalesQuote;
import com.myplus.business_service.service.SalesQuoteService;
import com.myplus.business_service.util.GenericResponse;

/**
 * B2B-P4b — the sales-quote API.
 *
 * <p>Thin by design: every rule (which transitions are legal, when internal approval is required, when a quote
 * has expired, how it converts) lives in {@link SalesQuoteService}. The controller resolves the request, applies
 * the trust rules that belong to THIS entry point, and translates a refusal into the wire shape.
 *
 * <p>Approving an over-threshold discount is owner/admin-gated — that gate is the entire point of the internal
 * approval step, and putting it anywhere but the endpoint would let a caller route around it. Raising and
 * sending a quote is not gated: quoting is normal counter work.
 */
@RestController
public class SalesQuoteController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SalesQuoteController.class);

    @Autowired
    private SalesQuoteService quoteService;

    @GetMapping("/getUserQuotes")
    public GenericResponse list() {
        try {
            return new GenericResponse("SUCCESS", "Quotes loaded", quoteService.list());
        } catch (Exception e) {
            LOGGER.error("getUserQuotes failed", e);
            return new GenericResponse("ERROR", "Could not load quotes.");
        }
    }

    @GetMapping("/getQuote")
    public GenericResponse get(@RequestParam Long id) {
        try {
            return new GenericResponse("SUCCESS", "Quote", quoteService.get(id));
        } catch (SalesQuoteService.QuoteRefused refused) {
            return new GenericResponse("NOT_FOUND", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("getQuote failed", e);
            return new GenericResponse("ERROR", "Could not load the quote.");
        }
    }

    /** Raise a quote. Totals are computed server-side from the lines — the caller never states a total. */
    @PostMapping(value = "/addQuote", consumes = "application/json")
    public GenericResponse create(@RequestBody SalesQuote body) {
        try {
            SalesQuote saved = quoteService.create(body);
            return new GenericResponse("SUCCESS", "Quote " + saved.getQuoteNo() + " created.", saved);
        } catch (SalesQuoteService.QuoteRefused refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("addQuote failed", e);
            return new GenericResponse("ERROR", "Could not create the quote.");
        }
    }

    /**
     * Send a quote to the customer. Refused when the discount is over the org threshold and nobody has approved
     * it — that refusal is the internal gate doing its job, and its message tells the operator what to do next.
     */
    @PostMapping("/sendQuote")
    public GenericResponse send(@RequestParam Long id) {
        return transition(id, QuoteStatus.SENT, null, "Quote sent.");
    }

    /** Route an over-threshold quote to an owner/admin for approval. */
    @PostMapping("/submitQuoteForApproval")
    public GenericResponse submitForApproval(@RequestParam Long id) {
        return transition(id, QuoteStatus.PENDING_APPROVAL, null, "Quote submitted for approval.");
    }

    /**
     * Approve an over-threshold discount and send it. Owner/admin only: this is a commercial concession, and
     * the whole reason the PENDING_APPROVAL state exists is that the person raising the quote may not clear it.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
    @PostMapping("/approveQuote")
    public GenericResponse approve(@RequestParam Long id) {
        return transition(id, QuoteStatus.SENT, null, "Quote approved and sent.");
    }

    /** Record that the CUSTOMER accepted. A fact we capture, not a permission we grant. */
    @PostMapping("/acceptQuote")
    public GenericResponse accept(@RequestParam Long id) {
        return transition(id, QuoteStatus.ACCEPTED, null, "Quote accepted.");
    }

    /** Record that the customer declined (or we withdrew it). Terminal — kept as a record of what was offered. */
    @PostMapping("/rejectQuote")
    public GenericResponse reject(@RequestParam Long id, @RequestParam(required = false) String reason) {
        return transition(id, QuoteStatus.REJECTED, reason, "Quote rejected.");
    }

    /**
     * Convert an accepted quote into an invoice, through the same sale path the till uses. The credit check
     * inside that path measures the customer's whole 4a shared pool, so a branch's conversion is capped by its
     * company's limit.
     */
    @PostMapping("/convertQuote")
    public GenericResponse convert(@RequestParam Long id) {
        try {
            SalesQuote converted = quoteService.convert(id);
            return new GenericResponse("SUCCESS",
                    "Quote converted to invoice " + converted.getConvertedInvoiceNo(), converted);
        } catch (SalesQuoteService.QuoteRefused refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (com.myplus.business_service.service.CreditConfirmationRequiredException confirm) {
            // Credit limit warn-mode: the operator must accept the overage before the invoice is written.
            return new GenericResponse("CONFIRM_REQUIRED", confirm.getMessage());
        } catch (com.myplus.common.web.exception.ValidationException blocked) {
            // Credit limit block-mode, out-of-stock, closed period — the reason is the operator's answer.
            return new GenericResponse("FAILED", blocked.getMessage());
        } catch (Exception e) {
            LOGGER.error("convertQuote failed", e);
            return new GenericResponse("ERROR", "Could not convert the quote.");
        }
    }

    private GenericResponse transition(Long id, QuoteStatus target, String reason, String okMessage) {
        try {
            return new GenericResponse("SUCCESS", okMessage, quoteService.transition(id, target, reason));
        } catch (SalesQuoteService.QuoteRefused refused) {
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("quote transition to {} failed", target, e);
            return new GenericResponse("ERROR", "Could not update the quote.");
        }
    }
}
