package com.myplus.business_service.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * RW-4a — the dashboard's money (revenue, sales, stock value) is computed only for someone holding report.view, or
 * the owner / an administrator / the platform. The front desk ("Clinic reception", no report.view since V23) sees none.
 */
class DashboardMoneyVisibilityTest {

    private static List<SimpleGrantedAuthority> held(String... a) {
        return Arrays.stream(a).map(SimpleGrantedAuthority::new).toList();
    }

    @Test
    void the_front_desk_and_the_doctor_see_no_money() {
        assertFalse(BusinessDashboardController.seesMoney(held("ROLE_BUSINESS_USER", "sale.create", "clinic.token.issue")));
        assertFalse(BusinessDashboardController.seesMoney(held("ROLE_BUSINESS_USER", "clinic.consult")));
        assertFalse(BusinessDashboardController.seesMoney(null));
    }

    @Test
    void report_view_the_owner_an_admin_and_the_platform_see_it() {
        assertTrue(BusinessDashboardController.seesMoney(held("ROLE_BUSINESS_USER", "report.view")));
        assertTrue(BusinessDashboardController.seesMoney(held("ROLE_OWNER")));
        assertTrue(BusinessDashboardController.seesMoney(held("ADMIN_PRIVILEGE")));
        assertTrue(BusinessDashboardController.seesMoney(held("SUPER_PRIVILEGE")));
    }
}
