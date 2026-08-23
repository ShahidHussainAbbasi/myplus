package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * OMS O7 D1b — what the sale path WOULD say about this basket, without writing anything.
 *
 * <h3>Why an answer and not an exception</h3>
 * The sale path signals refusal by throwing — {@code ValidationException} for margin,
 * {@code CreditConfirmationRequiredException} for credit — because there a refusal must stop a write. A dry run
 * has no write to stop, and an exception would force every caller to catch two unrelated types to render one
 * panel. So the same checks run and their refusals are reported as DATA.
 *
 * <h3>Advisory, and the wording has to say so</h3>
 * Between an amendment and the van there is a real gap: prices move, other orders consume the same credit,
 * costs change. Dispatch remains authoritative. A screen that presented this as final would be worse than no
 * check at all, because a reviewer who trusts it stops reading the dispatch failure.
 *
 * <h3>{@code blocked} is reported, not enforced</h3>
 * When the tenant's policy is {@code block} this comes back {@code blocked = true} and the caller still saves
 * the amendment. Refusing to save because a sale <em>would</em> fail later takes the decision away from the
 * person the review step exists to serve — D1 established that both booker and admin may revise. Tell them,
 * then let them choose.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyCheckResponse {

    /** True when nothing at all was raised — no warnings and no block. */
    private boolean ok;

    /**
     * True when the tenant's policy is {@code block} AND a rule fired, i.e. the real sale would be REFUSED.
     *
     * <p>Distinct from {@code ok} on purpose: a {@code warn}-policy tenant gets {@code ok=false, blocked=false},
     * which is "you should know" rather than "this cannot happen". Collapsing the two would either hide a real
     * refusal or cry wolf about an advisory note.
     */
    private boolean blocked;

    /**
     * Operator-facing sentences, exactly as the sale path phrases them.
     *
     * <p>Taken from the shared check methods rather than re-worded here, so the reviewer reads the same words
     * the cashier would. Two texts for one rule is how a support conversation stops being possible.
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /** What the basket comes to, as the SERVER computes it — never as the caller does. */
    private BigDecimal netTotal;

    /**
     * Margin on costed lines, or null when no line has a known cost.
     *
     * <p>Null is not zero. A shop that has never recorded a purchase has nothing to judge, and reporting 0
     * there would read as "no profit" and send someone looking for a pricing error that does not exist — the
     * same reason {@code assertMarginPolicy} excludes unknown-cost lines from BOTH sides of its sum.
     */
    private BigDecimal margin;

    /** What the customer would owe once this basket is billed; null for a walk-in or an uncapped account. */
    private BigDecimal projectedDue;

    /**
     * The customer's limit, or null when they are UNCAPPED.
     *
     * <p>Null, not zero — D2 established this: a false "0 of 0" trains a reader to ignore the warning.
     */
    private BigDecimal creditLimit;
}
