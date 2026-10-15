package com.web.controller.business;

import com.web.util.ProxyErrors;
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

/**
 * SRA-2 — the business's sales reps, passed through to business-service (static/js/business/territory.js).
 * Adding and changing is rep.manage: auth's endpoint_permission rows (V33) map POST /addSalesRep and /updateSalesRep,
 * PermissionInterceptor applies them before this runs, and business-service checks the same code itself. Reading is
 * unmapped — the rep pickers need it.
 */
@Controller
public class SalesRepController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getSalesReps", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getSalesReps() {
        try {
            return client.get("/getSalesReps");
        } catch (Exception e) {
            LOGGER.error("getSalesReps proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/addSalesRep", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addSalesRep(final HttpServletRequest request) {
        try {
            return client.postForm("/addSalesRep", single(request));
        } catch (Exception e) {
            LOGGER.error("addSalesRep proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/updateSalesRep", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> updateSalesRep(final HttpServletRequest request) {
        try {
            return client.postForm("/updateSalesRep", single(request));
        } catch (Exception e) {
            LOGGER.error("updateSalesRep proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** One value per name — these forms send no repeated parameters. */
    private static Map<String, String> single(HttpServletRequest request) {
        Map<String, String> params = new java.util.HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }
}
