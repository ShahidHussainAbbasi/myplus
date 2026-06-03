package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.util.BusinessRestClient;

@RestController
public class BusinessDashboardController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @GetMapping("/getBusinessDashboardStats")
    @ResponseBody
    public Map<String, Object> getBusinessDashboardStats(HttpServletRequest request) {
        try {
            return client.get("/getBusinessDashboardStats");
        } catch (Exception e) {
            LOGGER.error("getBusinessDashboardStats proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @GetMapping("/getDashboardChartData")
    @ResponseBody
    public Map<String, Object> getDashboardChartData(HttpServletRequest request) {
        try {
            return client.get("/getDashboardChartData");
        } catch (Exception e) {
            LOGGER.error("getDashboardChartData proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }
}
