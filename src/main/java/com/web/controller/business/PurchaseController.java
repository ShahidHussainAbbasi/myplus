package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

@Controller
public class PurchaseController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserPurchase", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserPurchase(final HttpServletRequest request) {
        try {
            return client.get("/getUserPurchase");
        } catch (Exception e) {
            LOGGER.error("getUserPurchase proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getAllPurchase", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllPurchase(final HttpServletRequest request) {
        try {
            return client.get("/getAllPurchase");
        } catch (Exception e) {
            LOGGER.error("getAllPurchase proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addPurchase", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addPurchase(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addPurchase", params);
        } catch (Exception e) {
            LOGGER.error("addPurchase proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deletePurchase", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deletePurchase(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deletePurchase", params);
        } catch (Exception e) {
            LOGGER.error("deletePurchase proxy error", e);
            return false;
        }
    }
}
