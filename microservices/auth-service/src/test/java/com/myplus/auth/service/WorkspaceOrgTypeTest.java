package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** HC-1a — a workspace tile is offered only to the business types it lists (the clinic gets no Pharmacy / Stock). */
class WorkspaceOrgTypeTest {

    @Test
    void a_tile_with_no_types_is_for_every_type() {
        assertThat(PermissionVisibility.forOrgType(null, "CLINIC")).isTrue();
        assertThat(PermissionVisibility.forOrgType(" ", "PHARMA")).isTrue();
    }

    @Test
    void the_clinic_gets_its_tiles_and_not_the_pharmacy_or_stock() {
        assertThat(PermissionVisibility.forOrgType("CLINIC,PHARMA", "CLINIC")).as("front desk / doctor / admin").isTrue();
        assertThat(PermissionVisibility.forOrgType("PHARMA", "CLINIC")).as("pharmacy / stock").isFalse();
    }

    @Test
    void a_combined_pharmacy_keeps_every_tile_until_it_is_split() {
        assertThat(PermissionVisibility.forOrgType("CLINIC,PHARMA", "PHARMA")).isTrue();
        assertThat(PermissionVisibility.forOrgType("PHARMA", "pharma")).as("case does not matter").isTrue();
    }

    @Test
    void an_unknown_type_sees_no_type_specific_tile() {
        assertThat(PermissionVisibility.forOrgType("PHARMA", null)).isFalse();
    }
}
