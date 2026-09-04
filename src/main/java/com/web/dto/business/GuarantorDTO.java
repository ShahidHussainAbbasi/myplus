package com.web.dto.business;

import lombok.Data;

/**
 * R4 — one person standing behind a financed sale.
 *
 * <h3>⚠ TWIN of {@code com.myplus.business_service.dto.GuarantorDTO} — they change together</h3>
 * This monolith binds the sale and <b>re-serialises it</b> to business-service. A field that exists on only
 * one side is silently discarded in transit: the sale succeeds, the invoice is right, and the guarantor never
 * arrives. Nothing errors and no test fails unless one is written for it — which is why the gate has a case
 * asserting both guarantors survive the hop.
 */
@Data
public class GuarantorDTO {

    /** GUARANTOR (the default) or WITNESS. Only GUARANTOR rows count towards the shop's requirement. */
    private String role;

    private String name;
    private String cnic;
    private String contact;
    private String address;

    /** Set when the cashier recalled somebody this shop already knows. An index, never authority. */
    private Long customerId;
}
