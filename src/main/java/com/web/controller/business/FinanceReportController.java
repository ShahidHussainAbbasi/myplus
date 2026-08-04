package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

/** F2: proxy the AR/AP aging + statement reads to business-service (which composes docs + the finance ledger). */
@Controller
public class FinanceReportController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/customerAging", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> customerAging(final HttpServletRequest request) {
        try { return client.get("/customerAging"); }
        catch (Exception e) { LOGGER.error("customerAging proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/vendorAging", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> vendorAging(final HttpServletRequest request) {
        try { return client.get("/vendorAging"); }
        catch (Exception e) { LOGGER.error("vendorAging proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/customerStatement", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> customerStatement(final HttpServletRequest request) {
        try {
            String id = request.getParameter("customerId");
            return client.get("/customerStatement", "customerId=" + (id == null ? "" : id));
        } catch (Exception e) { LOGGER.error("customerStatement proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    /**
     * B2B-P3d (#5): stream the customer statement CSV straight through from business-service.
     *
     * <p>Proxied as RAW TEXT, not JSON: the browser must receive the file with its Content-Disposition so it
     * saves rather than renders. Authorisation, tenant scope and the anti-IDOR customer check all stay in
     * business-service — this route adds no rules of its own, which is exactly why it cannot weaken them.
     */
    @RequestMapping(value = "/customerStatement.csv", method = RequestMethod.GET)
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> customerStatementCsv(final HttpServletRequest request) {
        return csv(request.getParameter("customerId"), "/customerStatement.csv", "customerId",
                "customer-statement");
    }

    /** B2B-P3d (#5): the vendor statement CSV — same passthrough, same guarantees. */
    @RequestMapping(value = "/vendorStatement.csv", method = RequestMethod.GET)
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> vendorStatementCsv(final HttpServletRequest request) {
        return csv(request.getParameter("venderId"), "/vendorStatement.csv", "venderId", "vendor-statement");
    }

    /** Shared passthrough: fetch the CSV text and hand it back as a download. */
    private org.springframework.http.ResponseEntity<String> csv(String id, String path, String param,
                                                                String filePrefix) {
        try {
            String body = client.getString(path, param + "=" + (id == null ? "" : id));
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + filePrefix + "-" + (id == null ? "" : id) + ".csv\"")
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .body(body);
        } catch (Exception e) {
            LOGGER.error(path + " proxy error", e);
            return org.springframework.http.ResponseEntity.status(502).body("Could not build the statement.");
        }
    }

    @RequestMapping(value = "/vendorStatement", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> vendorStatement(final HttpServletRequest request) {
        try {
            String id = request.getParameter("venderId");
            return client.get("/vendorStatement", "venderId=" + (id == null ? "" : id));
        } catch (Exception e) { LOGGER.error("vendorStatement proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    /** Audit #4: read the GL posting outbox (delivery status) for the tenant. */
    @RequestMapping(value = "/getGlOutbox", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getGlOutbox(final HttpServletRequest request) {
        try { return client.get("/getGlOutbox"); }
        catch (Exception e) { LOGGER.error("getGlOutbox proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    /** Multi-rate tax: per-rate taxable/tax breakdown over [from,to] (output=sales, input=purchases). */
    @RequestMapping(value = "/taxBreakdown", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> taxBreakdown(final HttpServletRequest request) {
        try {
            String from = request.getParameter("from"), to = request.getParameter("to");
            StringBuilder q = new StringBuilder();
            if (from != null && !from.isEmpty()) q.append("from=").append(from);
            if (to != null && !to.isEmpty()) q.append(q.length() > 0 ? "&" : "").append("to=").append(to);
            return client.get("/taxBreakdown", q.toString());
        } catch (Exception e) { LOGGER.error("taxBreakdown proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }
}
