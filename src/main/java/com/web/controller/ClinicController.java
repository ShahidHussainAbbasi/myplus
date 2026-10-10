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

    // ── S2: doctors and today's line ──────────────────────────────────────────────────────────────────────

    @GetMapping(value = "/clinic/doctors", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> doctors() {
        return call(() -> clinic.get("/doctors"));
    }

    @PostMapping(value = "/clinic/doctors", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> addDoctor(@RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/doctors", body));
    }

    @PostMapping(value = "/clinic/doctors/day", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> doctorDay(@RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/doctors/day", body));
    }

    @GetMapping(value = "/clinic/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> queue(@RequestParam(required = false) Long providerId) {
        return call(() -> clinic.get(providerId == null ? "/queue" : "/queue?providerId=" + providerId));
    }

    @PostMapping(value = "/clinic/queue/next", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> callNext(@RequestParam Long providerId) {
        return call(() -> clinic.send(HttpMethod.POST, "/queue/next?providerId=" + providerId, null));
    }

    @PostMapping(value = "/clinic/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> issueToken(@RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/tokens", body));
    }

    @GetMapping(value = "/clinic/tokens/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> token(@PathVariable Long id) {
        return call(() -> clinic.get("/tokens/" + id));
    }

    /** call · recall · start · park · resume · complete · cancel · noShow — anything else is refused here. */
    @PostMapping(value = "/clinic/tokens/{id}/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> moveToken(@PathVariable Long id, @PathVariable String action,
                                            @RequestBody(required = false) Map<String, Object> body) {
        if (!action.matches("call|recall|start|park|resume|complete|cancel|noShow")) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"message\":\"Unknown action.\"}");
        }
        return call(() -> clinic.send(HttpMethod.POST, "/tokens/" + id + "/" + action, body));
    }

    // ── S3a: the doctor's consultation (every call needs clinic.consult; clinical-service checks it) ──────────

    @PostMapping(value = "/clinic/consult/next", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultNext(@RequestParam Long providerId) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/next?providerId=" + providerId, null));
    }

    @PostMapping(value = "/clinic/consult/tokens/{tokenId}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultStart(@PathVariable Long tokenId) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/tokens/" + tokenId + "/start", null));
    }

    @GetMapping(value = "/clinic/consult/tokens/{tokenId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultByToken(@PathVariable Long tokenId) {
        return call(() -> clinic.get("/consult/tokens/" + tokenId));
    }

    @GetMapping(value = "/clinic/consult/encounters/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultOpen(@PathVariable Long id) {
        return call(() -> clinic.get("/consult/encounters/" + id));
    }

    @PutMapping(value = "/clinic/consult/encounters/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultUpdate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.PUT, "/consult/encounters/" + id, body));
    }

    @PostMapping(value = "/clinic/consult/encounters/{id}/notes", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultNote(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/encounters/" + id + "/notes", body));
    }

    @PostMapping(value = "/clinic/consult/encounters/{id}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultComplete(@PathVariable Long id) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/encounters/" + id + "/complete", null));
    }

    // ── S3b-1: the doctor's prescription ──────────────────────────────────────────────────────────────────────

    @PutMapping(value = "/clinic/consult/encounters/{id}/rx", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultSaveRx(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.PUT, "/consult/encounters/" + id + "/rx", body));
    }

    @PostMapping(value = "/clinic/consult/encounters/{id}/rx/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultSubmitRx(@PathVariable Long id) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/encounters/" + id + "/rx/submit", null));
    }

    // ── H2: Register doctor (the login + the doctor + the link), link / unlink a login, which doctor am I ─────────

    @Autowired
    private com.security.TokenStore tokenStore;

    @org.springframework.beans.factory.annotation.Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final org.springframework.web.client.RestTemplate authRest = new org.springframework.web.client.RestTemplate();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Register doctor — orchestrated HERE because the login is auth-service's and its create needs the caller's bearer
     * (owner / admin), which clinical-service never holds. Two steps, in this order:
     * <ol>
     *   <li>auth {@code POST /api/auth/org/users} (role USER): the login, with a set-password email;</li>
     *   <li>clinical {@code POST /doctors/register} with that userId: the doctor and the link (clinical checks the link
     *       can be made BEFORE it creates the doctor).</li>
     * </ol>
     * If step 2 fails the login already exists: the reply says so, and <b>Link login</b> on the Doctors screen finishes
     * it — never a second login (auth refuses the same email twice).
     */
    @PostMapping(value = "/clinic/doctors/register", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> registerDoctor(@RequestBody Map<String, Object> body) {
        String email = body.get("email") == null ? "" : String.valueOf(body.get("email")).trim();
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty()) return refusal(HttpStatus.BAD_REQUEST, "Enter the doctor's name.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return refusal(HttpStatus.BAD_REQUEST, "Enter the doctor's email: their login and set-password email go there.");
        }
        Long userId;
        try {
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setBearerAuth(tokenStore.getAccessToken());
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> login = new java.util.LinkedHashMap<>();
            login.put("firstName", name);
            login.put("lastName", "");
            login.put("email", email);
            login.put("role", "USER");
            Map<String, Object> made = authRest.exchange(gatewayUrl + "/api/auth/org/users", HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(login, h), Map.class).getBody();
            Object data = made == null ? null : made.get("data");
            Object id = data instanceof Map ? ((Map<String, Object>) data).get("userId") : null;
            if (id == null) return refusal(HttpStatus.BAD_GATEWAY, "The login could not be created. Nothing was saved; try again.");
            userId = Long.valueOf(String.valueOf(id));
        } catch (HttpStatusCodeException e) {
            String m = messageOf(e.getResponseBodyAsString());
            if (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("already registered")) {
                m = email + " already has a login. Add the doctor without a login, then use Link login.";
            }
            return refusal(e.getStatusCode().value() == 403 ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST,
                    m != null ? m : "The login could not be created.");
        } catch (Exception e) {
            LOGGER.error("register doctor: login step", e);
            return refusal(HttpStatus.SERVICE_UNAVAILABLE, "The login could not be created right now. Nothing was saved; try again.");
        }
        Map<String, Object> doctor = new java.util.LinkedHashMap<>(body);
        doctor.remove("email");
        doctor.put("userId", userId);
        ResponseEntity<String> r = call(() -> clinic.send(HttpMethod.POST, "/doctors/register", doctor));
        if (!r.getStatusCode().is2xxSuccessful() || !String.valueOf(r.getBody()).contains("\"success\":true")) {
            String why = messageOf(r.getBody());
            return refusal(HttpStatus.CONFLICT, "The login " + email + " was created, but the doctor was not saved"
                    + (why == null ? "." : ": " + why) + " Add the doctor without a login, then use Link login.");
        }
        return r;
    }

    @PostMapping(value = "/clinic/doctors/{id}/link", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> linkDoctor(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/doctors/" + id + "/link", body));
    }

    @PostMapping(value = "/clinic/doctors/{id}/unlink", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> unlinkDoctor(@PathVariable Long id) {
        return call(() -> clinic.send(HttpMethod.POST, "/doctors/" + id + "/unlink", null));
    }

    @GetMapping(value = "/clinic/doctors/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> me() {
        return call(() -> clinic.get("/doctors/me"));
    }

    private static ResponseEntity<String> refusal(HttpStatus status, String message) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("success", false);
        m.put("message", message);
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(m));
        } catch (Exception e) {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body("{\"success\":false}");
        }
    }

    private static String messageOf(String json) {
        try {
            Object m = JSON.readValue(json, Map.class).get("message");
            return m == null ? null : String.valueOf(m);
        } catch (Exception e) {
            return null;
        }
    }

    // ── S3b-2: prescription templates ─────────────────────────────────────────────────────────────────────────

    @GetMapping(value = "/clinic/consult/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultTemplates() {
        return call(() -> clinic.get("/consult/templates"));
    }

    @PostMapping(value = "/clinic/consult/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultSaveTemplate(@RequestBody Map<String, Object> body) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/templates", body));
    }

    @PostMapping(value = "/clinic/consult/templates/{id}/retire", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> consultRetireTemplate(@PathVariable Long id) {
        return call(() -> clinic.send(HttpMethod.POST, "/consult/templates/" + id + "/retire", null));
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
