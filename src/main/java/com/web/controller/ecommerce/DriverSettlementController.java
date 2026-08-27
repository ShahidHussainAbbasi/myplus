package com.web.controller.ecommerce;

import com.web.util.ProxyErrors;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.util.MarketplaceRestClient;

/**
 * OMS O7 D5 — monolith proxy for driver settlement / remittance → marketplace-service
 * ({@code /api/marketplace/driver-settlements}).
 *
 * <h3>Why these ship WITH the screen, in the same slice</h3>
 * Three times in this programme a capability has shipped that nothing could reach — O3's setting nothing read,
 * O4's endpoints with no proxy, O5d's policy no UI could satisfy. The screen (`#DriverSettlementDiv`) and these
 * proxies are the same change.
 *
 * <h3>Errors are relayed, never flattened</h3>
 * Standard D3d. Every refusal this feature has is something the ADMIN must act on and nothing a retry fixes:
 * <i>"the cash is 300 SHORT — explain the difference"</i>, <i>"these collections name more than one driver"</i>,
 * <i>"another settlement took some of these while this one was being prepared"</i>, and the accounting period
 * being closed. "Could not settle" would send them to press the button again.
 */
@Controller
public class DriverSettlementController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private MarketplaceRestClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The day-end worklist: cash keyed as collected and not yet handed over. */
    @RequestMapping(value = "/getDriverCollections", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getDriverCollections(final HttpServletRequest request) {
        try {
            return client.get("/driver-settlements/collections"
                    + relayQuery(request, "driver", "from", "to", "page", "size"));
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not load the driver's collections.");
        } catch (Exception e) {
            LOGGER.error("getDriverCollections proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Count the bag and hand it over — the act that posts the receipts to AR. */
    @RequestMapping(value = "/settleDriver", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> settleDriver(@RequestBody final Map<String, Object> body) {
        try {
            return client.postJson("/driver-settlements", body);
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not settle the driver.");
        } catch (Exception e) {
            LOGGER.error("settleDriver proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Past remittances, newest first. */
    @RequestMapping(value = "/getDriverSettlements", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getDriverSettlements(final HttpServletRequest request) {
        try {
            return client.get("/driver-settlements"
                    + relayQuery(request, "driver", "from", "to", "page", "size"));
        } catch (HttpStatusCodeException e) {
            return relayError(e, "Could not load the settlements.");
        } catch (Exception e) {
            LOGGER.error("getDriverSettlements proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** One remittance, with the collections it swept up and the receipts it raised. */
    @RequestMapping(value = "/getDriverSettlement", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getDriverSettlement(final HttpServletRequest request) {
        try {
            String id = request.getParameter("id");
            return client.get("/driver-settlements/" + java.net.URLEncoder.encode(
                    id == null ? "" : id, java.nio.charset.StandardCharsets.UTF_8));
        } catch (HttpStatusCodeException e) {
            // A scoped miss is a 404 "Settlement not found" — another tenant's is indistinguishable from a
            // missing one, and that wording is the marketplace's to give.
            return relayError(e, "Could not load the settlement.");
        } catch (Exception e) {
            LOGGER.error("getDriverSettlement proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Rebuild the named request parameters as an encoded query string; empty when none were supplied. */
    private static String relayQuery(HttpServletRequest request, String... names) {
        StringBuilder qs = new StringBuilder();
        for (String n : names) {
            String v = request.getParameter(n);
            if (v == null || v.isBlank()) continue;
            qs.append(qs.length() == 0 ? '?' : '&').append(n).append('=')
              .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        }
        return qs.toString();
    }

    /** Relay the marketplace's {success,message} body instead of swallowing it into a bare failure (D3d). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> relayError(HttpStatusCodeException e, String fallback) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        try {
            Map<String, Object> err = objectMapper.readValue(e.getResponseBodyAsString(), Map.class);
            out.put("message", err.get("message") != null ? err.get("message") : fallback);
        } catch (Exception ignore) {
            out.put("message", fallback);
        }
        return out;
    }
}
