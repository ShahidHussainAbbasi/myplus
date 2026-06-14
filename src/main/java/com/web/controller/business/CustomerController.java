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
public class CustomerController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserCustomer", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserCustomer(final HttpServletRequest request) {
        try {
            return client.get("/getUserCustomer");
        } catch (Exception e) {
            LOGGER.error("getUserCustomer proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserCustomers", method = RequestMethod.GET)
    @ResponseBody
    public String getUserCustomers(final HttpServletRequest request) {
        try {
            return client.getString("/getUserCustomers");
        } catch (Exception e) {
            LOGGER.error("getUserCustomers proxy error", e);
            return "<option value=''>No Data found</option>";
        }
    }

    @RequestMapping(value = "/getAllCustomer", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllCustomer(final HttpServletRequest request) {
        try {
            return client.get("/getAllCustomer");
        } catch (Exception e) {
            LOGGER.error("getAllCustomer proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addCustomer", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addCustomer(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addCustomer", params);
        } catch (Exception e) {
            LOGGER.error("addCustomer proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteCustomer", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteCustomer(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteCustomer", params);
        } catch (Exception e) {
            LOGGER.error("deleteCustomer proxy error", e);
            return false;
        }
    }
}
