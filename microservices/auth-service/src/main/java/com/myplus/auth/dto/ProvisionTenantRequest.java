package com.myplus.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Operator request to onboard a real client without a redeploy (slice 32) — the SaaS-correct replacement
 * for seeding a customer in {@code SetupDataLoader}. Creates the owner user (no known password — they set
 * one via a reset email) + their organization on a chosen plan. SUPER/ADMIN only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionTenantRequest {
    @NotBlank
    private String firstName;
    @NotBlank
    private String lastName;
    @NotBlank
    @Email
    private String email;
    private String phone;
    /** Module this tenant belongs to (BUSINESS/EDUCATION/...). Defaults to BUSINESS. */
    private String userType;
    @NotBlank
    private String organizationName;
    /** TRIAL | FREE | PRO | DEMO. Defaults to PRO (operator-onboarded paying client). */
    private String plan;
    /**
     * ONB-1 — what KIND of business this is: retail · pharmacy · distribution · storefront · general.
     *
     * <p><b>Mandatory, deliberately.</b> Without it a tenant has no {@code org.shape} row,
     * {@code Shape.byCode(null)} resolves to {@code GENERAL}, and GENERAL's preset is EVERY capability — so
     * the customer meets the whole product and has to subtract. A pesticide dealer was shown installments and
     * serial/IMEI for exactly this reason.
     *
     * <p>An optional field with a {@code general} default is a field that gets skipped, and the defect returns
     * for the next customer. Xero and QuickBooks both make this required at company setup, for the same
     * reason: "everything on" is never the right answer for a real business.
     */
    @NotBlank
    private String shape;
}
