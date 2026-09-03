package com.web.controller.business;

import com.web.util.ProxyErrors;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

@Controller
public class PurchaseController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserPurchase", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserPurchase(final HttpServletRequest request) {
        try {
            return client.get("/getUserPurchase");
        } catch (Exception e) {
            LOGGER.error("getUserPurchase proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/getAllPurchase", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllPurchase(final HttpServletRequest request) {
        try {
            return client.get("/getAllPurchase");
        } catch (Exception e) {
            LOGGER.error("getAllPurchase proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/addPurchase", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addPurchase(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addPurchase", params);
        } catch (Exception e) {
            LOGGER.error("addPurchase proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** Edit an existing purchase → business-service /updatePurchase: updates the record AND reconciles inventory
     *  by the quantity delta (new − old) against the purchase's own batch, instead of re-importing the full qty. */
    @RequestMapping(value = "/updatePurchase", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updatePurchase(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/updatePurchase", params);
        } catch (Exception e) {
            LOGGER.error("updatePurchase proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** Purchase Return (debit note) → business-service /purchaseReturn: reverses stock-in + vendor payable + GL. */
    @RequestMapping(value = "/purchaseReturn", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> purchaseReturn(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/purchaseReturn", params);
        } catch (Exception e) {
            LOGGER.error("purchaseReturn proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** Audit #3: proxy the books-safe bill Void to business-service. */
    @RequestMapping(value = "/voidPurchase", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> voidPurchase(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/voidPurchase", params);
        } catch (Exception e) {
            LOGGER.error("voidPurchase proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/deletePurchase", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deletePurchase(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deletePurchase", params);
        } catch (Exception e) {
            LOGGER.error("deletePurchase proxy error", e);
            return false;
        }
    }

    /**
     * SER-2 — the units of a product currently on the shelf → business-service {@code /serialUnits}.
     *
     * <p>Ships WITH the register rather than after it. A register nobody can query is a table, not a feature:
     * C6 shipped a per-product policy with no control on any screen, and every API test passed while the thing
     * was unusable. A shop recording IMEIs it can never look up is the same mistake with worse consequences —
     * "who did we sell this handset to?" is where a warranty claim, a return and a police enquiry all start.
     */
    @RequestMapping(value = "/serialUnits", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> serialUnits(final HttpServletRequest request) {
        try {
            return client.get("/serialUnits", "productId=" + enc(request.getParameter("productId")));
        } catch (Exception e) {
            LOGGER.error("serialUnits proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /**
     * SER-2 — everything ever recorded under one serial → business-service {@code /serialHistory}.
     *
     * <p>The HISTORY, not the live row. A unit that has already left the shop is exactly the one somebody is
     * asking about, and a query restricted to what is in stock could never answer it — the specific gap
     * {@code InstallmentPlan.assetRef} left, since it held a serial only while a finance plan was running.
     */
    @RequestMapping(value = "/serialHistory", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> serialHistory(final HttpServletRequest request) {
        try {
            return client.get("/serialHistory", "serial=" + enc(request.getParameter("serial")));
        } catch (Exception e) {
            LOGGER.error("serialHistory proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** Task #21 — the debit-note register, so a purchase return can be found and reprinted later. */
    @RequestMapping(value = "/getPurchaseReturns", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getPurchaseReturns(final HttpServletRequest request) {
        try {
            // Task #16 + #24: supplier, product and date ride along; business-service scopes and applies
            // them. Blank values are OMITTED, not forwarded empty — see AppUtil.passThroughQuery.
            return client.get("/getPurchaseReturns",
                    com.web.util.AppUtil.passThroughQuery(request, "venderId", "productId", "from", "to"));
        } catch (Exception e) {
            LOGGER.error("getPurchaseReturns proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /**
     * Task #15 — one debit note, resolved for printing. Straight proxy; the tenant and store checks that make
     * this safe live in business-service, which is the only side that can see the row's org and store.
     */
    @RequestMapping(value = "/debitNote", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> debitNote(final HttpServletRequest request) {
        try {
            return client.get("/debitNote", "no=" + enc(request.getParameter("no")));
        } catch (Exception e) {
            LOGGER.error("debitNote proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    private static String enc(String v) {
        return v == null ? "" : java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
