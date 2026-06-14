package com.web.controller.abbasiWelfare;

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
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.WelfareRestClient;

@Controller
public class DonationController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private WelfareRestClient client;

    private Map<String, String> params(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }

    @RequestMapping(value = "/getUserDonations", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserDonations(final HttpServletRequest request) {
        try {
            return client.get("/getUserDonations");
        } catch (Exception e) {
            LOGGER.error("getUserDonations proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserDonation", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserDonation(final HttpServletRequest request) {
        try {
            return client.get("/getUserDonation");
        } catch (Exception e) {
            LOGGER.error("getUserDonation proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserDonator", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserDonator(final HttpServletRequest request) {
        try {
            return client.get("/getUserDonator");
        } catch (Exception e) {
            LOGGER.error("getUserDonator proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getAllDonators", method = RequestMethod.GET)
    @ResponseBody
    public String getAllDonators() {
        try {
            return client.getString("/getAllDonators");
        } catch (Exception e) {
            LOGGER.error("getAllDonators proxy error", e);
            return "<option value=''>No Data found</option>";
        }
    }

    @RequestMapping(value = "/addDonator", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addDonator(final HttpServletRequest request) {
        try {
            return client.postForm("/addDonator", params(request));
        } catch (Exception e) {
            LOGGER.error("addDonator proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addDonation", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addDonation(final HttpServletRequest request) {
        try {
            return client.postForm("/addDonation", params(request));
        } catch (Exception e) {
            LOGGER.error("addDonation proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteDonator", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteDonator(HttpServletRequest req) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteDonator", params);
        } catch (Exception e) {
            LOGGER.error("deleteDonator proxy error", e);
            return false;
        }
    }

    @RequestMapping(value = "/deleteDonation", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteDonation(HttpServletRequest req) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteDonation", params);
        } catch (Exception e) {
            LOGGER.error("deleteDonation proxy error", e);
            return false;
        }
    }
}
