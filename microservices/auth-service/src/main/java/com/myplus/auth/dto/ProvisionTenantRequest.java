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
}
