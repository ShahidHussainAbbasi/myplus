package com.web.util;

import java.util.Arrays;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Exposes the active language to every rendered view — including the static view-controllers
 * (login, registration, forgetPassword…) that {@code @ControllerAdvice @ModelAttribute} does not
 * reach. Same mechanism as {@link FeatureFlagsInterceptor}.
 *
 * Templates read:
 * <ul>
 *   <li>{@code ${htmlLang}} → the {@code lang} attribute on &lt;html&gt;</li>
 *   <li>{@code ${htmlDir}}  → {@code ltr} or {@code rtl}; drives the whole RTL stylesheet</li>
 *   <li>{@code ${isRtl}}    → convenience boolean, e.g. to load the Arabic/Urdu webfont only when needed</li>
 *   <li>{@code ${currentLang}} / {@code ${supportedLanguages}} → the language switcher fragment</li>
 * </ul>
 *
 * Putting direction in the model rather than deriving it in each template means a page can never
 * disagree with the resolved locale — the classic cause of an RTL page rendering LTR.
 */
@Component
public class LocaleInterceptor implements HandlerInterceptor {

    /** Only this key subset is exposed to the browser. */
    private static final String JS_PREFIX = "ui.js.";

    @Autowired
    private MessageSource messageSource;

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView mav) {
        if (mav == null || mav.getViewName() == null || mav.getViewName().startsWith("redirect:")) {
            return; // JSON responses / redirects have no view model to populate
        }

        final Locale locale = RequestContextUtils.getLocale(request);
        final SupportedLanguage lang = SupportedLanguage.from(locale);

        mav.addObject("htmlLang", lang.getTag());
        mav.addObject("htmlDir", lang.getDirection());
        mav.addObject("isRtl", lang.isRtl());
        // Separate from isRtl: Hindi is left-to-right but still needs a Devanagari webfont.
        mav.addObject("langWebfont", lang.getWebfont());
        mav.addObject("currentLang", lang);
        mav.addObject("supportedLanguages", Arrays.asList(SupportedLanguage.values()));

        // Strings the module scripts need (alerts, confirms, empty states). Only the ui.js.* subset
        // crosses to the browser — server-only copy (validation text, emails) never ships.
        if (messageSource instanceof JsMessageSource) {
            mav.addObject("jsMessages",
                    ((JsMessageSource) messageSource).getMessagesWithPrefix(JS_PREFIX, locale));
        }
    }
}
