package com.myplus.market.entity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A seller's standing on the marketplace (MP-0b). The State pattern in the codebase's style: a transition table
 * read by one method, so no caller decides on its own which moves are legal (cf. marketplace-service
 * {@code FulfilmentStatus.ALLOWED}).
 *
 * <pre>
 *   PENDING_REVIEW → ACTIVE | REJECTED | WITHDRAWN
 *   REJECTED       → PENDING_REVIEW   (the seller re-applies)
 *   ACTIVE         → SUSPENDED | WITHDRAWN
 *   SUSPENDED      → ACTIVE           (operator reinstates)
 *   WITHDRAWN      → PENDING_REVIEW   (the seller applies again — the operator decides again)
 * </pre>
 *
 * Only ACTIVE may publish offers or receive orders (MP-2 onwards). A SUSPENDED seller cannot withdraw: leaving
 * and re-applying must not be a way round a suspension. From MP-5 a withdrawal is also refused while the seller
 * has open orders.
 */
public enum SellerStatus {
    PENDING_REVIEW, ACTIVE, REJECTED, SUSPENDED, WITHDRAWN;

    private static final Map<SellerStatus, Set<SellerStatus>> ALLOWED = Map.of(
            PENDING_REVIEW, EnumSet.of(ACTIVE, REJECTED, WITHDRAWN),
            REJECTED, EnumSet.of(PENDING_REVIEW),
            ACTIVE, EnumSet.of(SUSPENDED, WITHDRAWN),
            SUSPENDED, EnumSet.of(ACTIVE),
            WITHDRAWN, EnumSet.of(PENDING_REVIEW));

    public boolean canMoveTo(SellerStatus next) {
        return next != null && ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
