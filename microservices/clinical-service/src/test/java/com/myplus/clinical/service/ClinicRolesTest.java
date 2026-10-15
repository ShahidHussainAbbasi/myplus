package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.clinical.entity.ClinicProvider;
import com.myplus.clinical.repository.ClinicProviderRepo;
import com.myplus.common.security.AuthenticatedUser;

/**
 * RW-1 (2026-10-12) — each clinic role may do its own work and nothing else (docs/hms-role-workspaces-design.md §3.2).
 * The sets are auth-service V23: the codes below are exactly what each built-in set carries in the sign-in token.
 * A real ClinicAccess, so the rules tested are the ones that run.
 */
class ClinicRolesTest {

    private static final String[] FRONT_DESK = { "ROLE_BUSINESS_USER", "clinic.patient.view", "clinic.patient.edit",
            "clinic.queue.view", "clinic.token.issue", "clinic.token.cancel", "clinic.doctor.day", "sale.create" };
    private static final String[] DOCTOR = { "ROLE_BUSINESS_USER", "clinic.consult", "clinic.patient.view", "clinic.queue.view" };
    private static final String[] PHARMACIST = { "ROLE_BUSINESS_USER", "rx.dispense", "sale.create", "clinic.patient.view" };
    private static final String[] STORE_KEEPER = { "ROLE_BUSINESS_USER", "purchase.create", "stock.edit" };

    ClinicProviderRepo providers;
    ClinicAccess access;

    private static void signIn(long userId, String... authorities) {
        List<SimpleGrantedAuthority> auths = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        AuthenticatedUser u = new AuthenticatedUser(userId, "u" + userId + "@x.pk", auths, 7L);
        u.setCapabilities(java.util.Set.of("clinic"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auths));
    }

    @BeforeEach
    void setUp() {
        providers = mock(ClinicProviderRepo.class);
        access = new ClinicAccess(providers);
        ClinicProvider linked = new ClinicProvider();
        linked.setOrganizationId(7L); linked.setProviderId(12L); linked.setUserId(501L);
        when(providers.findByOrganizationIdAndUserId(7L, 501L)).thenReturn(Optional.of(linked));
    }

    @AfterEach
    void signOut() { SecurityContextHolder.clearContext(); }

    private void allowed(Runnable check) { assertThatCode(check::run).doesNotThrowAnyException(); }

    private void refused(Runnable check) {
        assertThatThrownBy(check::run).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("The owner can change your permission set on Team");
    }

    @Test
    void the_front_desk_registers_patients_gives_and_cancels_tokens_and_sets_a_doctors_day() {
        signIn(600L, FRONT_DESK);
        allowed(access::assertMayViewPatients);
        allowed(access::assertMayEditPatients);
        allowed(access::assertMayViewQueue);
        allowed(access::assertMayIssueTokens);
        allowed(access::assertMayCancelTokens);
        allowed(access::assertMaySetDoctorDay);
        // …but neither consults nor manages doctors
        assertThatThrownBy(access::assertCanConsult).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(access::assertClinicAdmin).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void a_doctor_reads_patients_and_the_queue_but_does_not_do_the_front_desks_work() {
        signIn(700L, DOCTOR);
        allowed(access::assertCanConsult);
        allowed(access::assertMayViewPatients);
        allowed(access::assertMayViewQueue);
        refused(access::assertMayEditPatients);
        refused(access::assertMayIssueTokens);
        refused(access::assertMayCancelTokens);
        refused(access::assertMaySetDoctorDay);
    }

    @Test
    void a_linked_login_without_the_doctor_set_still_opens_my_queue() {
        signIn(501L, "ROLE_BUSINESS_USER");   // H2-03: linked to Dr Sana, no clinic.* codes at all
        allowed(access::assertMayViewQueue);
        allowed(access::assertMayViewPatients);
        refused(access::assertMayIssueTokens);
    }

    @Test
    void the_pharmacist_finds_a_patient_and_nothing_else_in_the_clinic() {
        signIn(800L, PHARMACIST);
        allowed(access::assertMayViewPatients);
        refused(access::assertMayViewQueue);
        refused(access::assertMayEditPatients);
        refused(access::assertMayIssueTokens);
        refused(access::assertMayCancelTokens);
    }

    @Test
    void the_store_keeper_has_no_clinic_at_all() {
        signIn(900L, STORE_KEEPER);
        refused(access::assertMayViewPatients);
        refused(access::assertMayViewQueue);
        refused(access::assertMayIssueTokens);
    }

    @Test
    void the_owner_and_an_administrator_keep_the_whole_clinic_whatever_their_set() {
        for (String role : List.of("ROLE_OWNER", "ADMIN_ROLE")) {
            signIn(1L, role);
            allowed(access::assertMayEditPatients);
            allowed(access::assertMayIssueTokens);
            allowed(access::assertMayCancelTokens);
            allowed(access::assertMaySetDoctorDay);
            allowed(access::assertClinicAdmin);
        }
    }

    @Test
    void a_custom_set_holding_doctor_manage_may_manage_doctors() {
        signIn(950L, "ROLE_BUSINESS_USER", "clinic.doctor.manage");
        allowed(access::assertClinicAdmin);
    }
}
