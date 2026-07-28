package com.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.web.util.SupportedLanguage;

/**
 * Pure-logic tests for the language layer — no Spring context, so they run on every {@code mvn test}.
 *
 * The bundle-completeness test is the valuable one: a missing key does not fail at build time, it
 * fails as a raw message code shown to a user in production. This turns that into a build failure.
 */
class SupportedLanguageTest {

    @Test
    void resolvesEachShippedLanguage() {
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.from(Locale.forLanguageTag("en")));
        assertEquals(SupportedLanguage.FRENCH,  SupportedLanguage.from(Locale.forLanguageTag("fr")));
        assertEquals(SupportedLanguage.SPANISH, SupportedLanguage.from(Locale.forLanguageTag("es")));
        assertEquals(SupportedLanguage.HINDI,   SupportedLanguage.from(Locale.forLanguageTag("hi")));
        assertEquals(SupportedLanguage.ARABIC,  SupportedLanguage.from(Locale.forLanguageTag("ar")));
        assertEquals(SupportedLanguage.URDU,    SupportedLanguage.from(Locale.forLanguageTag("ur")));
    }

    @Test
    void regionalVariantsResolveToTheirLanguage() {
        // A browser sending ar-EG or ur-PK must get Arabic/Urdu, not a silent drop to English.
        assertEquals(SupportedLanguage.ARABIC, SupportedLanguage.from(Locale.forLanguageTag("ar-EG")));
        assertEquals(SupportedLanguage.ARABIC, SupportedLanguage.from(new Locale("ar", "SA")));
        assertEquals(SupportedLanguage.URDU,   SupportedLanguage.from(Locale.forLanguageTag("ur-PK")));
        assertEquals(SupportedLanguage.FRENCH, SupportedLanguage.from(Locale.CANADA_FRENCH));
        // Spanish has far more speakers outside Spain than in it — every variant must resolve.
        assertEquals(SupportedLanguage.SPANISH, SupportedLanguage.from(Locale.forLanguageTag("es-MX")));
        assertEquals(SupportedLanguage.SPANISH, SupportedLanguage.from(new Locale("es", "ES")));
        assertEquals(SupportedLanguage.HINDI,   SupportedLanguage.from(Locale.forLanguageTag("hi-IN")));
    }

    @Test
    void unsupportedAndNullFallBackToEnglish() {
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.from(Locale.GERMAN));
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.from(Locale.forLanguageTag("zz")));
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.from(null));
    }

    @Test
    void onlyShippedTagsAreAccepted() {
        assertTrue(SupportedLanguage.isSupported("ar"));
        assertTrue(SupportedLanguage.isSupported("es"));
        assertTrue(SupportedLanguage.isSupported("hi"));
        assertTrue(SupportedLanguage.isSupported("UR"), "matching must be case-insensitive");
        assertFalse(SupportedLanguage.isSupported("zz"));
        assertFalse(SupportedLanguage.isSupported(null));
    }

    /**
     * Font need is a per-language property, NOT something derived from direction: Hindi is
     * left-to-right yet still needs a Devanagari face, which a direction-keyed rule would miss.
     */
    @Test
    void onlyNonLatinScriptsDeclareAWebfont() {
        assertTrue(SupportedLanguage.HINDI.hasWebfont(), "Devanagari is not covered by the UI font");
        assertFalse(SupportedLanguage.HINDI.isRtl(), "Hindi is left-to-right");

        assertTrue(SupportedLanguage.ARABIC.hasWebfont());
        assertTrue(SupportedLanguage.URDU.hasWebfont());

        assertFalse(SupportedLanguage.ENGLISH.hasWebfont(), "Latin script needs no extra download");
        assertFalse(SupportedLanguage.FRENCH.hasWebfont());
        assertFalse(SupportedLanguage.SPANISH.hasWebfont());
    }

    @Test
    void arabicAndUrduAreRightToLeft() {
        assertTrue(SupportedLanguage.ARABIC.isRtl());
        assertTrue(SupportedLanguage.URDU.isRtl());
        assertEquals("rtl", SupportedLanguage.ARABIC.getDirection());
        assertEquals("rtl", SupportedLanguage.URDU.getDirection());

        assertFalse(SupportedLanguage.ENGLISH.isRtl());
        assertFalse(SupportedLanguage.FRENCH.isRtl());
        assertEquals("ltr", SupportedLanguage.ENGLISH.getDirection());
        assertEquals("ltr", SupportedLanguage.FRENCH.getDirection());
    }

    @Test
    void everyLanguageIsNamedInItsOwnScript() {
        // A switcher entry reading "Arabic" is only useful to someone who already reads English.
        for (SupportedLanguage lang : SupportedLanguage.values()) {
            assertNotNull(lang.getEndonym(), lang.name() + " needs an endonym");
            assertFalse(lang.getEndonym().isBlank(), lang.name() + " needs an endonym");
        }
        assertEquals("العربية", SupportedLanguage.ARABIC.getEndonym());
        assertEquals("اردو", SupportedLanguage.URDU.getEndonym());
        assertEquals("Français", SupportedLanguage.FRENCH.getEndonym());
    }

    /**
     * Every shipped language must define every key in the English base bundle. A gap here would
     * surface to a user as a raw key like {@code label.select.grade} on a live page.
     */
    @Test
    void everyBundleCoversEveryKeyInTheEnglishBase() throws IOException {
        final Set<String> baseKeys = keysOf("/messages.properties");
        assertFalse(baseKeys.isEmpty(), "the English base bundle must not be empty");

        for (SupportedLanguage lang : SupportedLanguage.values()) {
            if (lang == SupportedLanguage.ENGLISH) {
                continue; // English IS the base bundle
            }
            final String bundle = "/messages_" + lang.getTag() + ".properties";
            final Set<String> keys = keysOf(bundle);

            final Set<String> missing = new TreeSet<>(baseKeys);
            missing.removeAll(keys);

            assertTrue(missing.isEmpty(),
                    bundle + " is missing " + missing.size() + " key(s): " + missing);
        }
    }

    /**
     * No bundle may contain U+FFFD, the Unicode replacement character.
     *
     * The committed Spanish bundle had 26 such lines: someone had saved a Latin-1 file as UTF-8
     * years ago, so every accented character was already destroyed and users read
     * "credenciales no v�lidas". Nothing failed — the file was valid UTF-8, the keys all
     * resolved, and the damage was invisible to every other check. This is the only cheap way to
     * catch that class of corruption, and it matters most for the languages nobody on the team reads.
     */
    @Test
    void noBundleContainsCorruptedCharacters() throws IOException {
        for (SupportedLanguage lang : SupportedLanguage.values()) {
            final String bundle = lang == SupportedLanguage.ENGLISH
                    ? "/messages.properties"
                    : "/messages_" + lang.getTag() + ".properties";

            try (InputStream in = getClass().getResourceAsStream(bundle)) {
                assertNotNull(in, "missing bundle: " + bundle);
                final String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                final int at = body.indexOf('�');

                assertTrue(at < 0, () -> bundle + " contains U+FFFD near: "
                        + body.substring(Math.max(0, at - 60), Math.min(body.length(), at + 20)));
            }
        }
    }

    /** Keys defined in a translation but not in the base — usually a typo that will never render. */
    @Test
    void noBundleDefinesAKeyTheBaseDoesNotHave() throws IOException {
        final Set<String> baseKeys = keysOf("/messages.properties");

        for (SupportedLanguage lang : SupportedLanguage.values()) {
            if (lang == SupportedLanguage.ENGLISH) {
                continue;
            }
            final String bundle = "/messages_" + lang.getTag() + ".properties";
            final Set<String> extra = new TreeSet<>(keysOf(bundle));
            extra.removeAll(baseKeys);

            assertTrue(extra.isEmpty(), bundle + " defines key(s) absent from the base: " + extra);
        }
    }

    private Set<String> keysOf(String classpathResource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(classpathResource)) {
            assertNotNull(in, "missing bundle on the classpath: " + classpathResource);
            final Properties props = new Properties();
            // The bundles are UTF-8 (MessageSource sets defaultEncoding); Properties.load(Reader)
            // honours that, whereas load(InputStream) would decode them as ISO-8859-1.
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return new LinkedHashSet<>(props.stringPropertyNames());
        }
    }
}
