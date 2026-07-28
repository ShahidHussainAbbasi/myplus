package com.web.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * The app's message source, plus the ability to hand a whole key-prefix to the browser.
 *
 * Client-side strings (alerts, confirms, empty-state text built in JS) need the SAME bundle the
 * server renders from — a second copy in a JS file would drift the moment anyone edits one of them.
 * {@link #getMessagesWithPrefix} exposes the already-merged, already-locale-resolved properties, so
 * the browser gets exactly what Thymeleaf would have rendered, English fallback included.
 *
 * Subclassing is what makes this possible: {@code getMergedProperties} is protected on
 * {@link ReloadableResourceBundleMessageSource}. Re-reading the .properties files directly would
 * duplicate Spring's locale-resolution and fallback rules — and get them subtly wrong.
 */
public class JsMessageSource extends ReloadableResourceBundleMessageSource {

    /**
     * Every message whose key starts with {@code prefix}, for the given locale.
     *
     * Only the {@code ui.js.*} subset is ever requested, so the payload stays small: server-only
     * strings (validation messages, email copy) are never shipped to the browser.
     */
    public Map<String, String> getMessagesWithPrefix(String prefix, Locale locale) {
        final Properties merged = getMergedProperties(locale).getProperties();
        final Map<String, String> out = new LinkedHashMap<>();

        for (String key : merged.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                out.put(key, merged.getProperty(key));
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
