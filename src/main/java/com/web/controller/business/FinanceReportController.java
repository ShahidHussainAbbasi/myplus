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

    @RequestMapping(value = "/vendorStatement", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> vendorStatement(final HttpServletRequest request) {
        try {
            String id = request.getParameter("venderId");
            return client.get("/vendorStatement", "venderId=" + (id == null ? "" : id));
        } catch (Exception e) { LOGGER.error("vendorStatement proxy error", e); return Collections.singletonMap("status", "ERROR"); }
    }
}
