package com.web.controller.agriculture;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.AgricultureRestClient;

@Controller
public class AgricultureExpenseController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AgricultureRestClient client;

    private Map<String, String> params(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }

    @RequestMapping(value = "/addAgricultureExpense", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addAgricultureExpense(final HttpServletRequest request) {
        try {
            return client.postForm("/addAgricultureExpense", params(request));
        } catch (Exception e) {
            LOGGER.error("addAgricultureExpense proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserAgricultureExpense", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserAgricultureExpense(final HttpServletRequest request) {
        try {
            return client.get("/getUserAgricultureExpense");
        } catch (Exception e) {
            LOGGER.error("getUserAgricultureExpense proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/expense/loadLastCropAttached", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> loadLastExpenseCropAttached(@RequestParam Long landId, final HttpServletRequest request) {
        try {
            return client.get("/expense/loadLastCropAttached", "landId=" + landId);
        } catch (Exception e) {
            LOGGER.error("expense loadLastCropAttached proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteAgricultureExpense", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteAgricultureExpense(final HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("checked", request.getParameter("checked"));
            return client.postForm("/deleteAgricultureExpense", params);
        } catch (Exception e) {
            LOGGER.error("deleteAgricultureExpense proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
