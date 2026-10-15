package com.web.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.security.TokenStore;
import com.web.util.ProxyErrors;

/**
 * HC-1c — the clinic ↔ pharmacy link: pass-throughs to auth-service {@code /api/auth/links} with the signed-in person's
 * token. auth-service decides everything (owner/admin, which business, who may accept); this only carries the token and
 * the form, and turns a refused HTTP call into the platform's {@code success:false} shape so the screen has one shape to
 * read. The business is NEVER taken from the form — auth reads it from the token.
 */
@Controller
public class OrgLinkController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrgLinkController.class);

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    @RequestMapping(value = "/links/settings", method = RequestMethod.GET)
    @ResponseBody
    public Object settings() {
        return relay("/api/auth/links/settings", HttpMethod.GET, null);
    }

    @RequestMapping(value = "/links/settings", method = RequestMethod.POST)
    @ResponseBody
    public Object saveSettings(HttpServletRequest request) {
        return relay("/api/auth/links/settings", HttpMethod.POST, Map.of("acceptClinics",
                Boolean.parseBoolean(request.getParameter("acceptClinics"))));
    }

    @RequestMapping(value = "/links/pharmacies", method = RequestMethod.GET)
    @ResponseBody
    public Object pharmacies(HttpServletRequest request) {
        String q = request.getParameter("q");
        return relay("/api/auth/links/pharmacies?q=" + java.net.URLEncoder.encode(q == null ? "" : q,
                java.nio.charset.StandardCharsets.UTF_8), HttpMethod.GET, null);
    }

    @RequestMapping(value = "/links", method = RequestMethod.GET)
    @ResponseBody
    public Object mine() {
        return relay("/api/auth/links", HttpMethod.GET, null);
    }

    @RequestMapping(value = "/links", method = RequestMethod.POST)
    @ResponseBody
    public Object request(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pharmacyOrgId", request.getParameter("pharmacyOrgId"));
        return relay("/api/auth/links", HttpMethod.POST, body);
    }

    @RequestMapping(value = "/links/{id}/accept", method = RequestMethod.POST)
    @ResponseBody
    public Object accept(@PathVariable Long id) {
        return relay("/api/auth/links/" + id + "/accept", HttpMethod.POST, Map.of());
    }

    @RequestMapping(value = "/links/{id}/end", method = RequestMethod.POST)
    @ResponseBody
    public Object end(@PathVariable Long id, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", request.getParameter("reason"));
        return relay("/api/auth/links/" + id + "/end", HttpMethod.POST, body);
    }

    private Object relay(String path, HttpMethod method, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
            return rest.exchange(gatewayUrl + path, method, new HttpEntity<>(body, headers), Object.class).getBody();
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("{} {}: {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            return ProxyErrors.failure(e);
        } catch (Exception e) {
            LOGGER.error("{} proxy error", path, e);
            return ProxyErrors.failure(e);
        }
    }
}
