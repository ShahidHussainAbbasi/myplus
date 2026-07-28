package com.myplus.common.settings;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Language policy — the one settings catalog that is NOT vertical-specific, so it lives in the shared
 * library and appears on the Configuration screen of every module (retail, pharmacy, education,
 * welfare, agriculture) without each of them redeclaring it.
 *
 * The stored value is a BCP-47 language tag ("en", "fr", "ar", "ur"), never the display name: the
 * display name is itself translated, so storing it would change the stored value the moment an owner
 * switched language.
 *
 * This is only the FALLBACK. A visitor's own choice (the language switcher) wins, and their browser's
 * Accept-Language comes next — see SupportedLocaleResolver in the web tier. The setting decides what a
 * visitor sees when their browser asks for a language we do not ship, which is the common case for an
 * organization whose staff all use English-locale machines but work in Urdu or Arabic.
 */
@Component
public class LocaleSettingsCatalog implements SettingsCatalogProvider {

    /** Keep in step with SupportedLanguage in the web tier — same tags, same order. */
    private static final List<SettingEntry.Option> LANGUAGES = List.of(
            new SettingEntry.Option("auto", "Automatic (match the visitor's browser)"),
            new SettingEntry.Option("en", "English"),
            new SettingEntry.Option("fr", "Français (French)"),
            new SettingEntry.Option("es", "Español (Spanish)"),
            new SettingEntry.Option("hi", "हिन्दी (Hindi)"),
            new SettingEntry.Option("ar", "العربية (Arabic)"),
            new SettingEntry.Option("ur", "اردو (Urdu)")
    );

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.select("org.locale.defaultLanguage",
                        "Default language",
                        "The language new visitors see before they choose one. \"Automatic\" (default) follows each "
                                + "visitor's own browser and region settings, so a person in Lahore gets Urdu and one "
                                + "in Paris gets French. Pick a specific language to make everyone start there instead. "
                                + "Anyone can still switch language at any time.",
                        "auto", "Language", LANGUAGES)
        );
    }
}
