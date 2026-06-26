package com.web.controller.business;

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

import com.web.util.BusinessRestClient;

/** Proxy for park / hold & resume a sale (POS R10, slice 40) → business-service. */
@Controller
public class ParkedSaleController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/parkSale", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> parkSale(@RequestBody final Map<String, Object> body) {
        try { return client.postJson("/parkSale", body); }
        catch (Exception e) { LOGGER.error("parkSale proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/parkedSales", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> parkedSales(final HttpServletRequest request) {
        try { return client.get("/parkedSales"); }
        catch (Exception e) { LOGGER.error("parkedSales proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/resumeParked", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> resumeParked(final HttpServletRequest request) {
        try { return client.get("/resumeParked", "id=" + request.getParameter("id")); }
        catch (Exception e) { LOGGER.error("resumeParked proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }

    @RequestMapping(value = "/deleteParked", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteParked(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("id", request.getParameter("id"));
            return client.postForm("/deleteParked", params);
        } catch (Exception e) { LOGGER.error("deleteParked proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }
}
