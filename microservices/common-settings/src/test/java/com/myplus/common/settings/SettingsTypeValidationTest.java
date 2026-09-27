package com.myplus.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;

/**
 * SET-GUIDE — a setting is validated against its TYPE when it is written.
 *
 * <p>Found while documenting each setting's validation rules: the write path accepted anything — "abc" for a whole
 * number, an option that does not exist, "yes" for a switch — and every reader falls back to the default on a value it
 * cannot parse. So a malformed value was STORED, shown as "changed from default", and silently ignored: the owner
 * saw their change saved and the till behaved as if they had never made it. A cleared number box (which sends "")
 * did exactly that.
 */
class SettingsTypeValidationTest {

    private static final Long ORG = 13L;
    private final Map<String, String> rows = new LinkedHashMap<>();
    private SettingsService svc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "owner@test.com", List.of(), ORG), null, List.of()));
        SettingsStore store = new SettingsStore() {
            @Override public Optional<String> find(Long o, String k) { return Optional.ofNullable(rows.get(k)); }
            @Override public List<Stored> findAll(Long o) {
                List<Stored> out = new ArrayList<>();
                rows.forEach((k, v) -> out.add(new Stored(k, v)));
                return out;
            }
            @Override public void upsert(Long o, Long u, String k, String v) { rows.put(k, v); }
        };
        SettingsCatalogProvider catalog = () -> List.of(
                SettingEntry.bool("b", "Switch", "", false, "G"),
                SettingEntry.intOf("i", "Whole number", "", 1, "G"),
                SettingEntry.money("m", "Money", "", "0", "G"),
                SettingEntry.select("s", "Choice", "", "CASH", "G",
                        List.of(new SettingEntry.Option("CASH", "Cash"), new SettingEntry.Option("CARD", "Card"))),
                SettingEntry.text("t", "Text", "", "", "G"));
        svc = new SettingsService(store, List.of(catalog), Providers.none(), Providers.none(), 60L);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void refused(String key, String value, String says) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> svc.set(key, value),
                key + "=" + value + " must be refused");
        assertTrue(e.getMessage().toLowerCase().contains(says), e.getMessage());
        assertFalse(rows.containsKey(key), "nothing is stored for a refused " + key);
    }

    @Test
    void a_switch_takes_only_true_or_false() {
        refused("b", "yes", "on or off");
        refused("b", "", "on or off");
        svc.set("b", "TRUE");
        assertEquals("true", rows.get("b"), "stored in its canonical form");
    }

    @Test
    void a_whole_number_must_be_one__including_a_cleared_box() {
        refused("i", "abc", "whole number");
        refused("i", "", "whole number");
        refused("i", "2.5", "whole number");
        svc.set("i", " 4 ");
        assertEquals("4", rows.get("i"));
    }

    @Test
    void money_must_be_a_number() {
        refused("m", "Rs 5", "amount");
        refused("m", "", "amount");
        svc.set("m", "5.50");
        assertEquals("5.50", rows.get("m"));
    }

    @Test
    void a_choice_must_be_one_of_its_options__stored_as_the_option_is_written() {
        refused("s", "CHEQUE", "one of");
        svc.set("s", "card");
        assertEquals("CARD", rows.get("s"));
    }

    @Test
    void text_is_free__blank_included() {
        svc.set("t", "");
        svc.set("t", "Any words at all");
        assertEquals("Any words at all", rows.get("t"));
    }
}
