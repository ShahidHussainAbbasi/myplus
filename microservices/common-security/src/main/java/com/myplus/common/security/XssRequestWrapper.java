package com.myplus.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Wraps an {@link HttpServletRequest} so that every form/query parameter value is passed through
 * {@link XssSanitizer} before it reaches controllers / DTO binding. Covers form-encoded posts and
 * query strings (the binding path used by the {@code @Validated DTO} controllers). JSON request
 * bodies are handled separately by the Jackson layer.
 *
 * Credential / opaque-token fields are exempt so passwords, 2FA codes and tokens reach the auth
 * layer byte-for-byte (they are never rendered as HTML).
 */
public class XssRequestWrapper extends HttpServletRequestWrapper {

    private static final Set<String> EXEMPT = Set.of(
            "password", "matchingpassword", "oldpassword", "newpassword",
            "confirmpassword", "passwordconfirmation", "token", "code", "_csrf");

    public XssRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    private static boolean isExempt(String name) {
        return name != null && EXEMPT.contains(name.toLowerCase());
    }

    @Override
    public String getParameter(String name) {
        if (isExempt(name)) {
            return super.getParameter(name);
        }
        return XssSanitizer.sanitize(super.getParameter(name));
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null || isExempt(name)) {
            return values;
        }
        String[] sanitized = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            sanitized[i] = XssSanitizer.sanitize(values[i]);
        }
        return sanitized;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> original = super.getParameterMap();
        Map<String, String[]> sanitized = new LinkedHashMap<>(original.size());
        original.forEach((key, values) -> {
            if (values == null || isExempt(key)) {
                sanitized.put(key, values);
                return;
            }
            String[] copy = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                copy[i] = XssSanitizer.sanitize(values[i]);
            }
            sanitized.put(key, copy);
        });
        return sanitized;
    }
}
