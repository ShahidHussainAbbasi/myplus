package com.security;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

@Component("authenticationFailureHandler")
public class CustomAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Autowired
    private MessageSource messages;

    @Autowired
    private LocaleResolver localeResolver;

    @Override
    public void onAuthenticationFailure(final HttpServletRequest request, final HttpServletResponse response, final AuthenticationException exception) throws IOException, ServletException {
        final Locale locale = localeResolver.resolveLocale(request);
        final String reason = exception.getMessage() == null ? "" : exception.getMessage();

        String errorMessage = messages.getMessage("message.badCredentials", null, locale);
        // Default: ordinary credential failure.
        String failureUrl = "/login?error=true";

        if (reason.equalsIgnoreCase("Verification code required")) {
            // 2FA is enabled but no code was supplied — prompt for it (not an error).
            errorMessage = "Enter the verification code from your authenticator app.";
            failureUrl = "/login?twofa=true";
        } else if (reason.equalsIgnoreCase("Invalid 2FA code")
                || reason.toLowerCase(locale).contains("verfication code")
                || reason.toLowerCase(locale).contains("verification code")) {
            errorMessage = "Invalid verification code. Please try again.";
            failureUrl = "/login?twofa=true";
        } else if (reason.equalsIgnoreCase("User is disabled")) {
            errorMessage = messages.getMessage("auth.message.disabled", null, locale);
        } else if (reason.equalsIgnoreCase("User account has expired")) {
            errorMessage = messages.getMessage("auth.message.expired", null, locale);
        } else if (reason.equalsIgnoreCase("blocked")) {
            errorMessage = messages.getMessage("auth.message.blocked", null, locale);
        }

        setDefaultFailureUrl(failureUrl);
        super.onAuthenticationFailure(request, response, exception);

        // Overwrite the stored exception with a friendly message, and remember the entered username
        // so the login page can prefill it (the user only re-enters password + code).
        request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, errorMessage);
        request.getSession().setAttribute("LAST_USERNAME", request.getParameter("username"));
    }
}