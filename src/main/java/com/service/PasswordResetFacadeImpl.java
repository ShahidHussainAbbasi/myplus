package com.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import com.web.error.PasswordResetException;
import com.web.util.AuthServerClient;

/**
 * Delegates the forget/reset password flow to the auth-service via {@link AuthServerClient}.
 * Centralizes the error policy: silent on "forgot" (never reveal whether an email exists), and a
 * translated, user-facing {@link PasswordResetException} on a failed reset.
 */
@Service
public class PasswordResetFacadeImpl implements PasswordResetFacade {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AuthServerClient authServerClient;

    @Autowired
    private MessageSource messages;

    @Override
    public void requestReset(final String email) {
        try {
            authServerClient.forgotPassword(email);
        } catch (Exception ex) {
            // Swallow transport/404 errors so we never reveal whether an address is registered.
            LOGGER.debug("forgot-password delegation for {} returned: {}", email, ex.getMessage());
        }
    }

    @Override
    public void completeReset(final String token, final String newPassword) {
        try {
            authServerClient.resetPassword(token, newPassword);
        } catch (HttpStatusCodeException ex) {
            throw new PasswordResetException(
                    messages.getMessage("auth.message.invalidToken", null, LocaleContextHolder.getLocale()), ex);
        }
    }
}
