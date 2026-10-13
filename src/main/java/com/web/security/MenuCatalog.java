package com.web.security;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * RW-0b — the menu catalogue (auth-service {@code ui_menu}, V25): which permission codes unlock each menu key.
 * Loaded and kept by {@link AuthCatalog}. DATA, not code: a new menu or a changed rule is a row.
 */
@Component
public class MenuCatalog {

    static final String PATH = "/api/auth/org/menus";

    private final AuthCatalog catalog;

    public MenuCatalog(AuthCatalog catalog) { this.catalog = catalog; }

    /** The codes that unlock {@code key} (empty = everyone signed in), or null when the key or catalogue is unknown. */
    public List<String> codesFor(String key) {
        List<Map<String, Object>> rows = catalog.rows(PATH);
        if (rows == null || key == null) return null;
        for (Map<String, Object> r : rows) {
            if (key.equals(String.valueOf(r.get("key")))) {
                Object codes = r.get("codes");
                return codes instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            }
        }
        return null;
    }
}
