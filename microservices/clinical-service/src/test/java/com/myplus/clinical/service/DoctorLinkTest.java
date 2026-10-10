package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.myplus.clinical.config.AppointmentDirectoryClient;
import com.myplus.clinical.config.AppointmentDirectoryClient.Doctor;
import com.myplus.clinical.config.AppointmentDirectoryClient.Envelope;
import com.myplus.clinical.dto.QueueDtos.RegisterDoctorRequest;
import com.myplus.clinical.entity.ClinicProvider;
import com.myplus.clinical.repository.ClinicProviderRepo;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.clinical.repository.ProviderDayRepo;
import com.myplus.clinical.repository.QueueTokenRepo;
import com.myplus.common.security.AuthenticatedUser;

/**
 * HMS H2 — a doctor IS a login: the link grants clinical access; a linked doctor works only their own queue; only the
 * owner or an admin registers and links doctors; an admin never links themselves.
 *
 * <p>Runs the REAL {@link ClinicAccess} against a signed-in user in the security context (only repositories and the
 * directory are mocked), so the rules tested are the ones that run.
 */
class DoctorLinkTest {

    ClinicProviderRepo providers; QueueTokenRepo tokens; ProviderDayRepo days; TokenWriter writer;
    AppointmentDirectoryClient directory; ClinicAuditService audit;
    ClinicAccess access; QueueService queue;

    private static void signIn(long userId, String... authorities) {
        List<SimpleGrantedAuthority> auths = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        AuthenticatedUser u = new AuthenticatedUser(userId, "u" + userId + "@x.pk", auths, 7L);
        u.setCapabilities(java.util.Set.of("clinic"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auths));
    }

    private static ClinicProvider row(long providerId, String letter, Long userId) {
        ClinicProvider r = new ClinicProvider();
        r.setOrganizationId(7L); r.setProviderId(providerId); r.setTokenPrefix(letter); r.setUserId(userId);
        return r;
    }

