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

import com.web.util.CatalogRestClient;

/**
 * Proxies the catalog-backed item picker (slice 33, U4.3 pre-stage). Additive — the new sell-screen picker
 * will load products from here ({@code /catalogProducts} → catalog-service {@code /products}) and submit a
 * productId. Nothing calls it yet, so it does not change the existing item flow.
 */
@RestController
public class CatalogController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private CatalogRestClient catalog;

    /** Catalog products for the picker. Pass-through of paging params, e.g. /catalogProducts?size=1000. */
    @GetMapping("/catalogProducts")
    @ResponseBody
    public Map<String, Object> products(final HttpServletRequest request) {
        try {
            return catalog.get("/products", request.getQueryString());
        } catch (Exception e) {
            LOGGER.error("catalogProducts proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }
}
