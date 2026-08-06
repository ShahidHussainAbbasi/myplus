package com.web.controller.business;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

/**
 * B2B-P4b — thin proxy for the sales-quote API in business-service.
 *
 * <p>Pure transport: no rules live here. The quote lifecycle (legal transitions, the internal approval
 * threshold, derived expiry, conversion through the one sale path) is business-service's, and duplicating any
 * of it in the monolith would mean two implementations that drift.
 *
 * <p>Every response is relayed as-is so the screen sees business-service's own wording — a refusal like "this
 * quote expired on 2026-09-01" is the operator's answer and must not be flattened into a generic error.
 */
@Controller
public class SalesQuoteController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SalesQuoteController.class);

    @Autowired
    private BusinessRestClient business;

    @GetMapping("/getUserQuotes")
    @ResponseBody
    public Map<String, Object> list() {
        try {
            return business.get("/getUserQuotes");
        } catch (Exception e) {
            LOGGER.error("getUserQuotes proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @GetMapping("/getQuote")
    @ResponseBody
    public Map<String, Object> get(final HttpServletRequest request) {
        try {
            return business.get("/getQuote", "id=" + nz(request.getParameter("id")));
        } catch (Exception e) {
            LOGGER.error("getQuote proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** Raise a quote. JSON body (lines + customer + PO); business-service computes every total. */
    @PostMapping("/addQuote")
    @ResponseBody
    public Map<String, Object> create(@RequestBody final Map<String, Object> body) {
        try {
            return business.postJson("/addQuote", body);
        } catch (Exception e) {
            LOGGER.error("addQuote proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @PostMapping("/sendQuote")
    @ResponseBody
    public Map<String, Object> send(final HttpServletRequest request) {
        return relay("/sendQuote", request);
    }

    @PostMapping("/submitQuoteForApproval")
    @ResponseBody
    public Map<String, Object> submitForApproval(final HttpServletRequest request) {
        return relay("/submitQuoteForApproval", request);
    }

    /** Owner/admin-gated in business-service — the gate stays there, with the rule it protects. */
    @PostMapping("/approveQuote")
    @ResponseBody
    public Map<String, Object> approve(final HttpServletRequest request) {
        return relay("/approveQuote", request);
    }

    @PostMapping("/acceptQuote")
    @ResponseBody
    public Map<String, Object> accept(final HttpServletRequest request) {
        return relay("/acceptQuote", request);
    }

    @PostMapping("/rejectQuote")
    @ResponseBody
    public Map<String, Object> reject(final HttpServletRequest request) {
        return relay("/rejectQuote", request);
    }

    /** Convert to an invoice. May come back CONFIRM_REQUIRED when the group credit limit is breached (4a). */
    @PostMapping("/convertQuote")
    @ResponseBody
    public Map<String, Object> convert(final HttpServletRequest request) {
        return relay("/convertQuote", request);
    }

    private Map<String, Object> relay(String path, HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return business.postForm(path, params);
        } catch (Exception e) {
            LOGGER.error("{} proxy error", path, e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
