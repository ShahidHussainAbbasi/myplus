package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.dto.business.CustomerHistoryDTO;
import com.web.dto.business.SellDTO;
import com.web.util.BusinessRestClient;
import com.web.util.PosOrderRecorder;

@RestController
public class SellController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    /** OMS O5e step 3 — turns a completed Store-vertical sale into its order (see {@link PosOrderRecorder}). */
    @Autowired
    private PosOrderRecorder posOrderRecorder;

    @RequestMapping(value = "/getUserSell", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserSell(final HttpServletRequest request) {
        try {
            String q = request.getParameter("q");
            return client.get("/getUserSell", q != null ? "q=" + q : "");
        } catch (Exception e) {
            LOGGER.error("getUserSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * UI/UX P3 — the shop's best sellers, for the POS quick-pick tiles → business-service
     * {@code /topProducts}. Tenant + store scoping happen there, from the token; nothing here widens it.
     *
     * <p>A failure returns an EMPTY list under a SUCCESS status rather than an error: the tiles are a
     * shortcut, and every product stays reachable through the normal picker, so a shop must never be
     * blocked from selling because a convenience could not be drawn.
     */
    @RequestMapping(value = "/topProducts", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> topProducts(final HttpServletRequest request) {
        try {
            StringBuilder qs = new StringBuilder();
            String days = request.getParameter("days");
            String limit = request.getParameter("limit");
            if (days != null && !days.isBlank()) qs.append("days=").append(enc(days));
            if (limit != null && !limit.isBlank()) {
                if (qs.length() > 0) qs.append('&');
                qs.append("limit=").append(enc(limit));
            }
            return client.get("/topProducts", qs.toString());
        } catch (Exception e) {
            LOGGER.error("topProducts proxy error", e);
            return Map.of("status", "SUCCESS", "collection", java.util.Collections.emptyList());
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // Load a full sale (invoice) for editing — proxies to business-service getSellInvoice.
    @RequestMapping(value = "/getSellInvoice", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getSellInvoice(final HttpServletRequest request) {
        try {
            String sellId = request.getParameter("sellId");
            return client.get("/getSellInvoice", "sellId=" + sellId);
        } catch (Exception e) {
            LOGGER.error("getSellInvoice proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * B2B-P3e-1 (#6): the sale report as CSV. Raw passthrough so the browser saves the file; every filter
     * rides along in the query string, and business-service applies them to the SAME method the screen uses.
     */
    @RequestMapping(value = "/saleReport.csv", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> saleReportCsv(final HttpServletRequest request) {
        try {
            String qs = request.getQueryString();
            String body = client.getString("/saleReport.csv", qs == null ? "" : qs);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"sale-report.csv\"")
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .body(body);
        } catch (Exception e) {
            LOGGER.error("saleReport.csv proxy error", e);
            return org.springframework.http.ResponseEntity.status(502).body("Could not build the report.");
        }
    }

    @RequestMapping(value = "/loadSR", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> loadSR(final SellDTO dto, final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/loadSR", params);
        } catch (Exception e) {
            LOGGER.error("loadSR proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllSell(final HttpServletRequest request) {
        try {
            return client.get("/getAllSell");
        } catch (Exception e) {
            LOGGER.error("getAllSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    // G6 receipts (slice 38) — proxies the printable receipt (by invoice number) to business-service.
    @RequestMapping(value = "/getReceipt", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getReceipt(final HttpServletRequest request) {
        try {
            String invoiceNo = request.getParameter("invoiceNo");
            return client.get("/getReceipt", "invoiceNo=" + java.net.URLEncoder.encode(
                    invoiceNo == null ? "" : invoiceNo, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("getReceipt proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /*
     * B2B-P3g — owner-designed document layouts (business-service owns the data).
     *
     * All five relay UNTYPED (Map / raw JSON String), deliberately. A typed DTO here would be a second
     * definition of the Document Profile shape that has to be kept in step with the validator and the
     * renderer, and the trap this codebase has already been bitten by is the opposite of harmless: a typed
     * proxy silently DROPS any field it does not declare. A layout is opaque to the monolith — it neither
     * reads it nor validates it — so passing it through untouched is both simpler and safer.
     */
    @RequestMapping(value = "/documentTemplates", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> documentTemplates() {
        try {
            return client.get("/documentTemplates", "");
        } catch (Exception e) {
            LOGGER.error("documentTemplates proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/documentTemplate", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> documentTemplate(final HttpServletRequest request) {
        try {
            String id = request.getParameter("id");
            return client.get("/documentTemplate", "id=" + java.net.URLEncoder.encode(
                    id == null ? "" : id, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("documentTemplate proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/documentFields", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> documentFields() {
        try {
            return client.get("/documentFields", "");
        } catch (Exception e) {
            LOGGER.error("documentFields proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/saveDocumentTemplate", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveDocumentTemplate(@RequestBody final Object body) {
        try {
            return client.postJson("/saveDocumentTemplate", body);
        } catch (Exception e) {
            LOGGER.error("saveDocumentTemplate proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteDocumentTemplate", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteDocumentTemplate(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/deleteDocumentTemplate", params);
        } catch (Exception e) {
            LOGGER.error("deleteDocumentTemplate proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    // SF-11: the sale-return / credit-note audit log (proxy to business-service).
    @RequestMapping(value = "/getSaleReturns", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getSaleReturns(final HttpServletRequest request) {
        try {
            return client.get("/getSaleReturns", "");
        } catch (Exception e) {
            LOGGER.error("getSaleReturns proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * OMS O5e step 3 — a Store-vertical sale creates its order here, server-side, instead of the browser
     * posting {@code /recordOrder} after the fact.
     *
     * <p>The proxy itself is unchanged. What is added is the orchestration §2.5 chose (option C): the sale is
     * written first and returned unchanged, and only then does {@link PosOrderRecorder} turn it into an order.
     * That ordering is the point — the order is derived from a sale that already exists, so closing the tab or
     * losing the network can no longer lose it, and a failure to record it cannot fail the sale.
     */
    @RequestMapping(value = "/addSell", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addSell(@RequestBody final CustomerHistoryDTO dto, final HttpServletRequest request) {
        try {
            Map<String, Object> response = client.postJson("/addSell", dto);
            posOrderRecorder.afterSale(response);   // best-effort, gated to the Store vertical, never throws
            return response;
        } catch (Exception e) {
            LOGGER.error("addSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    // In-place edit of an existing invoice (Phase 3). The frontend routes here (instead of addSell)
    // when the cart carries a customer_history_id; business-service reverts the old lines' stock/dues
    // and re-applies the edited cart under the SAME invoice number, all-or-nothing.
    /**
     * INST-1 — a customer's installment plans, for the schedule block on the customer screen.
     *
     * <p>Shipped WITH the slice, not after it: an endpoint with no proxy is unreachable from the only UI this
     * platform has, which is review finding R7 — hit three times in the OMS programme, each a capability
     * built, tested and reachable by nobody.
     *
     * <p>The proxy takes no authorisation decision. Scoping happens in business-service, where the data is.
     */
    @RequestMapping(value = "/installmentPlans", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> installmentPlans(final HttpServletRequest request) {
        try {
            return client.get("/installmentPlans", request.getQueryString());
        } catch (Exception e) {
            LOGGER.error("installmentPlans proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** INST-1 — every plan in the tenant still owing money, most overdue first. */
    @RequestMapping(value = "/installmentPlansOpen", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> installmentPlansOpen(final HttpServletRequest request) {
        try {
            return client.get("/installmentPlansOpen");
        } catch (Exception e) {
            LOGGER.error("installmentPlansOpen proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/updateSell", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateSell(@RequestBody final CustomerHistoryDTO dto, final HttpServletRequest request) {
        try {
            return client.postJson("/updateSell", dto);
        } catch (Exception e) {
            LOGGER.error("updateSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @PostMapping(value = "/addSelling")
    @ResponseBody
    public Map<String, Object> addSelling(@RequestBody final java.util.List<SellDTO> dtos, final HttpServletRequest request) {
        try {
            return client.postJson("/addSelling", dtos);
        } catch (Exception e) {
            LOGGER.error("addSelling proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/revertSell", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> reverSell(final SellDTO dto, final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/revertSell", params);
        } catch (Exception e) {
            LOGGER.error("revertSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteSell", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteSell(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteSell", params);
        } catch (Exception e) {
            LOGGER.error("deleteSell proxy error", e);
            return false;
        }
    }

    @PostMapping(value = "/saleReturn")
    @ResponseBody
    public Map<String, Object> saleReturn(final SellDTO dto, final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/saleReturn", params);
        } catch (Exception e) {
            LOGGER.error("saleReturn proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** Audit #3: proxy the books-safe invoice Void to business-service. */
    @PostMapping(value = "/voidSell")
    @ResponseBody
    public Map<String, Object> voidSell(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/voidSell", params);
        } catch (Exception e) {
            LOGGER.error("voidSell proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
