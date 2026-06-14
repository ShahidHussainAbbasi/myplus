package com.myplus.campaign.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Inbound demo lead (the monolith validates first and proxies this). */
@Data
public class DemoRequestRequest {
    @NotBlank
    private String fullName;
    @NotBlank
    @Email
    private String workEmail;
    private String company;
    private String country;
    private String phone;
    private String interest;
    private String companySize;
    private String message;
    private String timezone;
    private String preferredDate;
    private String locale;
    private String source;
}
