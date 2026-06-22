package com.myplus.common.captcha;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * reCAPTCHA configuration (slice 33, Phase 9). Bound from {@code app.captcha.*}:
 * <pre>
 * app.captcha.enabled: true|false   # default false — verifier is a no-op when off
 * app.captcha.site:    &lt;site key&gt;
 * app.captcha.secret:  &lt;secret key&gt;
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "app.captcha")
public class CaptchaProperties {

    /** When false, {@link CaptchaVerifier#verify} does nothing — every environment defaults to off. */
    private boolean enabled = false;

    /** reCAPTCHA site key (public; for rendering the widget). */
    private String site;

    /** reCAPTCHA secret key (server-side verification). */
    private String secret;
}
