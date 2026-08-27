package com.myplus.marketplace.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.client.RestClientResponseException;

/**
 * Relay the reason a downstream service refused, instead of flattening it.
 *
 * <h3>Why this exists</h3>
 * business-service and inventory-service refuse with a specific, actionable sentence — <i>"Not enough sellable
 * stock — '5CC CLAINIC': only 0 sellable, 7 requested. Expired or held stock is not sellable; add a fresh batch
 * to sell more."</i> That sentence travels in the error body. If the caller lets the
 * {@code HttpServerErrorException} escape instead of reading it, {@code GlobalExceptionHandler} turns the whole
 * thing into a 500 and the operator is told <i>"Something went wrong. Please try again."</i> — advice that is
 * both useless and wrong, because trying again will fail identically until someone fixes the stock.
 *
 * <p>{@code OrderService.checkoutFailureMessage} already did this for the storefront shopper. Dispatch did not,
 * which is why a perfectly clear refusal reached the packer as a generic 500 (production, 2026-08-27, order 8).
 * One definition now, so the next call site cannot forget: the rule is that a downstream's REASON belongs to
 * whoever has to act on it.
 *
 * <p>Note this is the same failure the codebase has already recorded twice — {@code GatewayClient} discarding
 * downstream errors, and {@code GlobalExceptionHandler} previously logging nothing at all. Losing the reason at
 * a service boundary is this system's most repeated mistake.
 */
public final class DownstreamMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DownstreamMessage() {}

    /**
     * The {@code message} a downstream put in its error body, or {@code null} when there isn't one.
     *
     * <p>Never throws: a non-JSON body, a proxy's HTML error page or a truncated response all yield
     * {@code null} so the caller falls back to its own wording. Losing the detail is acceptable; losing the
     * original failure because the extractor blew up is not.
     */
    public static String of(Throwable failure) {
        if (!(failure instanceof RestClientResponseException http)) return null;
        try {
            String body = http.getResponseBodyAsString();
            if (body == null || body.isBlank()) return null;
            JsonNode node = MAPPER.readTree(body);
            String msg = node.path("message").asText(null);
            return (msg == null || msg.isBlank()) ? null : msg;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The downstream's reason, or {@code fallback} when it did not give a readable one. */
    public static String orElse(Throwable failure, String fallback) {
        String msg = of(failure);
        return msg != null ? msg : fallback;
    }
}
