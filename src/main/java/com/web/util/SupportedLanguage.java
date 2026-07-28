package com.web.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The languages the UI ships in — the single source of truth for the switcher, the locale
 * whitelist, and the text direction. Adding a language means adding a constant here and a
 * {@code messages_<tag>.properties} bundle; nothing else needs to change.
 *
 * {@code endonym} is the language's name in its own script, because a switcher that lists
 * "Arabic" is only useful to someone who already reads English.
 */
public enum SupportedLanguage {

    ENGLISH("en", "English",  "EN", Direction.LTR, null),
    FRENCH ("fr", "Français", "FR", Direction.LTR, null),
    SPANISH("es", "Español",  "ES", Direction.LTR, null),
    // Devanagari: the UI font (Inter) has no coverage, so Hindi needs a webfont even though it is
    // left-to-right. Font need is therefore its own property, NOT something derived from direction.
    HINDI  ("hi", "हिन्दी",     "हि", Direction.LTR, "Noto+Sans+Devanagari:wght@400;500;600;700"),
    ARABIC ("ar", "العربية",  "ع",  Direction.RTL, "Noto+Naskh+Arabic:wght@400;500;600;700"),
    URDU   ("ur", "اردو",     "اُر", Direction.RTL, "Noto+Nastaliq+Urdu:wght@400;500;600;700");

    public enum Direction { LTR, RTL }

    private final String tag;
    private final String endonym;
    private final String shortLabel;
    private final Direction direction;
    private final String webfont;

    SupportedLanguage(String tag, String endonym, String shortLabel, Direction direction, String webfont) {
        this.tag = tag;
        this.endonym = endonym;
        this.shortLabel = shortLabel;
        this.direction = direction;
        this.webfont = webfont;
    }

    /**
     * Google Fonts family spec for scripts the UI font does not cover, or {@code null} for the
     * Latin-script languages that Inter already handles.
     */
    public String getWebfont() {
        return webfont;
    }

    public boolean hasWebfont() {
        return webfont != null;
    }

    public String getTag() {
        return tag;
    }

    public String getEndonym() {
        return endonym;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getDirection() {
        return direction == Direction.RTL ? "rtl" : "ltr";
    }

    public boolean isRtl() {
        return direction == Direction.RTL;
    }

    private static final Map<String, SupportedLanguage> BY_TAG;

    static {
        final Map<String, SupportedLanguage> byTag = new LinkedHashMap<>();
        for (SupportedLanguage lang : values()) {
            byTag.put(lang.tag, lang);
        }
        BY_TAG = Collections.unmodifiableMap(byTag);
    }

    /**
     * Resolves a locale to a shipped language, falling back to English. Matches on the language
     * subtag only, so {@code ar-EG}, {@code ar_SA} and {@code ar} all resolve to Arabic — a browser
     * sending a regional variant should still get its language rather than silently drop to English.
     */
    public static SupportedLanguage from(Locale locale) {
        if (locale == null) {
            return ENGLISH;
        }
        final SupportedLanguage match = BY_TAG.get(locale.getLanguage().toLowerCase(Locale.ROOT));
        return match != null ? match : ENGLISH;
    }

    public static boolean isSupported(String tag) {
        return tag != null && BY_TAG.containsKey(tag.toLowerCase(Locale.ROOT));
    }

    /** The locales the app accepts — used to whitelist the {@code ?lang=} switch. */
    public static List<Locale> locales() {
        return java.util.Arrays.stream(values())
                .map(l -> Locale.forLanguageTag(l.tag))
                .collect(Collectors.toList());
    }
}
