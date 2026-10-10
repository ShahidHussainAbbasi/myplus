package com.web.controller.pharma;

import com.web.util.ProxyErrors;
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

import com.web.util.GatewayClient;
import com.web.util.PharmaRestClient;

/** Monolith proxy for prescriptions (P5, slice 41) → pharma-service via the gateway (/api/pharma/prescriptions). */
@Controller
public class PharmaPrescriptionController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private PharmaRestClient client;

    @RequestMapping(value = "/getPrescriptions", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getPrescriptions(final HttpServletRequest request) {
        try { return client.get("/prescriptions"); }
        catch (Exception e) { LOGGER.error("getPrescriptions proxy error", e); return ProxyErrors.failure(e); }
    }

    @Autowired
    private com.web.util.ClinicRestClient clinic;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * HMS S4-lite — the pharmacist's search box. A clinic token of today ("A-007"), an MRN or a phone is first
     * resolved by the clinic to the PERSON, and that person's prescriptions are listed; anything else (or a clinic
     * that is switched off / unreachable) is searched as text — name, phone, doctor. Paged: {items, page, hasMore},
     * plus {@code resolved} (who the search found) when the clinic answered, so the screen can say so.
     */
    @RequestMapping(value = "/searchPrescriptions", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> searchPrescriptions(final HttpServletRequest request) {
        String q = request.getParameter("q") == null ? "" : request.getParameter("q").trim();
        int page = parseInt(request.getParameter("page"), 0);
        try {
            Map<String, Object> who = q.isEmpty() ? null : resolveInClinic(q);
            StringBuilder query = new StringBuilder("page=").append(page).append("&size=25");
            if (who != null) query.append("&partyId=").append(who.get("partyId"));
            else if (!q.isEmpty()) query.append("&q=").append(java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> resp = client.get("/prescriptions/search", query.toString());
            // A prescription whose person link was never stamped (the party bridge is best-effort) is invisible to a
            // person search — so an empty first page by person falls back to the typed text, which still matches it.
            if (who != null && page == 0 && resp != null && resp.get("data") instanceof Map
                    && ((java.util.List<?>) ((Map<?, ?>) resp.get("data")).get("items")).isEmpty()) {
                resp = client.get("/prescriptions/search", "page=0&size=25&q="
                        + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (who != null && resp != null) {
                Map<String, Object> out = new java.util.LinkedHashMap<>(resp);
                Map<String, Object> resolved = new java.util.LinkedHashMap<>();
                resolved.put("name", who.get("name"));
                resolved.put("mrn", who.get("mrn"));
                resolved.put("phone", who.get("phone"));
                resolved.put("partyId", who.get("partyId"));
                out.put("resolved", resolved);
                return out;
            }
            return resp;
        } catch (Exception e) {
            LOGGER.error("searchPrescriptions proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** The clinic's answer for a token / MRN / phone, or null — never an error: the text search is the fallback. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveInClinic(String q) {
        boolean shaped = q.matches("(?i)[a-z]{1,2}-\\d{1,4}") || q.toUpperCase(java.util.Locale.ROOT).startsWith("MRN-")
                || q.replaceAll("[^0-9]", "").length() >= 10;
        if (!shaped) return null;
        try {
            String body = clinic.get("/patients/resolve?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)).getBody();
            Map<String, Object> r = JSON.readValue(body, Map.class);
            if (!Boolean.TRUE.equals(r.get("success"))) return null;
            java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) r.get("data");
            if (list == null || list.isEmpty() || list.get(0).get("partyId") == null) return null;
            return list.get(0);   // family on one phone share one person (the household), so the first is the party
        } catch (Exception clinicOffOrDown) {
            return null;
        }
    }

    private static int parseInt(String s, int dflt) {
        try { return s == null ? dflt : Math.max(0, Integer.parseInt(s.trim())); } catch (NumberFormatException e) { return dflt; }
    }

    @RequestMapping(value = "/getPrescription", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getPrescription(final HttpServletRequest request) {
        try { return client.get("/prescriptions/" + request.getParameter("id")); }
        catch (Exception e) { LOGGER.error("getPrescription proxy error", e); return ProxyErrors.failure(e); }
    }

    // The service's validation messages are the whole point of these screens' error handling ("this prescription
    // expired on ...", "quantity must be greater than zero"), so relay them instead of a bare success:false.
    @RequestMapping(value = "/addPrescription", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addPrescription(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/prescriptions", body); }
        catch (Exception e) {
            LOGGER.error("addPrescription proxy error", e);
            return GatewayClient.errorMap(e, "Could not save the prescription.");
        }
    }

    /** P6 (slice 43): record a dispense against a prescription (fulfilled by a trade sale). */
    @RequestMapping(value = "/dispensePrescription", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> dispensePrescription(@RequestBody final Map<String, Object> body) {
        try {
            Object id = body.get("prescriptionId");
            return client.postJson("/prescriptions/" + id + "/dispense", body);
        } catch (Exception e) {
            LOGGER.error("dispensePrescription proxy error", e);
            return GatewayClient.errorMap(e, "Could not record the dispense.");
        }
    }

    /** Withdraw a prescription (no further dispensing); already-dispensed quantities stay on the record. */
    @RequestMapping(value = "/cancelPrescription", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> cancelPrescription(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/prescriptions/" + body.get("prescriptionId") + "/cancel", body); }
        catch (Exception e) {
            LOGGER.error("cancelPrescription proxy error", e);
            return GatewayClient.errorMap(e, "Could not cancel the prescription.");
        }
    }
}
