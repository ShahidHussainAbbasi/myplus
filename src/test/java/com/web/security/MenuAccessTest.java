package com.web.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * RW-2 on RW-0b — a menu shows when the person holds ANY code its ui_menu row lists (the PermissionInterceptor rule).
 * The codes per role here are what auth-service's built-in sets carry; the menus' codes are ui_menu rows (V25).
 */
class MenuAccessTest {

    private static List<SimpleGrantedAuthority> held(String... a) {
        return Arrays.stream(a).map(SimpleGrantedAuthority::new).toList();
    }

    private static final List<SimpleGrantedAuthority> RECEPTION = held("ROLE_BUSINESS_USER", "WRITE_PRIVILEGE",
            "customer.view", "sale.create", "sale.view", "till.view", "clinic.patient.edit", "clinic.token.issue");
    private static final List<SimpleGrantedAuthority> DOCTOR = held("ROLE_BUSINESS_USER", "clinic.consult",
            "clinic.patient.view", "clinic.queue.view");

    private static final List<String> PURCHASE = List.of("purchase.view", "purchase.create", "purchase.edit");
    private static final List<String> SALE = List.of("sale.view", "sale.create");
    private static final List<String> CLINIC_SETTINGS = List.of("clinic.settings");

    @Test
    void a_menu_shows_when_any_of_its_codes_is_held() {
        assertTrue(MenuAccess.allows(RECEPTION, SALE));
        assertFalse(MenuAccess.allows(RECEPTION, PURCHASE));
        assertFalse(MenuAccess.allows(RECEPTION, CLINIC_SETTINGS));
        assertFalse(MenuAccess.allows(DOCTOR, SALE));
    }

    @Test
    void a_row_with_no_codes_is_for_everyone_signed_in() {
        assertTrue(MenuAccess.allows(DOCTOR, List.of()));
    }

    @Test
    void the_owner_the_platform_and_an_administrator_see_every_menu() {
        for (String role : List.of("ROLE_OWNER", "SUPER_PRIVILEGE", "ADMIN_PRIVILEGE", "ADMIN_ROLE")) {
            assertTrue(MenuAccess.allows(held(role), PURCHASE), role);
        }
    }

    @Test
    void nobody_signed_in_sees_nothing() {
        assertFalse(MenuAccess.allows(null, SALE));
    }
}
