package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.auth.entity.Membership;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.UserRepository;

/** TEAM-1 — who may resend, remove and reactivate a team member, and what each does. */
class TeamMemberServiceTest {

    private static final long ORG = 15L, OWNER = 1L, ADMIN = 2L, DESK = 3L, OTHER_ADMIN = 4L;

    MembershipRepository memberships;
    UserRepository users;
    RefreshTokenService refreshTokens;
    ControlPlaneAuditService audit;
    AuthService authService;
    TeamMemberService service;
    Membership desk;
    Membership otherAdmin;
    User deskUser;

    private static Membership member(long user, String role, String status) {
        return Membership.builder().userId(user).organizationId(ORG).role(role).status(status).build();
    }

    @BeforeEach
    void setUp() {
        memberships = mock(MembershipRepository.class);
        users = mock(UserRepository.class);
        refreshTokens = mock(RefreshTokenService.class);
        audit = mock(ControlPlaneAuditService.class);
        authService = mock(AuthService.class);
        service = new TeamMemberService(memberships, users, refreshTokens, audit, authService);
        desk = member(DESK, "USER", "ACTIVE");
        otherAdmin = member(OTHER_ADMIN, "ADMIN", "ACTIVE");
        when(memberships.findByUserIdAndOrganizationId(DESK, ORG)).thenReturn(Optional.of(desk));
        when(memberships.findByUserIdAndOrganizationId(OTHER_ADMIN, ORG)).thenReturn(Optional.of(otherAdmin));
        when(memberships.findByUserIdAndOrganizationId(OWNER, ORG)).thenReturn(Optional.of(member(OWNER, "OWNER", "ACTIVE")));
        deskUser = User.builder().id(DESK).email("desk@x.pk").invitedAt(LocalDateTime.now()).build();
        when(users.findById(DESK)).thenReturn(Optional.of(deskUser));
    }

    @Test
    void the_owner_removes_a_member_reversibly_signs_them_out_and_it_is_audited() {
        service.remove(OWNER, ORG, true, DESK, "Left the clinic");
        assertThat(desk.getStatus()).isEqualTo("INACTIVE");
        verify(memberships).save(desk);
        verify(refreshTokens).deleteByUserId(DESK);
        verify(audit).tenantAction(eq(ControlPlaneAuditService.MEMBER_REMOVE), eq(ControlPlaneAuditService.ENTITY_MEMBER),
                eq("desk@x.pk"), eq("ACTIVE"), eq("INACTIVE"), eq("Left the clinic"));
        service.reactivate(OWNER, ORG, true, DESK);
        assertThat(desk.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void removing_needs_a_reason() {
        assertThatThrownBy(() -> service.remove(OWNER, ORG, true, DESK, " ")).hasMessageContaining("Say why");
        verify(refreshTokens, never()).deleteByUserId(any());
    }

    @Test
    void nobody_removes_the_owner_or_themselves() {
        assertThatThrownBy(() -> service.remove(ADMIN, ORG, false, OWNER, "x")).hasMessageContaining("owner cannot be removed");
        assertThatThrownBy(() -> service.remove(DESK, ORG, false, DESK, "x")).hasMessageContaining("your own account");
    }

    @Test
    void an_admin_acts_on_ordinary_members_only() {
        assertThatThrownBy(() -> service.remove(ADMIN, ORG, false, OTHER_ADMIN, "x")).hasMessageContaining("Only the owner");
        service.remove(ADMIN, ORG, false, DESK, "x");
        assertThat(desk.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void a_member_of_another_business_is_not_found() {
        when(memberships.findByUserIdAndOrganizationId(99L, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.remove(OWNER, ORG, true, 99L, "x")).hasMessageContaining("No such member");
    }

    @Test
    void an_invitation_is_resent_only_to_someone_who_has_not_set_a_password() {
        service.resendInvite(OWNER, ORG, true, DESK);
        verify(authService).sendPasswordResetEmail("desk@x.pk");
        deskUser.setInvitedAt(null);
        assertThatThrownBy(() -> service.resendInvite(OWNER, ORG, true, DESK)).hasMessageContaining("already set a password");
    }

    @Test
    void a_removed_member_is_reactivated_before_an_invitation_is_resent() {
        desk.setStatus("INACTIVE");
        assertThatThrownBy(() -> service.resendInvite(OWNER, ORG, true, DESK)).hasMessageContaining("Reactivate");
        verify(authService, never()).sendPasswordResetEmail(anyString());
    }
}
