package com.web.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import com.captcha.CaptchaSettings;

/**
 * Exposes UI feature flags to every rendered view — including static view-controllers (login,
 * registration, console, forgetPassword) which {@code @ControllerAdvice @ModelAttribute} does not reach.
 * Templates read {@code ${captchaEnabled}} / {@code ${captchaSiteKey}} / {@code ${twoFaEnabled}}.
 * Both flags are env-overridable ({@code CAPTCHA_ENABLED}/{@code TWOFA_ENABLED}).
 */
@Component
public class FeatureFlagsInterceptor implements HandlerInterceptor {

    @Value("${app.captcha.enabled:false}")
    private boolean captchaEnabled;

    @Value("${app.twofa.enabled:true}")
    private boolean twoFaEnabled;

    @Autowired
    private CaptchaSettings captchaSettings;

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView mav) {
        if (mav == null || mav.getViewName() == null || mav.getViewName().startsWith("redirect:")) {
            return; // JSON responses / redirects have no view model to populate
        }
        mav.addObject("captchaEnabled", captchaEnabled);
        mav.addObject("captchaSiteKey", captchaSettings.getSite());
        mav.addObject("twoFaEnabled", twoFaEnabled);
    }
}
