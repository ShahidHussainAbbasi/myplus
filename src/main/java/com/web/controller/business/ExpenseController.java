package com.web.controller.business;

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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;

import com.web.util.ExpenseRestClient;

/**
 * EX-1 — proxies the Expenses screen to expense-service. Thin on purpose: every rule (capability, tier, tenant,
 * idempotency, the purchase/expense boundary) is enforced in the service, never here.
 *
 * <p>A downstream refusal is passed through with ITS status and ITS message ({@code {success:false, message}}),
 * so "Expense management is not switched on…" reaches the owner instead of a generic "could not save" — and a 404
 * stays a 404, so another tenant's voucher is indistinguishable from one that never existed.
 */
@Controller
@RequestMapping("/expense")
public class ExpenseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpenseController.class);

    @Autowired
    private ExpenseRestClient expense;

    @GetMapping(value = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> categories() {
        return call(() -> expense.get("/categories"));
    }

    @PostMapping(value = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> createCategory(@RequestBody Map<String, Object> body) {
        return call(() -> expense.send(HttpMethod.POST, "/categories", body));
    }

    /** EX-2b — what expenses can be tagged to on this dashboard (education | agriculture). */
    @GetMapping(value = "/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> tags(@RequestParam(required = false) String source) {
        StringBuilder q = new StringBuilder("/tags?x=1");
        param(q, "source", source);
        return call(() -> expense.get(q.toString()));
    }

    @GetMapping(value = "/vouchers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        StringBuilder q = new StringBuilder("/vouchers?page=").append(page).append("&size=").append(size);
        param(q, "from", from);
        param(q, "to", to);
        param(q, "status", status);
        return call(() -> expense.get(q.toString()));
    }

    @GetMapping(value = "/vouchers/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> get(@PathVariable Long id) {
        return call(() -> expense.get("/vouchers/" + id));
    }

    @PostMapping(value = "/vouchers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> record(@RequestBody Map<String, Object> body,
                                         @RequestParam(defaultValue = "true") boolean post,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        StringBuilder q = new StringBuilder("/vouchers?post=").append(post);
        param(q, "idempotencyKey", key);
        return call(() -> expense.send(HttpMethod.POST, q.toString(), body));
    }

    @PostMapping(value = "/vouchers/{id}/void", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> voidVoucher(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return call(() -> expense.send(HttpMethod.POST, "/vouchers/" + id + "/void", body));
    }

    /** FP-3 — the suppliers a bill can be owed to (business-service's list, read through expense-service). */
    @GetMapping(value = "/suppliers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> suppliers() {
        return call(() -> expense.get("/suppliers"));
    }

    /** FP-3 — pay (part of) a bill. The screen keeps ONE key per payment dialog, so a retry never pays twice. */
    @PostMapping(value = "/vouchers/{id}/pay", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> pay(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        StringBuilder q = new StringBuilder("/vouchers/").append(id).append("/pay?x=1");
        param(q, "idempotencyKey", key);
        return call(() -> expense.send(HttpMethod.POST, q.toString(), body));
    }

    @GetMapping(value = "/vouchers/{id}/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> payments(@PathVariable Long id) {
        return call(() -> expense.get("/vouchers/" + id + "/payments"));
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────

    private interface Call { ResponseEntity<String> run(); }

    private ResponseEntity<String> call(Call c) {
        try {
            ResponseEntity<String> r = c.run();
            return ResponseEntity.status(r.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(r.getBody());
        } catch (HttpStatusCodeException e) {
            // the service's own envelope and status, unchanged
            return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            LOGGER.error("expense proxy error", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"message\":\"Expenses are not reachable right now. Nothing was saved; try again in a moment.\"}");
        }
    }

    /** Pre-encoded, because GatewayClient treats the query as already encoded (no double encoding). */
    private static void param(StringBuilder q, String name, String value) {
        if (value == null || value.isBlank()) return;
        q.append('&').append(name).append('=').append(URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
    }
}
