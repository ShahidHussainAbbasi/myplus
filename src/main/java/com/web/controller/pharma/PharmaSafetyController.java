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

/** Monolith proxy for pharmacy safety (P7, slice 44) → pharma-service via the gateway (/api/pharma/...). */
@Controller
public class PharmaSafetyController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private PharmaRestClient client;

    @RequestMapping(value = "/checkSafety", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> checkSafety(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/safety/check", body); }
        catch (Exception e) { LOGGER.error("checkSafety proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/getClinical", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getClinical(final HttpServletRequest request) {
        try { return client.get("/clinical"); }
        catch (Exception e) { LOGGER.error("getClinical proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/saveClinical", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveClinical(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/clinical", body); }
        catch (Exception e) { LOGGER.error("saveClinical proxy error", e); return Collections.singletonMap("success", false); }
    }

    @RequestMapping(value = "/addInteraction", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addInteraction(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/interactions", body); }
        catch (Exception e) { LOGGER.error("addInteraction proxy error", e); return Collections.singletonMap("success", false); }
    }

    /** P8 (slice 45): the controlled-substance register. */
    @RequestMapping(value = "/controlledRegister", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> controlledRegister(final HttpServletRequest request) {
        try { return client.get("/controlled-register"); }
        catch (Exception e) { LOGGER.error("controlledRegister proxy error", e); return Collections.singletonMap("success", false); }
    }
}