    @BeforeEach
    void setUp() {
        providers = mock(ClinicProviderRepo.class); tokens = mock(QueueTokenRepo.class); days = mock(ProviderDayRepo.class);
        writer = mock(TokenWriter.class); directory = mock(AppointmentDirectoryClient.class); audit = mock(ClinicAuditService.class);
        access = new ClinicAccess(providers);
        queue = new QueueService(tokens, mock(PatientRepo.class), providers, days, writer, directory, access,
                mock(ClinicSettings.class), audit);
        when(directory.doctors()).thenReturn(new Envelope<>(true, null, List.of(
                Doctor.builder().id(11L).name("Dr Ahmed").build(), Doctor.builder().id(12L).name("Dr Sana").build())));
        when(providers.findByOrganizationIdAndProviderId(7L, 11L)).thenReturn(Optional.of(row(11L, "A", null)));
        when(providers.findByOrganizationIdAndProviderId(7L, 12L)).thenReturn(Optional.of(row(12L, "B", 501L)));
        when(providers.findByOrganizationId(7L)).thenReturn(List.of(row(11L, "A", null), row(12L, "B", 501L)));
        when(providers.findByOrganizationIdAndUserId(7L, 501L)).thenReturn(Optional.of(row(12L, "B", 501L)));
        when(providers.saveAndFlush(any(ClinicProvider.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokens.board(any(), any())).thenReturn(List.of());
        when(days.findByOrganizationIdAndVisitDate(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void signOut() { SecurityContextHolder.clearContext(); }

    @Test
    void a_linked_login_may_consult_without_the_doctor_set_and_an_unlinked_desk_may_not() {
        signIn(501L, "ROLE_BUSINESS_USER");   // Dr Sana's login: no clinic.consult authority
        assertThatCode(() -> access.assertCanConsult()).doesNotThrowAnyException();
        signIn(600L, "ROLE_BUSINESS_USER");   // the front desk
        assertThatThrownBy(() -> access.assertCanConsult()).isInstanceOf(AccessDeniedException.class).hasMessageContaining("Only a doctor");
    }

    @Test
    void a_linked_doctor_works_only_their_own_queue_while_the_owner_covers_every_queue() {
        signIn(501L, "ROLE_BUSINESS_USER");
        assertThatCode(() -> access.assertMayWorkProvider(12L, "Dr Sana")).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.assertMayWorkProvider(11L, "Dr Ahmed"))
                .isInstanceOf(AccessDeniedException.class).hasMessage("This is Dr Ahmed's patient. You see your own queue.");
        signIn(1L, "ROLE_OWNER", "clinic.consult");   // the owner, not linked
        assertThatCode(() -> access.assertMayWorkProvider(11L, "Dr Ahmed")).doesNotThrowAnyException();
    }

    @Test
    void an_owner_linked_as_a_doctor_still_covers_every_queue() {
        // the owner of a small clinic linked their own login (My queue opens on them) — they are still the owner
        when(providers.findByOrganizationIdAndUserId(7L, 1L)).thenReturn(Optional.of(row(11L, "A", 1L)));
        signIn(1L, "ROLE_OWNER", "clinic.consult");
        assertThatCode(() -> access.assertMayWorkProvider(12L, "Dr Sana")).doesNotThrowAnyException();
    }

    @Test
    void only_the_owner_or_an_admin_adds_registers_or_links_a_doctor() {
        signIn(600L, "ROLE_BUSINESS_USER");   // the front desk — this was allowed before H2
        assertThatThrownBy(() -> queue.addDoctor(com.myplus.clinical.dto.QueueDtos.NewDoctorRequest.builder().name("Dr X").build()))
                .isInstanceOf(AccessDeniedException.class).hasMessage("Only the owner or an admin can register doctors.");
        assertThatThrownBy(() -> queue.link(11L, 700L)).isInstanceOf(AccessDeniedException.class);
        verify(directory, never()).createDoctor(any());
        verify(providers, never()).saveAndFlush(any());
    }

    @Test
    void an_admin_links_a_login_and_it_is_audited_but_never_their_own_login() {
        signIn(300L, "ADMIN_ROLE");
        var v = queue.link(11L, 700L);
        assertThat(v.getName()).isEqualTo("Dr Ahmed");
        verify(providers).saveAndFlush(org.mockito.ArgumentMatchers.argThat(r -> r.getUserId() == 700L && r.getLinkedBy() == 300L));
        verify(audit).record(org.mockito.ArgumentMatchers.argThat(a -> "CLINIC_DOCTOR_LINK".equals(a.getAction())));
        assertThatThrownBy(() -> queue.link(11L, 300L)).hasMessage("You cannot link your own login as a doctor. The owner can.");
    }

    @Test
    void the_owner_may_link_themselves_the_one_doctor_of_a_small_clinic() {
        signIn(1L, "ROLE_OWNER", "clinic.consult");
        assertThatCode(() -> queue.link(11L, 1L)).doesNotThrowAnyException();
    }

    @Test
    void a_login_is_one_doctor_and_a_linked_doctor_is_not_relinked_silently() {
        signIn(300L, "ADMIN_ROLE");
        assertThatThrownBy(() -> queue.link(11L, 501L)).hasMessage("That login is already linked to another doctor of this clinic.");
        assertThatThrownBy(() -> queue.link(12L, 700L)).hasMessage("Dr Sana is linked to another login. Unlink it first.");
    }

    @Test
    void register_refuses_an_unlinkable_login_before_any_doctor_is_created() {
        signIn(300L, "ADMIN_ROLE");
        assertThatThrownBy(() -> queue.registerDoctor(RegisterDoctorRequest.builder().name("Dr New").userId(501L).build()))
                .hasMessage("That login is already linked to another doctor of this clinic.");
        verify(directory, never()).createDoctor(any());   // no stray doctor is left behind
    }
}
