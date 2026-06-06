package com.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Wraps an {@link HttpServletRequest} so every form/query parameter value is sanitized via
 * {@link XssSanitizer} before controllers read it (e.g. the {@code /addItem}, {@code /addCustomer}
 * proxy controllers that read params from the request and forward them to the microservices).
 *
 * Credential / opaque-token fields are exempt: passwords, 2FA codes and tokens must reach the
 * authentication layer byte-for-byte, and stripping HTML-like substrings from them would silently
 * corrupt valid values (and they are never rendered as HTML).
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
