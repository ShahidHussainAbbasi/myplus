package com.web.error;

/**
 * Slice 3.1b — raised when a microservice answers a proxied call with {@code 404}.
 *
 * <p>A downstream 404 is an <b>answer</b>, not a failure. Before this existed it propagated to
 * {@link RestResponseEntityExceptionHandler}'s catch-all and came back as a generic {@code 500}
 * "Error Occurred" — the same class of defect slice 2.1 recorded as standard D3d, which had been fixed
 * for logging but not for this status.
 *
 * <p>It matters most for the guardian portal: {@code PortalScopeFilter} refuses a portal principal with a
 * 404 precisely so the caller learns nothing. Relaying that as a 500 would say "something broke" — untrue,
 * and more informative to a prober than the deliberate silence the 404 was chosen for.
 *
 * @see DownstreamNotFoundAdvice
 */
public class DownstreamNotFoundException extends RuntimeException {

    /** The downstream body, passed through unchanged so a service's own message is not invented over. */
    private final String body;

    public DownstreamNotFoundException(String body) {
        super("Not found");
        this.body = body;
    }

    public String getBody() {
        return body;
    }
}
