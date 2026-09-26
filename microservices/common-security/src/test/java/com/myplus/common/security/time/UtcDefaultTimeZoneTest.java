package com.myplus.common.security.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * TZ-1 — the JVM zone pin is OFF unless switched on. Unguarded it changed every service's zone the moment the jar
 * contained it (reported by a peer session before a rebuild shipped it); it must land deliberately, with its gate.
 */
class UtcDefaultTimeZoneTest {

    private TimeZone original;

    @BeforeEach
    void remember() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Karachi"));   // what the Windows host runs
    }

    @AfterEach
    void restore() { TimeZone.setDefault(original); }

    @Test
    @DisplayName("⭐ switch absent → the JVM zone is left exactly as it was")
    void offByDefault() {
        new UtcDefaultTimeZone().postProcessEnvironment(new MockEnvironment(), null);
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Karachi");
    }

    @Test
    @DisplayName("app.tz.pin-utc=true → UTC")
    void onWhenSwitchedOn() {
        new UtcDefaultTimeZone().postProcessEnvironment(
                new MockEnvironment().withProperty(UtcDefaultTimeZone.PROPERTY, "true"), null);
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}
