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

/**
 * Turns a failed sign-in into the login page's message (2026-10-14).
 *
 * <p>Branches on the TYPED failure ({@link SignInFailure}) and shows its message-bundle key — never on exception
 * text. The old version compared {@code exception.getMessage()} with literals ("Access removed", "locked") that
 * the provider had to spell identically, and hardcoded two English sentences that no translation reached.
 *
 * <p>The return page is chosen PER REQUEST. It used to be set with {@code setDefaultFailureUrl} on this singleton,
 * so two sign-ins failing at the same moment could send each other to the wrong page.
 */
@Component("authenticationFailureHandler")
public class CustomAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Autowired
    private MessageSource messages;

    @Autowired
    private LocaleResolver localeResolver;

    @Override
    public void onAuthenticationFailure(final HttpServletRequest request, final HttpServletResponse response,
                                        final AuthenticationException exception) throws IOException, ServletException {
        final Locale locale = localeResolver.resolveLocale(request);
        // Anything not raised by AuthServerAuthenticationProvider (e.g. the session-limit refusal) reads as a plain
        // refusal — as it always did.
        final SignInFailure failure = exception instanceof SignInFailedException f ? f.failure() : SignInFailure.BAD_CREDENTIALS;

        // The friendly, translated message replaces the exception the page reads; the entered username is kept so
        // the login page can prefill it (the person only re-enters password + code).
        request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION,
                messages.getMessage(failure.messageKey(), null, locale));
        request.getSession().setAttribute("LAST_USERNAME", request.getParameter("username"));
        getRedirectStrategy().sendRedirect(request, response, failure.failureUrl());
    }
}
