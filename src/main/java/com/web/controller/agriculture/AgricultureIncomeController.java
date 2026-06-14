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
public class AgricultureIncomeController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AgricultureRestClient client;

    private Map<String, String> params(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }

    @RequestMapping(value = "/addAgricultureIncome", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addAgricultureIncome(final HttpServletRequest request) {
        try {
            return client.postForm("/addAgricultureIncome", params(request));
        } catch (Exception e) {
            LOGGER.error("addAgricultureIncome proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserAgricultureIncome", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserAgricultureIncome(final HttpServletRequest request) {
        try {
            return client.get("/getUserAgricultureIncome");
        } catch (Exception e) {
            LOGGER.error("getUserAgricultureIncome proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/income/loadLastCropAttached", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> loadLastIncomeCropAttached(@RequestParam Long landId, final HttpServletRequest request) {
        try {
            return client.get("/income/loadLastCropAttached", "landId=" + landId);
        } catch (Exception e) {
            LOGGER.error("income loadLastCropAttached proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteAgricultureIncome", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> deleteAgricultureIncome(final HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("checked", request.getParameter("checked"));
            return client.postForm("/deleteAgricultureIncome", params);
        } catch (Exception e) {
            LOGGER.error("deleteAgricultureIncome proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
