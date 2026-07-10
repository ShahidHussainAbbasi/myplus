package com.web.controller.business;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import com.web.util.FinanceRestClient;

/**
 * F3 (GL): proxy the General Ledger reads/writes to finance-service. Returns the raw JSON (GL reads include arrays
 * as well as objects), so responses are produced as application/json strings.
 */
@Controller
public class GlController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private FinanceRestClient finance;

    /** Ensure the tenant's default chart of accounts exists, return it. */
    @PostMapping(value = "/gl/ensureDefaults", produces = "application/json")
    @ResponseBody
    public String ensureDefaults() {
        try { return finance.postJson("/gl/accounts/ensure-defaults", java.util.Map.of()); }
        catch (Exception e) { LOGGER.error("gl ensureDefaults proxy error", e); return "{\"status\":\"ERROR\"}"; }
    }

    @GetMapping(value = "/gl/accounts", produces = "application/json")
    @ResponseBody
    public String accounts() {
        try { return finance.get("/gl/accounts"); }
        catch (Exception e) { LOGGER.error("gl accounts proxy error", e); return "{\"status\":\"ERROR\"}"; }
    }

    /** Post a balanced journal (JSON body passed straight through). */
    @PostMapping(value = "/gl/journal", produces = "application/json")
    @ResponseBody
    public String journal(@RequestBody Object body) {
        try { return finance.postJson("/gl/journal", body); }
        catch (Exception e) { LOGGER.error("gl journal proxy error", e); return "{\"status\":\"ERROR\"}"; }
    }

    @GetMapping(value = "/gl/trialBalance", produces = "application/json")
    @ResponseBody
    public String trialBalance(final HttpServletRequest request) {
        try {
            String asOf = request.getParameter("asOf");
            return finance.get("/gl/trial-balance", asOf != null && !asOf.isEmpty() ? "asOf=" + asOf : null);
        } catch (Exception e) { LOGGER.error("gl trialBalance proxy error", e); return "{\"status\":\"ERROR\"}"; }
    }

    @GetMapping(value = "/gl/accountLedger", produces = "application/json")
    @ResponseBody
    public String accountLedger(final HttpServletRequest request) {
        try {
            String id = request.getParameter("accountId");
            return finance.get("/gl/accounts/" + (id == null ? "" : id) + "/ledger");
        } catch (Exception e) { LOGGER.error("gl accountLedger proxy error", e); return "{\"status\":\"ERROR\"}"; }
    }
}
