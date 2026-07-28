package com.web.util;

import java.util.Locale;

import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * {@code ?lang=} switch restricted to the languages we actually ship.
 *
 * {@code setIgnoreInvalidLocale(true)} alone is not enough: it only rejects MALFORMED tags, so
 * {@code ?lang=zz} — well-formed but with no bundle — would still be accepted and stored in the
 * locale cookie, after which every page renders raw message keys until the user clears it.
 *
 * An unsupported tag is rejected by THROWING rather than by returning {@code null}: the parent
 * catches IllegalArgumentException and — with {@code ignoreInvalidLocale} set — leaves the current
 * locale alone, which is the behaviour we want. Returning null would instead hand null to
 * {@code CookieLocaleResolver.setLocale}, which clears the cookie and drops the user back to
 * English; a mistyped link should not silently reset a visitor's chosen language.
 */
public class SupportedLocaleChangeInterceptor extends LocaleChangeInterceptor {

    @Override
    protected Locale parseLocaleValue(String value) {
        final Locale parsed = super.parseLocaleValue(value);
        if (parsed == null) {
            return null;   // parent's own "no value" path
        }
        if (!SupportedLanguage.isSupported(parsed.getLanguage())) {
            throw new IllegalArgumentException("Unsupported language tag: " + value);
        }
        // Normalise ar-EG / ur-PK down to the tag we actually ship a bundle for.
        return Locale.forLanguageTag(SupportedLanguage.from(parsed).getTag());
    }
}
