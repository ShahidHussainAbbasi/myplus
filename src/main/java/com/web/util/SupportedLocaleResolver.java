package com.web.util;

import java.util.Enumeration;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.i18n.CookieLocaleResolver;

/**
 * Picks the language for a visitor who has not chosen one yet.
 *
 * Resolution order, first match wins:
 * <ol>
 *   <li><b>Explicit choice</b> — the locale cookie, set when the visitor uses the language switcher.
 *       A person's own choice always outranks any guess.</li>
 *   <li><b>Region / browser</b> — the {@code Accept-Language} header, which carries the language list
 *       from the visitor's OS and browser settings, in their preferred order.</li>
 *   <li><b>Organization default</b> — {@code app.locale.default}, what the owner set for the tenant.</li>
 *   <li><b>English.</b></li>
 * </ol>
 *
 * Step 2 is the "automatic by region" behaviour: before this, {@code CookieLocaleResolver} was
 * constructed with {@code setDefaultLocale(ENGLISH)}, so a first-time visitor in Karachi or Cairo got
 * an English page no matter what their browser asked for, and had to find the switcher to fix it.
 *
 * Accept-Language is used rather than IP geolocation deliberately: it states what the person wants to
 * READ, needs no third-party lookup service on the request path, and does not treat an IP address —
 * which travels, and is often a VPN — as evidence about a person. A traveller keeps their language.
 */
public class SupportedLocaleResolver extends CookieLocaleResolver {

    private final Locale organizationDefault;

    public SupportedLocaleResolver(String cookieName, String organizationDefaultTag) {
        super(cookieName);
        this.organizationDefault = SupportedLanguage.isSupported(organizationDefaultTag)
                ? Locale.forLanguageTag(organizationDefaultTag)
                : Locale.ENGLISH;

        // Spring 6.2 resolves the no-cookie case through this FUNCTION, not through the older
        // protected determineDefaultLocale() hook — overriding that method compiles fine but is
        // never called, leaving the stock behaviour (return request.getLocale() verbatim). That
        // would hand back an unshipped locale such as de_DE and render raw message keys.
        setDefaultLocaleFunction(request -> {
            final Locale fromHeader = firstSupportedFrom(request);
            return fromHeader != null ? fromHeader : organizationDefault;
        });
    }

    /**
     * The visitor's most-preferred language that we actually ship.
     *
     * {@code getLocales()} yields the header in descending q-value order, so a browser asking for
     * "ur, en;q=0.8" gets Urdu while one asking for "de, fr;q=0.7" gets French rather than being
     * dropped to English on the first miss. Regional variants resolve through
     * {@link SupportedLanguage#from} — ar-EG is Arabic.
     */
    private Locale firstSupportedFrom(HttpServletRequest request) {
        if (request.getHeader("Accept-Language") == null) {
            return null;   // no signal at all (some bots, some API clients)
        }

        final Enumeration<Locale> requested = request.getLocales();
        while (requested != null && requested.hasMoreElements()) {
            final Locale candidate = requested.nextElement();
            if (SupportedLanguage.isSupported(candidate.getLanguage())) {
                return Locale.forLanguageTag(SupportedLanguage.from(candidate).getTag());
            }
        }
        return null;
    }

    /**
     * Persist an explicit choice. Guarded so a caller cannot store a locale we have no bundle for —
     * that would render raw message keys on every later page.
     */
    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        if (locale != null && !SupportedLanguage.isSupported(locale.getLanguage())) {
            return;
        }
        super.setLocale(request, response, locale);
    }
}
