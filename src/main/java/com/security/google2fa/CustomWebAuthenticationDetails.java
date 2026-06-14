package com.security.google2fa;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.authentication.WebAuthenticationDetails;

public class CustomWebAuthenticationDetails extends WebAuthenticationDetails {

    private static final long serialVersionUID = 1L;

    private final String verificationCode;
    private final String captchaResponse;

    public CustomWebAuthenticationDetails(HttpServletRequest request) {
        super(request);
        verificationCode = request.getParameter("code");
        captchaResponse = request.getParameter("g-recaptcha-response");
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public String getCaptchaResponse() {
        return captchaResponse;
    }
}