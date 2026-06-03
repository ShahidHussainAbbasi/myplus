package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.web.util.BusinessRestClient;

@RestController
public class ItemController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getUserItem", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getUserItem(final HttpServletRequest request) {
        try {
            return client.get("/getUserItem");
        } catch (Exception e) {
            LOGGER.error("getUserItem proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/getUserItems", method = RequestMethod.GET)
    @ResponseBody
    public String getUserItems(final HttpServletRequest request) {
        try {
            return client.getString("/getUserItems");
        } catch (Exception e) {
            LOGGER.error("getUserItems proxy error", e);
            return "<option value=''> Item not available </option>";
        }
    }

    @RequestMapping(value = "/getItem", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getItem(@RequestParam final Long itemId) {
        try {
            return client.get("/getItem", "itemId=" + itemId);
        } catch (Exception e) {
            LOGGER.error("getItem proxy error", e);
            return null;
        }
    }

    @RequestMapping(value = "/getAllItem", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAllItem(final HttpServletRequest request) {
        try {
            return client.get("/getAllItem");
        } catch (Exception e) {
            LOGGER.error("getAllItem proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/addItem", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addItem(final HttpServletRequest request) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
            return client.postForm("/addItem", params);
        } catch (Exception e) {
            LOGGER.error("addItem proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    @RequestMapping(value = "/deleteItem", method = RequestMethod.POST)
    @ResponseBody
    public Boolean deleteItem(HttpServletRequest req, HttpServletResponse resp) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            params.put("checked", req.getParameter("checked"));
            return client.postFormBoolean("/deleteItem", params);
        } catch (Exception e) {
            LOGGER.error("deleteItem proxy error", e);
            return false;
        }
    }
}
