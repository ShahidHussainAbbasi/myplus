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

/** Proxy for the cashier shift / cash-drawer / X-Z report (POS day-close, slice 39) → business-service. */
@Controller
public class ShiftController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    private Map<String, String> formParams(HttpServletRequest request) {
        Map<String, String> params = new java.util.HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }

    @RequestMapping(value = "/openShift", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> openShift(final HttpServletRequest request) {
        try { return client.postForm("/openShift", formParams(request)); }
        catch (Exception e) { LOGGER.error("openShift proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/currentShift", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> currentShift(final HttpServletRequest request) {
        try { return client.get("/currentShift"); }
        catch (Exception e) { LOGGER.error("currentShift proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/cashMovement", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> cashMovement(final HttpServletRequest request) {
        try { return client.postForm("/cashMovement", formParams(request)); }
        catch (Exception e) { LOGGER.error("cashMovement proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/shiftReport", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> shiftReport(final HttpServletRequest request) {
        try { return client.get("/shiftReport"); }
        catch (Exception e) { LOGGER.error("shiftReport proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/closeShift", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> closeShift(final HttpServletRequest request) {
        try { return client.postForm("/closeShift", formParams(request)); }
        catch (Exception e) { LOGGER.error("closeShift proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }
}
