package com.web.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Returns the demo free-trial cap as a clean JSON 403 the dashboards' AJAX can detect
 * ({@code {code:"DEMO_LIMIT", message:...}}) and turn into the upsell prompt.
 */
@RestControllerAdvice
public class DemoLimitAdvice {

    @ExceptionHandler(DemoLimitException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> handle(DemoLimitException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "DEMO_LIMIT");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
