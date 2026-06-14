package com.web.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

import com.web.dto.DemoRequestDTO;

/**
 * Public "Book a Demo" endpoint for the marketing landing page. No auth (path is permitted + CSRF-exempt
 * in {@code SecSecurityConfig}). Validates here, then proxies the lead to campaign-service via the gateway's
 * open route {@code /api/campaign/public/demo-request} (campaign-service persists + emails).
 */
@Controller
public class DemoController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoController.class);

    @Value("${gateway.url:http://localhost:8765}")
    private String gatewayUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/api/demo-request")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bookDemo(@Valid @RequestBody DemoRequestDTO dto,
                                                        BindingResult binding) {
        // Honeypot: bots fill the hidden "website" field. Pretend success and drop silently.
        if (StringUtils.hasText(dto.getWebsite())) {
            LOGGER.warn("Demo request dropped as spam (honeypot filled, source={})", dto.getSource());
            return ResponseEntity.ok(ok());
        }

        if (binding.hasErrors()) {
            final Map<String, Object> body = result(false,
                    "Please review the highlighted fields and try again.");
            final Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (FieldError fe : binding.getFieldErrors()) {
                fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
            }
            body.put("errors", fieldErrors);
            return ResponseEntity.badRequest().body(body);
        }

        try {
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(gatewayUrl + "/api/campaign/public/demo-request",
                    new HttpEntity<>(dto, headers), Void.class);
            return ResponseEntity.ok(ok());
        } catch (Exception e) {
            LOGGER.error("Demo request proxy to campaign-service failed", e);
            return ResponseEntity.internalServerError().body(result(false,
                    "Something went wrong on our side. Please email maxtheservice@gmail.com and we'll set it up."));
        }
    }

    private static Map<String, Object> ok() {
        return result(true, "Thank you! Our team will reach out within one business day to schedule your demo.");
    }

    private static Map<String, Object> result(final boolean success, final String message) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        return m;
    }
}
