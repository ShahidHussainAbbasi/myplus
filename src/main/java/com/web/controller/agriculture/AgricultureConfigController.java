package com.web.controller.agriculture;

import com.web.util.ProxyErrors;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.AgricultureRestClient;

/**
 * Owner Configuration screen proxy → agriculture-service's shared common-settings adapter ({@code /getConfig} +
 * {@code /saveConfig}). Mirrors the education/business/welfare config proxies. The service-side @PreAuthorize is
 * the real write gate.
 */
@Controller
public class AgricultureConfigController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AgricultureRestClient client;

    // Distinct monolith paths (the single monolith context already maps /getConfig for education); each forwards
    // to the agriculture-service's own /getConfig+/saveConfig (a separate context, no collision there).
    @RequestMapping(value = "/getAgricultureConfig", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getConfig(final HttpServletRequest request) {
        try {
            return client.get("/getConfig");
        } catch (Exception e) {
            LOGGER.error("agriculture getConfig proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/saveAgricultureConfig", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> saveConfig(final HttpServletRequest request) {
        try {
            Map<String, String> params = new HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/saveConfig", params);
        } catch (Exception e) {
            LOGGER.error("agriculture saveConfig proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }
}
