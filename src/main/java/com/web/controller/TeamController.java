package com.web.controller;

import com.web.util.ProxyErrors;
import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.security.TokenStore;

/**
 * Owner/team management proxy — the businessDashboard "Team" form calls these; they forward to the
 * auth-service org-user endpoints (via the gateway) with the logged-in owner's Bearer token. The
 * auth-service confines everything to the caller's active org and enforces SUPER_PRIVILEGE.
 */
@Controller
public class TeamController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private TokenStore tokenStore;

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate rest = new RestTemplate();

    @RequestMapping(value = "/team/users", method = RequestMethod.GET)
    @ResponseBody
    public Object listTeam() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            return rest.exchange(gatewayUrl + "/api/auth/org/users", HttpMethod.GET,
                    new HttpEntity<>(headers), Object.class).getBody();
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("listTeam {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ProxyErrors.failure(e);
        } catch (Exception e) {
            LOGGER.error("listTeam proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    // ── PERM-1: permission sets ────────────────────────────────────────────────────────────────
    //
    // Straight pass-throughs. auth-service is the authority on who may call these — every one is
    // @PreAuthorize("hasAuthority('ROLE_OWNER')") there — and re-checking here would be a SECOND opinion
    // about the same question, which is how a proxy and its service come to disagree. The proxy's job is
    // to carry the token and the body, nothing else.

    /** The catalog and this tenant's sets — everything the matrix screen draws itself from. */
    @RequestMapping(value = "/team/permissions", method = RequestMethod.GET)
    @ResponseBody
    public Object permissions() {
        return relay("/api/auth/org/permissions", HttpMethod.GET, null, "load permissions");
    }

    @RequestMapping(value = "/team/permissions/sets", method = RequestMethod.POST)
    @ResponseBody
    public Object saveSet(@RequestBody Map<String, Object> body) {   // Object: `codes` is an array
        return relay("/api/auth/org/permissions/sets", HttpMethod.POST, body, "save the permission set");
    }

    @RequestMapping(value = "/team/permissions/assign", method = RequestMethod.POST)
    @ResponseBody
    public Object assign(@RequestBody Map<String, Object> body) {
        return relay("/api/auth/org/permissions/assign", HttpMethod.POST, body, "save the assignment");
    }

    @RequestMapping(value = "/team/permissions/sets/{id}", method = RequestMethod.DELETE)
    @ResponseBody
    public Object deleteSet(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return relay("/api/auth/org/permissions/sets/" + id, HttpMethod.DELETE, null, "delete the set");
    }

    /**
     * One relay for all four, because four copies of the same six lines is four places to forget the
     * bearer token. The REFUSAL is surfaced verbatim: auth-service already words these for the person
     * reading them ("that is a built-in set and cannot be changed"), and replacing that with a generic
     * failure would throw away the only sentence that tells the owner what to do instead.
     */
    private Object relay(String path, HttpMethod method, Object body, String what) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
            return rest.exchange(gatewayUrl + path, method,
                    new HttpEntity<>(body, headers), Object.class).getBody();
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("{} {}: {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            return Collections.singletonMap("message", extractMessage(e.getResponseBodyAsString()));
        } catch (Exception e) {
            LOGGER.error("{} proxy error", path, e);
            return Collections.singletonMap("message", "Could not " + what + ". Please try again.");
        }
    }

    @RequestMapping(value = "/team/users", method = RequestMethod.POST)
    @ResponseBody
    public Object createTeamUser(@RequestBody Map<String, Object> body) {   // Object: storeIds is an array
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenStore.getAccessToken());
            headers.setContentType(MediaType.APPLICATION_JSON);
            return rest.exchange(gatewayUrl + "/api/auth/org/users", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Object.class).getBody();
        } catch (HttpStatusCodeException e) {
            // Surface the auth-service message (e.g. duplicate email / invalid role) to the form.
            LOGGER.warn("createTeamUser {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Collections.singletonMap("message", extractMessage(e.getResponseBodyAsString()));
        } catch (Exception e) {
            LOGGER.error("createTeamUser proxy error", e);
            return Collections.singletonMap("message", "Could not add the team member. Please try again.");
        }
    }

    /**
     * The server's own sentence, out of its JSON body.
     *
     * <p>PARSED, not regexed. The previous version matched the message with a character class that stops
     * at the first quote &mdash; and a refusal that NAMES something in quotes ("Cashier" is a built-in
     * set, duplicate it and edit the copy) therefore reached the owner as a single backslash. That
     * sentence is the only part of a refusal that tells somebody what to do next, so it has to survive
     * the trip whole.
     */
    private String extractMessage(String body) {
        if (body == null || body.isBlank()) return "Could not complete that request.";
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String msg = n.path("message").asText(null);
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Exception ignored) {
            // Not JSON, or a shape we do not know. Falling through beats surfacing a parser error to
            // somebody who asked to save a permission set.
        }
        return "Could not complete that request.";
    }
}
