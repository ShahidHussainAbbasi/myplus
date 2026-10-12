package com.web.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * RW-2 on RW-0b (2026-10-12) — which menus a person sees: {@code ${@perm.menu('purchase')}} in a page. The KEY is all a
 * page names; which permission codes unlock it is the {@code ui_menu} row in auth-service (V25), read through
 * {@link MenuCatalog}. Nothing about roles or codes is written in a template or here.
 *
 * <p>The rule mirrors {@link PermissionInterceptor#holds}: the owner, an administrator and the platform roles see every
 * menu (they may do every action); anyone else sees a menu when they hold ANY of its codes; a row with no codes is for
 * everyone signed in. An unknown key (no row, or the catalogue not loaded) is SHOWN and logged — a missing row must
 * not blank a screen; the server's refusal is the control (STANDARDS §0c).
 */
@Component("perm")
public class MenuAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger(MenuAccess.class);
    private static final Set<String> EVERYTHING = Set.of("ROLE_OWNER", "SUPER_PRIVILEGE", "ADMIN_PRIVILEGE", "ADMIN_ROLE");

    private final MenuCatalog catalog;

    public MenuAccess(MenuCatalog catalog) { this.catalog = catalog; }

    /** May the signed-in person see the menu {@code key}? */
    public boolean menu(String key) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        List<String> codes = catalog.codesFor(key);
        if (codes == null) {
            LOGGER.debug("menu.unknown {} — shown (no ui_menu row, or the catalogue is not loaded)", key);
            return true;
        }
        return allows(auth.getAuthorities(), codes);
    }

    static boolean allows(Collection<? extends GrantedAuthority> held, List<String> codes) {
        if (held == null) return false;
        for (GrantedAuthority a : held) if (EVERYTHING.contains(a.getAuthority())) return true;
        if (codes == null || codes.isEmpty()) return true;
        for (GrantedAuthority a : held) if (codes.contains(a.getAuthority())) return true;
        return false;
    }
}
