package com.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.web.util.SupportedLocaleResolver;

/**
 * Region-based language selection for a visitor who has not chosen one yet.
 *
 * The behaviour these lock down is the fix for a first-time visitor in Lahore or Cairo being served
 * English regardless of what their browser asked for — the resolver used to be constructed with a
 * hardcoded ENGLISH default.
 */
class SupportedLocaleResolverTest {

    private static final String COOKIE = "MYPLUS_LOCALE";

    private MockHttpServletRequest requestAccepting(String acceptLanguage) {
        final MockHttpServletRequest req = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            req.addHeader("Accept-Language", acceptLanguage);
            // MockHttpServletRequest derives getLocales() from preferredLocales, not the raw header.
            req.setPreferredLocales(java.util.Arrays.stream(acceptLanguage.split(","))
                    .map(s -> s.split(";")[0].trim())
                    .map(Locale::forLanguageTag)
                    .toList());
        }
        return req;
    }

    private Locale resolve(String acceptLanguage, String orgDefault) {
        return new SupportedLocaleResolver(COOKIE, orgDefault)
                .resolveLocale(requestAccepting(acceptLanguage));
    }

    @Test
    void followsTheBrowsersPreferredLanguage() {
        assertEquals("ur", resolve("ur-PK,ur;q=0.9,en;q=0.8", "en").getLanguage(),
                "a visitor whose browser asks for Urdu must not be served English");
        assertEquals("ar", resolve("ar-EG,ar;q=0.9", "en").getLanguage());
        assertEquals("fr", resolve("fr-FR,fr;q=0.9", "en").getLanguage());
        assertEquals("es", resolve("es-MX,es;q=0.9", "en").getLanguage());
        assertEquals("hi", resolve("hi-IN,hi;q=0.9,en;q=0.8", "en").getLanguage());
    }

    @Test
    void skipsLanguagesWeDoNotShipAndTakesTheNextPreference() {
        // German first, French second — French is shipped, so the visitor gets French rather than
        // being dropped to English on the first miss.
        assertEquals("fr", resolve("de-DE,de;q=0.9,fr;q=0.7", "en").getLanguage());
    }

    @Test
    void fallsBackToTheOrganizationDefaultWhenNothingMatches() {
        assertEquals("ur", resolve("de-DE,de;q=0.9", "ur").getLanguage(),
                "an org that set Urdu should show Urdu to a browser asking for German");
    }

    @Test
    void fallsBackToEnglishWhenThereIsNoSignalAtAll() {
        assertEquals("en", resolve(null, "en").getLanguage());
    }

    @Test
    void anUnsupportedOrganizationDefaultDoesNotBreakThePage() {
        // A bad app.locale.default must degrade to English, never to a locale with no bundle.
        assertEquals("en", resolve(null, "zz").getLanguage());
        assertEquals("en", resolve(null, null).getLanguage());
    }

    @Test
    void theVisitorsOwnChoiceOutranksTheirBrowser() {
        final SupportedLocaleResolver resolver = new SupportedLocaleResolver(COOKIE, "en");
        final MockHttpServletRequest req = requestAccepting("fr-FR,fr;q=0.9");
        req.setCookies(new jakarta.servlet.http.Cookie(COOKIE, "ar"));

        assertEquals("ar", resolver.resolveLocale(req).getLanguage(),
                "an explicit switch must survive a browser that prefers something else");
    }
}
