package com.web.controller;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.security.TokenStore;
import com.service.PasswordResetFacade;
import com.web.dto.PasswordDto;
import com.web.dto.UserDto;
import com.web.error.InvalidOldPasswordException;
import com.web.error.UserAlreadyExistException;
import com.web.util.AuthServerClient;
import com.web.util.GenericResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;

@Controller
public class RegistrationController {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AuthServerClient authServerClient;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private PasswordResetFacade passwordResetFacade;

    @Autowired
    private MessageSource messages;

    public RegistrationController() {
        super();
    }

    // Registration — delegated to the auth-service (single identity store). The auth-service
    // creates the user (disabled until verified) and sends the verification email itself.
    @RequestMapping(value = "/user/registration", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse registerUserAccount(@Valid final UserDto accountDto, final HttpServletRequest request) {
        LOGGER.debug("Registering user account: {}", accountDto.getEmail());
        try {
            authServerClient.register(accountDto.getFirstName(), accountDto.getLastName(),
                    accountDto.getEmail(), accountDto.getPassword(), null, null);
        } catch (HttpStatusCodeException ex) {
            // Most common cause from the form is a duplicate email.
            throw new UserAlreadyExistException("Registration failed");
        }
        return new GenericResponse("success");
    }

    // Reset password — delegated to the auth-service (it owns the user store and the reset token).
    @RequestMapping(value = "/user/resetPassword", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse resetPassword(final HttpServletRequest request, @RequestParam("email") final String userEmail) {
        passwordResetFacade.requestReset(userEmail);
        return new GenericResponse(messages.getMessage("message.resetPasswordEmail", null, request.getLocale()));
    }

    // Landing page from the reset email: render the new-password form carrying the token. The token
    // itself is the credential — no login/session is required (the auth-service validates it on submit).
    @RequestMapping(value = "/user/changePassword", method = RequestMethod.GET)
    public String showChangePasswordPage(final Model model, @RequestParam("token") final String token) {
        model.addAttribute("token", token);
        return "updatePassword";
    }

    @RequestMapping(value = "/user/savePassword", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse savePassword(final Locale locale, @RequestParam("token") final String token, @Valid PasswordDto passwordDto) {
        passwordResetFacade.completeReset(token, passwordDto.getNewPassword());
        return new GenericResponse(messages.getMessage("message.resetPasswordSuc", null, locale));
    }

    // change user password (logged in) — delegated to the auth-service (it owns the identity store).
    @RequestMapping(value = "/user/updatePassword", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse changeUserPassword(final Locale locale, @Valid PasswordDto passwordDto) {
        try {
            authServerClient.changePassword(tokenStore.getAccessToken(), passwordDto.getOldPassword(), passwordDto.getNewPassword());
        } catch (HttpStatusCodeException e) {
            // auth-service returns 4xx when the current password is wrong / new password rejected.
            throw new InvalidOldPasswordException();
        }
        GenericResponse response = new GenericResponse();
        response.setMessage(messages.getMessage("message.updatePasswordSuc", null, locale));
        return response;
    }

    // 2FA enrolment (setup -> scan QR -> verify) and disable, all delegated to the auth-service.
    @RequestMapping(value = "/user/2fa/setup", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse setup2FA() {
        // message carries the otpauth:// provisioning URI; the page renders it as a QR for the authenticator app.
        GenericResponse response = new GenericResponse();
        response.setMessage(authServerClient.setup2fa(tokenStore.getAccessToken()));
        return response;
    }

    @RequestMapping(value = "/user/2fa/verify", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse verify2FA(final Locale locale, @RequestParam("code") final String code) {
        GenericResponse response = new GenericResponse();
        if (!authServerClient.verify2fa(tokenStore.getAccessToken(), code)) {
            response.setError("Invalid2FACode");
            response.setMessage(messages.getMessage("message.error", null, "Invalid code", locale));
            return response;
        }
        markPrincipal2FA(true);
        response.setMessage(messages.getMessage("message.updateUser2faSuccess", null, "Two-step verification enabled", locale));
        return response;
    }

    @RequestMapping(value = "/user/2fa/disable", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse disable2FA() {
        authServerClient.disable2fa(tokenStore.getAccessToken());
        markPrincipal2FA(false);
        GenericResponse response = new GenericResponse();
        response.setMessage("success");
        return response;
    }

    /** Reflect the 2FA change on the in-session principal so the console renders the right state without re-login. */
    private void markPrincipal2FA(boolean using2FA) {
        Object principal = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        if (principal instanceof com.persistence.model.User u) {
            u.setUsing2FA(using2FA);
        }
    }
}
