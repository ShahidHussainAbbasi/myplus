package com.web.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.servlet.ModelAndView;

import com.web.util.ClinicRestClient;

/**
 * HMS S1 — the clinic's own page ({@code /clinicDashboard}) and its proxy to clinical-service ({@code /clinic/**}).
 *
 * <p>Thin on purpose: every rule (capability, phone, one patient per phone, tenant, audit) is enforced in the
 * service. A refusal passes through with ITS status and ITS envelope, so "This phone is already registered to …"
 * reaches the front desk with the patient attached, and another clinic's patient stays a 404.
 */
@Controller
public class ClinicController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClinicController.class);

    @Autowired
    private ClinicRestClient clinic;

    @GetMapping("/clinicDashboard")
    public ModelAndView clinicDashboard() {
        return new ModelAndView("clinicDashboard");
    }

    /** {@code ?phone=} who is on a number · {@code ?q=} search · nothing: the most recent. */
    @GetMapping(value = "/clinic/patients", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> find(@RequestParam(required = false) String phone, @RequestParam(required = false) String q) {
        StringBuilder query = new StringBuilder();
        param(query, "phone", phone);
        param(query, "q", q);
        String path = query.length() == 0 ? "/patients" : "/patients?" + query.substring(1);
        return call(() -> clinic.get(path));
    }

    @GetMapping(value = "/clinic/patients/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> get(@PathVariable Long id) {
        return call(() -> clinic.get("/patients/" + id));
    }

    @PostMapping(value = "/clinic/patients", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> register(@RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/patients", body));
    }

    @PutMapping(value = "/clinic/patients/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.PUT, "/patients/" + id, body));
    }

    @PostMapping(value = "/clinic/patients/{id}/link", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> link(@PathVariable Long id) {
        return call(() -> clinic.send(HttpMethod.POST, "/patients/" + id + "/link", null));
    }

    @PostMapping(value = "/clinic/patients/{id}/retire", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> retire(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/patients/" + id + "/retire", body));
    }

    @GetMapping(value = "/clinic/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> settings() {
        return call(() -> clinic.get("/settings"));
    }

    @PostMapping(value = "/clinic/settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> saveSetting(@RequestParam String key, @RequestParam(required = false) String value) {
        StringBuilder q = new StringBuilder();
        param(q, "key", key);
        param(q, "value", value);
        return call(() -> clinic.send(HttpMethod.POST, "/settings?" + q.substring(1), null));
    }

    @PostMapping(value = "/clinic/settings/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> resetSetting(@RequestParam String key) {
        StringBuilder q = new StringBuilder();
        param(q, "key", key);
        return call(() -> clinic.send(HttpMethod.POST, "/settings/reset?" + q.substring(1), null));
    }

    @FunctionalInterface
    private interface Call { ResponseEntity<String> run(); }

    private ResponseEntity<String> call(Call c) {
        try {
            ResponseEntity<String> r = c.run();
            return ResponseEntity.status(r.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(r.getBody());
        } catch (com.web.error.DownstreamNotFoundException e) {
            // GatewayClient turns a 404 into this, not an HttpStatusCodeException. Without this branch another
            // clinic's patient ("Patient not found", by design) reached the screen as "the clinic is not reachable"
            // — found by hms-s1-patient-desk.cy.js S1-08. The service's own body and the 404 pass through.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body(e.getBody() != null && !e.getBody().isBlank() ? e.getBody()
                            : "{\"success\":false,\"message\":\"Patient not found.\"}");
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            LOGGER.error("clinic proxy error", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"message\":\"The clinic is not reachable right now. Nothing was saved; try again in a moment.\"}");
        }
    }

    /** Pre-encoded, because GatewayClient treats the query as already encoded (no double encoding). */
    private static void param(StringBuilder q, String name, String value) {
        if (value == null || value.isBlank()) return;
        q.append('&').append(name).append('=').append(URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
    }
}
