package com.web.controller;

import com.captcha.ICaptchaService;
import com.web.dto.UserDto;
import com.web.error.UserAlreadyExistException;
import com.web.util.AuthServerClient;
import com.web.util.GenericResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Controller
public class RegistrationCaptchaController {
    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private AuthServerClient authServerClient;

    @Autowired
    private ICaptchaService captchaService;

    public RegistrationCaptchaController() {
        super();
    }

    // Registration (with reCAPTCHA) — delegated to the auth-service.
    @RequestMapping(value = "/user/registrationCaptcha", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse captchaRegisterUserAccount(@Valid final UserDto accountDto, final HttpServletRequest request) {
        final String response = request.getParameter("g-recaptcha-response");
        captchaService.processResponse(response);

        LOGGER.debug("Registering user account: {}", accountDto.getEmail());
        try {
            authServerClient.register(accountDto.getFirstName(), accountDto.getLastName(),
                    accountDto.getEmail(), accountDto.getPassword(), null, accountDto.getUserType(),
                    accountDto.getOrganizationName());
        } catch (HttpStatusCodeException ex) {
            throw new UserAlreadyExistException("Registration failed");
        }
        return new GenericResponse("success");
    }
}
