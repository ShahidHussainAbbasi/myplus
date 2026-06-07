package com.web.controller;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.persistence.model.User;
import com.service.IUserService;
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
import org.springframework.security.core.context.SecurityContextHolder;
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
    private IUserService userService;

    @Autowired
    private AuthServerClient authServerClient;

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

    // change user password (logged in)
    @RequestMapping(value = "/user/updatePassword", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse changeUserPassword(final Locale locale, @Valid PasswordDto passwordDto) {
        final User user = userService.findUserByEmail(((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getEmail());
        if (!userService.checkIfValidOldPassword(user, passwordDto.getOldPassword())) {
            throw new InvalidOldPasswordException();
        }
        userService.changeUserPassword(user, passwordDto.getNewPassword());
        return new GenericResponse(messages.getMessage("message.updatePasswordSuc", null, locale));
    }

    @RequestMapping(value = "/user/update/2fa", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse modifyUser2FA(@RequestParam("use2FA") final boolean use2FA) throws UnsupportedEncodingException {
        final User user = userService.updateUser2FA(use2FA);
        if (use2FA) {
            return new GenericResponse(userService.generateQRUrl(user));
        }
        return null;
    }
}
