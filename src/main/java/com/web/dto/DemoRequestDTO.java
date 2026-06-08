package com.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.validation.ValidEmail;

/**
 * Payload for a public "Book a Demo" submission. Designed for global use: free-form country/phone,
 * an optional message, and a {@link #website} honeypot for bots. {@code consent} is required so the
 * capture is compliant with contact-consent regimes (GDPR et al.).
 */
public class DemoRequestDTO {

    @NotBlank
    @Size(max = 120)
    private String fullName;

    @NotBlank
    @ValidEmail
    @Size(max = 160)
    private String workEmail;

    @NotBlank
    @Size(max = 160)
    private String company;

    @NotBlank
    @Size(max = 80)
    private String country;

    @Size(max = 40)
    private String phone;

    @Size(max = 60)
    private String interest;

    @Size(max = 40)
    private String companySize;

    @Size(max = 2000)
    private String message;

    @Size(max = 80)
    private String timezone;

    @Size(max = 40)
    private String preferredDate;

    @Size(max = 40)
    private String locale;

    @Size(max = 40)
    private String source;

    /** Honeypot — must stay empty. Real users never see this field; bots fill it. */
    @Size(max = 0, message = "Bot detected")
    private String website;

    @AssertTrue(message = "Please accept the contact consent to continue.")
    private boolean consent;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getWorkEmail() { return workEmail; }
    public void setWorkEmail(String workEmail) { this.workEmail = workEmail; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getInterest() { return interest; }
    public void setInterest(String interest) { this.interest = interest; }

    public String getCompanySize() { return companySize; }
    public void setCompanySize(String companySize) { this.companySize = companySize; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getPreferredDate() { return preferredDate; }
    public void setPreferredDate(String preferredDate) { this.preferredDate = preferredDate; }

    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public boolean isConsent() { return consent; }
    public void setConsent(boolean consent) { this.consent = consent; }
}
