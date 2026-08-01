package com.web.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        @DisplayName("an UNKNOWN org type falls back to the landing page, never a 404 route")
        void unknownOrgTypeIsSafe() {
            // A module with no monolith dashboard yet is a normal state. It must not become "/logisticsDashboard".
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardFor(user("BUSINESS", "LOGISTICS")));
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
        @DisplayName("null/unknown module → landing, never a null path")
        void unknownModule() {
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardForModule(null));
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardForModule("NOPE"));
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardForModule(""));
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
            assertEquals(ModuleRouter.LANDING, ModuleRouter.dashboardFor(user("BUSINESS", "LOGISTICS")));
        }
    }
}
