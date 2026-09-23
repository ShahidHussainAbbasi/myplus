package com.myplus.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.myplus.common.security.AuthenticatedUser;

/**
 * The production guard on the shared tenant purge.
 *
 * <h3>What this protects</h3>
 * {@code DemoPurgeController#purge} deletes every row carrying the caller's {@code organizationId} in this
 * service — and the same controller is auto-applied to all 13 JPA services, so one call clears a tenant across
 * the platform. There is no backup, no undo and, until this slice, no record of what went.
 *
 * <p>Before this guard the only thing between a production tenant and that was CONFIGURATION: with
 * {@code app.seed-demo=false} nobody holds {@code DEMO_PRIVILEGE}, so nobody can reach the endpoint. That is
 * true, and it is one environment variable deep — {@code SEED_DEMO=true} in production (to show the product to
 * a customer) seeds demo tenants AND a known-credential owner account carrying {@code DEMO_RESET_PRIVILEGE},
 * and a whole-organisation delete becomes reachable from a button.
 *
 * <h3>Why these cases and not a mocked EntityManager</h3>
 * The controller's {@code EntityManager} is deliberately left NULL here. Every case below asserts a refusal
 * that must happen BEFORE any deletion, so a null {@code em} is the strongest possible assertion: if the guard
 * ever stops returning early, these tests do not fail on a mismatched string — they fail with
 * NullPointerException at the first query. The test cannot pass while the code deletes anything.
 */
class DemoPurgeControllerProdGuardTest {

    /** A caller who WOULD be allowed to purge — so a refusal below is the profile guard, never the privilege. */
    private static AuthenticatedUser demoAccount() {
        return new AuthenticatedUser(7L, "demo.business@myplus.com",
                List.of(new SimpleGrantedAuthority("DEMO_PRIVILEGE")), 13L);
    }

    private static DemoPurgeController controller(String profile, boolean purgeEnabled) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) env.setActiveProfiles(profile);
        return new DemoPurgeController(env, purgeEnabled);
    }

    @Test
    @DisplayName("⭐⭐ under `prod` the purge is REFUSED — even for an account that holds the privilege")
    void productionRefusesEvenAPrivilegedCaller() {
        ResponseEntity<Map<String, Object>> r = controller("prod", false).purge(demoAccount());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).containsEntry("success", false);
        assertThat(String.valueOf(r.getBody().get("message")))
                .as("the message names production, so the refusal is not mistaken for a permissions problem")
                .contains("production");
    }

    @Test
    @DisplayName("⭐⭐ the refusal comes BEFORE the privilege check — the caller's identity is irrelevant")
    void productionRefusesBeforeLookingAtAuthorities() {
        /*
         * Ordering is the whole design. Were the privilege check first, the guard would only ever be reached by
         * someone already entitled to purge — and the failure mode this exists for is precisely an account that
         * IS entitled (a seeded demo owner) existing in production by accident.
         */
        AuthenticatedUser nobody = new AuthenticatedUser(99L, "stranger@example.com", List.of(), 13L);
        ResponseEntity<Map<String, Object>> r = controller("prod", false).purge(nobody);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("message"))).contains("production");
    }

    @Test
    @DisplayName("⭐ a deliberate opt-in lifts the guard — app.demo.purge-enabled=true")
    void anExplicitOptInIsHonoured() {
        /*
         * With the flag on, the prod guard must stand aside and the ORDINARY privilege check must decide. This
         * caller holds nothing, so the answer is the authority refusal — a different message from the one above,
         * which is what proves the flag was read rather than the guard merely re-firing.
         */
        AuthenticatedUser nobody = new AuthenticatedUser(99L, "stranger@example.com", List.of(), 13L);
        ResponseEntity<Map<String, Object>> r = controller("prod", true).purge(nobody);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("message")))
                .as("past the profile guard, so the privilege check answers")
                .isEqualTo("Demo/reset accounts only");
    }

    @Test
    @DisplayName("⭐ off production the endpoint behaves exactly as before — dev and CI are untouched")
    void nonProductionIsUnchanged() {
        // The guard must not become a blanket disable: demo tenants resetting themselves is a real product
        // feature, and every Cypress gate depends on it working outside prod.
        AuthenticatedUser nobody = new AuthenticatedUser(99L, "stranger@example.com", List.of(), 13L);

        for (String profile : new String[] { null, "dev", "docker" }) {
            ResponseEntity<Map<String, Object>> r = controller(profile, false).purge(nobody);
            assertThat(String.valueOf(r.getBody().get("message")))
                    .as("profile %s reaches the ordinary privilege check", profile)
                    .isEqualTo("Demo/reset accounts only");
        }
    }

    @Test
    @DisplayName("the profile match is case-insensitive — PROD must not slip past")
    void profileMatchingIsCaseInsensitive() {
        ResponseEntity<Map<String, Object>> r = controller("PROD", false).purge(demoAccount());
        assertThat(String.valueOf(r.getBody().get("message"))).contains("production");
    }

    @Test
    @DisplayName("prod alongside other profiles still refuses — the list is searched, not just its first entry")
    void prodAnywhereInTheProfileListCounts() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("docker", "prod");
        ResponseEntity<Map<String, Object>> r = new DemoPurgeController(env, false).purge(demoAccount());
        assertThat(String.valueOf(r.getBody().get("message"))).contains("production");
    }
}
