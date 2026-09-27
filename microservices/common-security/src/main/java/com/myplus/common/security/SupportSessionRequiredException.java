package com.myplus.common.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * E5b — a platform operator named another business without an open support session covering it.
 *
 * <p>Thrown instead of answering about the operator's OWN organization. Until E5b {@link CurrentUser#organizationIdFor}
 * fell back to the caller's org for everyone — right for a tenant (their parameter is ignored, anti-IDOR), wrong for
 * an operator: the console printed the platform org's trail, counts and money under the other business's name, and
 * "Clear flags" wrote to the operator's own catalogue.
 *
 * <p>An {@link AccessDeniedException}, so anything that already turns access denied into a 403 still does. The shared
 * exception handlers render this one with its sentence and {@link #CODE}, so the console can tell "needs a support
 * session" from "the service is down".
 */
public class SupportSessionRequiredException extends AccessDeniedException {

    /** Stable machine-readable marker, carried as {@code data.code} in the 403 body. */
    public static final String CODE = "SUPPORT_SESSION_REQUIRED";

    private final Long organizationId;

    public SupportSessionRequiredException(Long organizationId) {
        super("Open a support session for this business to see or change its records.");
        this.organizationId = organizationId;
    }

    /** The business the operator asked about. */
    public Long getOrganizationId() {
        return organizationId;
    }
}
