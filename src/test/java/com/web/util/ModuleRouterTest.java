package com.web.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.persistence.model.User;

/**
 * B2B P0.5 — where a login lands.
 *
 * <p>Pure logic, no Spring, so it runs on every {@code mvn test}. Worth pinning hard: this decides which
 * screen a user sees, the rule has a fallback that every pre-existing tenant depends on, and the map it
 * replaces had already drifted into two disagreeing copies.
 */
class ModuleRouterTest {

    private static User user(String userType, String activeOrgType) {
        User u = new User();
        u.setUserType(userType);
        u.setActiveOrgType(activeOrgType);
        return u;
    }

    @Nested
    @DisplayName("resolution order")
    class ResolutionOrder {

        @Test
        @DisplayName("the ACTIVE ORG's type wins over the user's own type")
        void orgTypeWins() {
            // The whole point of the slice: a BUSINESS user working in their school gets the school.
            assertEquals("/educationDashboard", ModuleRouter.dashboardFor(user("BUSINESS", "EDUCATION")));
            assertEquals("/businessDashboard", ModuleRouter.dashboardFor(user("EDUCATION", "BUSINESS")));
        }

        @Test
        @DisplayName("a NULL org type falls back to userType — every tenant predating the column")
        void nullOrgTypeFallsBack() {
            assertEquals("/educationDashboard", ModuleRouter.dashboardFor(user("EDUCATION", null)));
            assertEquals("/businessDashboard", ModuleRouter.dashboardFor(user("BUSINESS", null)));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        @DisplayName("a BLANK org type behaves exactly like a missing one, never like a type")
        void blankOrgTypeFallsBack(String blank) {
            assertEquals("/educationDashboard", ModuleRouter.dashboardFor(user("EDUCATION", blank)));
        }

        @Test
        @DisplayName("C2 — an UNKNOWN org type reaches a WORKING dashboard, never a guessed route")
        void unknownOrgTypeIsSafe() {
            /*
             * The original intent survives and is asserted below: the router must never build
             * "/logisticsDashboard" from the type, because that route does not exist and would 404.
             *
             * What changed is the destination. This used to answer LANDING, and Organization.type is a
             * free-text column with no enum and no allow-list — so a tenant onboarded as MOBILE, exactly the
             * mobile shop this product means to serve, had every login silently bounced to "/". Nothing
             * failed and nothing logged; they simply never reached a dashboard.
             *
             * A fixed fallback to a dashboard that certainly exists is better than a landing page AND better
             * than a guess. Routing is not authorization — every section on that page stays gated by
             * privilege, org scope and capability.
             */
            String dest = ModuleRouter.dashboardFor(user("BUSINESS", "LOGISTICS"));

            assertEquals(ModuleRouter.COMMERCE_DASHBOARD, dest, "an unknown type lands somewhere that works");
            assertNotEquals(ModuleRouter.LANDING, dest, "and is no longer bounced to the landing page");
            // The original guarantee, unchanged: never a route derived from the type itself.
            assertFalse(dest.toLowerCase().contains("logistics"), "never a guessed /<type>Dashboard");
        }

        @Test
        @DisplayName("C2 — a real mobile shop: type MOBILE now reaches the trade dashboard")
        void mobileShopReachesADashboard() {
            // The case that prompted C2. Shahzad Mobile Shop is onboarded as MOBILE; before this it saw the
            // landing page on every login.
            assertEquals(ModuleRouter.COMMERCE_DASHBOARD, ModuleRouter.dashboardFor(user("BUSINESS", "MOBILE")));
        }

        @Test
        @DisplayName("neither set → the landing page")
        void neitherSet() {
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardFor(user(null, null)));
        }

        @Test
        @DisplayName("a null user never throws — the handler calls this before anything else")
        void nullUser() {
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardFor(null));
        }

