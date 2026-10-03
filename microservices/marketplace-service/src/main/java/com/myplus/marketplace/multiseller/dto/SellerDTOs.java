package com.myplus.marketplace.multiseller.dto;

import java.time.LocalDateTime;
import java.util.List;

/** MKT-0a — the seller onboarding wire shapes. DTOs only; no entity crosses the boundary (§1.5). */
public final class SellerDTOs {

    private SellerDTOs() {
    }

    /** POST /mkt/seller/agreements. {@code displayName} is required for a first application or a re-application. */
    public record AcceptRequest(String version, String displayName) {
    }

    public record Agreement(String code, String version, Long acceptedBy, LocalDateTime acceptedAt) {
    }

    public record Account(Long organizationId, String displayName, String status, String statusReason,
            LocalDateTime appliedAt, LocalDateTime decidedAt, Integer version) {
    }

    /**
     * What the seller screen renders. {@code canSell} is computed here, once, so the screen never re-derives it
     * from three fields and disagrees with the server about whether a write will be accepted.
     */
    public record SellerView(boolean capabilityOn, String requiredVersion, List<Agreement> agreements,
            boolean agreementsCurrent, Account account, boolean canSell) {
    }

    /** The answer to accepting: carries version / acceptedBy / acceptedAt (the gate reads these keys). */
    public record Acceptance(String version, Long acceptedBy, LocalDateTime acceptedAt, Account account) {
    }

    /** POST /mkt/operator/sellers/{org}/decision. decision: APPROVE | REJECT | SUSPEND | REINSTATE. */
    public record DecisionRequest(String decision, String reason, Integer version) {
    }
}
