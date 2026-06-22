package com.myplus.common.captcha;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Stateless reCAPTCHA verifier (slice 33, Phase 9) — ported from the monolith's {@code CaptchaService}.
 * A no-op when {@code app.captcha.enabled=false}, so it is safe to call unconditionally. Callers supply the
 * client IP (the library is servlet-agnostic). On rejection it throws {@link CaptchaInvalidException}; on a
 * transport failure to Google, {@link CaptchaUnavailableException}.
 */
public class CaptchaVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptchaVerifier.class);
    private static final Pattern RESPONSE_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String VERIFY_URL =
            "https://www.google.com/recaptcha/api/siteverify?secret=%s&response=%s&remoteip=%s";

    private final CaptchaProperties properties;
    private final CaptchaAttemptService attemptService;
    private final RestClient restClient;

    public CaptchaVerifier(final CaptchaProperties properties, final CaptchaAttemptService attemptService) {
        this.properties = properties;
        this.attemptService = attemptService;
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(7).toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Verify a captcha token for the given client IP. Does nothing when captcha is disabled.
     *
     * @throws CaptchaInvalidException     missing/malformed/throttled/rejected token
     * @throws CaptchaUnavailableException Google's siteverify could not be reached
     */
    public void verify(final String captchaResponse, final String clientIp) {
        if (!properties.isEnabled()) {
            return; // captcha disabled by config — skip verification
        }
        if (attemptService.isBlocked(clientIp)) {
            throw new CaptchaInvalidException("Client exceeded maximum number of failed attempts");
        }
        if (!StringUtils.hasLength(captchaResponse)) {
            // Enabled but no token supplied — this is the enforced-but-not-solved case (step 2e).
            throw new CaptchaInvalidException("Captcha response is required");
        }
        if (!RESPONSE_PATTERN.matcher(captchaResponse).matches()) {
            throw new CaptchaInvalidException("Captcha response contains invalid characters");
        }

        final URI verifyUri = URI.create(String.format(VERIFY_URL,
                properties.getSecret(), captchaResponse, clientIp == null ? "" : clientIp));
        final GoogleResponse googleResponse;
        try {
            googleResponse = restClient.get().uri(verifyUri).retrieve().body(GoogleResponse.class);
        } catch (RestClientException rce) {
            throw new CaptchaUnavailableException("Captcha verification unavailable. Please try again later.", rce);
        }

        if (googleResponse == null || !googleResponse.isSuccess()) {
            if (googleResponse != null && googleResponse.hasClientError()) {
                attemptService.failed(clientIp);
            }
            // error-codes tells you exactly why (invalid-keys = site/secret mismatch or wrong key type;
            // timeout-or-duplicate = token expired/reused; hostname-mismatch = domain not allowed).
            LOGGER.warn("reCAPTCHA rejected by Google: {}", googleResponse);
            throw new CaptchaInvalidException("reCaptcha was not successfully validated");
        }
        attemptService.succeeded(clientIp);
    }
}