        @Test
        @DisplayName("case and padding are irrelevant")
        void caseInsensitive() {
            assertEquals("/educationDashboard", ModuleRouter.dashboardFor(user(null, "  education  ")));
            assertEquals("/businessDashboard", ModuleRouter.dashboardFor(user("business", null)));
        }
    }

    @Nested
    @DisplayName("the module → dashboard map")
    class DashboardMap {

        @ParameterizedTest
        @CsvSource({
                "BUSINESS,    /businessDashboard",
                "PHARMA,      /businessDashboard",
                "MARKETPLACE, /businessDashboard",
                "EDUCATION,   /educationDashboard",
                "WELFARE,     /welfareDashboard",
                "AGRICULTURE, /agricultureDashboard",
                "APPOINTMENT, /appointmentDashboard",
        })
        @DisplayName("every module lands on its dashboard")
        void everyModuleMaps(String module, String expected) {
            assertEquals(expected, ModuleRouter.dashboardForModule(module));
        }

        @Test
        @DisplayName("APPOINTMENT resolves — the drift this class was created to fix")
        void appointmentRegression() {
            // AppController.dashboard() had no APPOINTMENT case and returned "/", while the login handler
            // routed to /appointmentDashboard. The same user landed in two places depending on how they
            // arrived. Both now call this one method, so the disagreement cannot come back.
            assertEquals("/appointmentDashboard", ModuleRouter.dashboardForModule("APPOINTMENT"));
            assertEquals("/appointmentDashboard", ModuleRouter.dashboardFor(user("APPOINTMENT", null)));
            assertEquals("/appointmentDashboard", ModuleRouter.dashboardFor(user(null, "APPOINTMENT")));
        }

        @Test
        @DisplayName("all three commerce verticals share the ONE dashboard (slice 36)")
        void commerceSharesOneDashboard() {
            assertEquals("/businessDashboard", ModuleRouter.dashboardForModule("BUSINESS"));
            assertEquals("/businessDashboard", ModuleRouter.dashboardForModule("PHARMA"));
            assertEquals("/businessDashboard", ModuleRouter.dashboardForModule("MARKETPLACE"));

            assertTrue(ModuleRouter.isCommerce("PHARMA"));
            assertTrue(ModuleRouter.isCommerce("marketplace"));
            assertFalse(ModuleRouter.isCommerce("EDUCATION"));
            assertFalse(ModuleRouter.isCommerce(null));
        }

        @Test
        @DisplayName("blank → landing; unknown → a working dashboard; never a null path")
        void unknownModule() {
            // C2 splits what used to be one answer. BLANK means we do not know who this user is, and saying
            // so is honest. An unknown-but-PRESENT type means somebody onboarded a business the router has
            // not heard of — they get a dashboard that exists rather than the landing page.
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardForModule(null));
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardForModule(""));
            assertEquals(ModuleRouter.COMMERCE_DASHBOARD, ModuleRouter.dashboardForModule("NOPE"));
            // The original guarantee, unchanged.
            assertNotNull(ModuleRouter.dashboardForModule("NOPE"));
        }
    }

    @Nested
    @DisplayName("moduleOf")
    class ModuleOf {

        @Test
        @DisplayName("reports the module actually in effect, normalised")
        void reportsEffectiveModule() {
            assertEquals("EDUCATION", ModuleRouter.moduleOf(user("BUSINESS", "education")));
            assertEquals("BUSINESS", ModuleRouter.moduleOf(user("business", null)));
            assertEquals(null, ModuleRouter.moduleOf(user(null, null)));
            assertEquals(null, ModuleRouter.moduleOf(null));
        }

        @Test
        @DisplayName("an unknown module is still REPORTED — only the dashboard lookup falls back")
        void unknownModuleStillReported() {
            // moduleOf answers "what module is this user in", which is a different question from "what
            // screen do we have for it". Collapsing an unknown module to null here would lose information
            // a caller may legitimately want (logging, a future per-module feature check).
            assertEquals("LOGISTICS", ModuleRouter.moduleOf(user("BUSINESS", "LOGISTICS")));
            // C2: the lookup still falls back — to a working dashboard now, not the landing page.
            assertEquals(ModuleRouter.COMMERCE_DASHBOARD, ModuleRouter.dashboardFor(user("BUSINESS", "LOGISTICS")));
        }
    }

    /**
     * INST-0b — the rule {@code CommerceDashboardController.resolveModule()} now composes.
     *
     * <p>That controller used to keep its OWN copy of the commerce-type set and read {@code userType} only, so
     * the screen a user was ROUTED to and the vertical it was SKINNED as could disagree. It now asks this class
     * both questions. Pinned here because the controller's own answer needs a Spring context to observe, while
     * the rule behind it does not.
     */
    @Nested
    @DisplayName("INST-0b — moduleOf + isCommerce decide the dashboard SKIN")
    class CommerceSkin {

        /** Exactly what the controller does, so a change to either method is caught by this test. */
        private String skinFor(User u) {
            String module = ModuleRouter.moduleOf(u);
            return ModuleRouter.isCommerce(module) ? module : "BUSINESS";
        }

        @Test
        @DisplayName("a BUSINESS user working in a PHARMA org is skinned as PHARMA, not POS")
        void orgTypeDecidesTheSkin() {
            // The defect INST-0b fixes: routed to /businessDashboard by the org, then relabelled by the person.
            assertEquals("PHARMA", skinFor(user("BUSINESS", "PHARMA")));
            assertEquals("MARKETPLACE", skinFor(user("BUSINESS", "MARKETPLACE")));
        }

        @Test
        @DisplayName("unchanged for every tenant whose org type is null — i.e. almost all of them")
        void unchangedForSingleModuleTenants() {
            assertEquals("BUSINESS", skinFor(user("BUSINESS", null)));
            assertEquals("PHARMA", skinFor(user("PHARMA", null)));
            assertEquals("MARKETPLACE", skinFor(user("MARKETPLACE", null)));
            assertEquals("BUSINESS", skinFor(user("BUSINESS", "BUSINESS")));
        }

        @Test
        @DisplayName("a non-commerce module falls back to POS wording rather than a half-relabelled screen")
        void nonCommerceFallsBackToPos() {
            assertEquals("BUSINESS", skinFor(user("EDUCATION", null)));
            assertEquals("BUSINESS", skinFor(user("BUSINESS", "EDUCATION")));
            assertEquals("BUSINESS", skinFor(user(null, null)));
            assertEquals("BUSINESS", skinFor(null));
        }

        @Test
        @DisplayName("an unregistered org type is skinned POS — and now ROUTES somewhere too")
        void unregisteredTypeIsSkinnedPos() {
            /*
             * This test already described the MOBILE trap before C2 existed: Organization.type is free text,
             * so 'MOBILE' can be stored today, and it used to route to LANDING and skin as POS. C2 fixes the
             * ROUTING half — the trap was that a real mobile shop never reached a dashboard at all.
             *
             * The SKINNING half is deliberately unchanged and is not a trap: POS wording is the correct
             * default for a trade business the router has not been taught about, and CommerceDashboardController
             * renders it for exactly that reason. Words are a profile question (shape), not a routing one.
             */
            assertEquals("BUSINESS", skinFor(user("BUSINESS", "MOBILE")), "still skinned POS, correctly");
            assertEquals(ModuleRouter.COMMERCE_DASHBOARD, ModuleRouter.dashboardFor(user("BUSINESS", "MOBILE")),
                    "and no longer stranded on the landing page");
        }
    }
}
