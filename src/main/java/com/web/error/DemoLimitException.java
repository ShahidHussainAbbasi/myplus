package com.web.error;

/**
 * Raised when the gateway rejects a demo account's create with {@code 403 DEMO_LIMIT} (the 50-entry/
 * module free-trial cap). Carries the upsell message so {@link DemoLimitAdvice} can return it uniformly
 * to the browser, where the dashboards show the "register at maxtheservice.com" prompt.
 */
public class DemoLimitException extends RuntimeException {
    public DemoLimitException(String message) {
        super(message);
    }
}
