package com.web.controller;

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
            return Collections.singletonMap("success", false);
        } catch (Exception e) {
            LOGGER.error("listTeam proxy error", e);
            return Collections.singletonMap("success", false);
        }
    }

    @RequestMapping(value = "/team/users", method = RequestMethod.POST)
    @ResponseBody
    public Object createTeamUser(@RequestBody Map<String, String> body) {
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

    private String extractMessage(String body) {
        if (body == null) return "Could not add the team member.";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : "Could not add the team member.";
    }
}
