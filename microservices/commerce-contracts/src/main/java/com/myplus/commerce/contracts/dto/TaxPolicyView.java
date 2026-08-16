package com.myplus.commerce.contracts.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A tenant's sales-tax policy, published so other channels can price the SAME WAY THE BOOKS DO.
 *
 * <p><b>Why a channel must ask.</b> Whether tax applies at all is a per-tenant switch that lives with the
 * books (business-service owns `tax_setting`). A channel that computes tax from the product's rate alone —
 * which the storefront checkout did — will happily show tax to a shop that has tax turned OFF, quote a total,
 * and then watch the invoice come back without it. Quoted 22, invoiced 20, and both sides individually
 * "correct".
 *
 * <p>This carries only the POLICY. The arithmetic is
 * {@code com.myplus.commerce.domain.TaxMath}, shared by every caller, so a rule change lands in one place.
 *
 * <p>Read-only and cheap: it is three fields describing configuration that changes at month-end, not per
 * request, so a caller on a hot path may safely cache it for a short while.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxPolicyView {

    /**
     * The master switch. When false the tenant charges no sales tax and every line is net — regardless of
     * what rate a product carries. This is the field the storefront's private tax engine did not know about.
     */
    private boolean enabled;

    /**
     * {@code EXCLUSIVE} (tax added on top) or {@code INCLUSIVE} (the price already contains it).
     *
     * <p>Carried as a STRING rather than the books' `TaxMode` enum on purpose: that enum is a JPA
     * {@code @Enumerated} mapped to a MySQL enum column, and moving it into a shared module to satisfy a
     * contract would couple the wire format to a schema migration. Callers compare against
     * {@link #isInclusive()} rather than the literal.
     */
    private String mode;

    /**
     * The tenant's fallback rate, applied when a product carries none. A product rate of 0/null means
     * "unset", not "zero-rated" — see {@code TaxMath.resolveRate}.
     */
    private BigDecimal defaultRate;

    /**
     * True when prices already contain the tax. Null/unknown reads as EXCLUSIVE, which is the app default.
     *
     * <p>{@code @JsonIgnore} because this is a READING of {@link #mode}, not a fourth field. Left visible it
     * would serialise an {@code "inclusive"} key the receiving side has no setter for — a wire field that
     * exists in one direction only, and one more thing that could drift out of step with the mode it derives
     * from. The contract carries the policy; the interpretation belongs to whoever holds it.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isInclusive() {
        return "INCLUSIVE".equalsIgnoreCase(mode);
    }
}
