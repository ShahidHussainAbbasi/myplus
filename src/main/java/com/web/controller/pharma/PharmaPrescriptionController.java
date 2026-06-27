package com.web.controller.pharma;

import java.util.Collections;
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
        catch (Exception e) { LOGGER.error("getPrescriptions proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/getPrescription", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getPrescription(final HttpServletRequest request) {
        try { return client.get("/prescriptions/" + request.getParameter("id")); }
        catch (Exception e) { LOGGER.error("getPrescription proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/addPrescription", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addPrescription(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/prescriptions", body); }
        catch (Exception e) { LOGGER.error("addPrescription proxy error", e); return Collections.singletonMap("success", false); }
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
            return Collections.singletonMap("success", false);
        }
    }
}
