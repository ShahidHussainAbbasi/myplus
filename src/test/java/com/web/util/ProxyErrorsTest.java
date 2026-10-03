package com.web.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import com.web.error.DownstreamNotFoundException;

/** The service's own sentence reaches the screen, whichever exception carried it (MKT-1b gate finding). */
class ProxyErrorsTest {

    @Test
    void a404CarriedByDownstreamNotFoundKeepsTheServicesSentence() {
        Map<String, Object> out = ProxyErrors.failure(new DownstreamNotFoundException(
                "{\"success\":false,\"message\":\"That product is not in your catalogue.\",\"statusCode\":404}"));
        assertThat(out).containsEntry("success", false).containsEntry("message", "That product is not in your catalogue.");
    }

    @Test
    void aStatusCodeExceptionStillKeepsItsSentence() {
        Map<String, Object> out = ProxyErrors.failure(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                null, "{\"message\":\"Give a reason.\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(out).containsEntry("message", "Give a reason.");
    }

    @Test
    void aBodyWithoutAMessageAddsNoInventedOne() {
        assertThat(ProxyErrors.failure(new DownstreamNotFoundException("<html>404</html>"))).doesNotContainKey("message");
        assertThat(ProxyErrors.failure(new DownstreamNotFoundException(null))).doesNotContainKey("message");
    }
}
