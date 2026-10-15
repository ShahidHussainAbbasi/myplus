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
 * SRA-1 — the business's cities and areas, passed through to business-service, for the inline address form on the
 * customer and supplier screens (static/js/business/address.js).
 *
 * <p>Adding is the {@code area.create} permission: auth-service's endpoint_permission rows (V31) map POST /addCity and
 * /addArea to it, and PermissionInterceptor applies that before this runs. Reading is unmapped — both forms' pickers
 * need it.
 */
@Controller
public class AddressController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    @RequestMapping(value = "/getCities", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getCities() {
        try {
            return client.get("/getCities");
        } catch (Exception e) {
            LOGGER.error("getCities proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/getAreas", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getAreas() {
        try {
            return client.get("/getAreas");
        } catch (Exception e) {
            LOGGER.error("getAreas proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/addCity", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addCity(final HttpServletRequest request) {
        try {
            return client.postForm("/addCity", single(request));
        } catch (Exception e) {
            LOGGER.error("addCity proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    @RequestMapping(value = "/addArea", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> addArea(final HttpServletRequest request) {
        try {
            return client.postForm("/addArea", single(request));
        } catch (Exception e) {
            LOGGER.error("addArea proxy error", e);
            return ProxyErrors.statusError(e);
        }
    }

    /** One value per name — the same flattening /addCustomer uses; these forms send no repeated parameters. */
    private static Map<String, String> single(HttpServletRequest request) {
        Map<String, String> params = new java.util.HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }
}
