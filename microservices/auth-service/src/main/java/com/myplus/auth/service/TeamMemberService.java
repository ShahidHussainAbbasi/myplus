package com.myplus.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.auth.entity.Membership;
import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * TEAM-1 (2026-10-13) — what the owner can do to a team member after adding them: resend the invitation, REMOVE them
 * (reversible: the membership becomes INACTIVE, nothing is deleted — Odoo archives, Slack deactivates) and reactivate.
 *
 * <p>Who may: the owner, or an administrator for an ordinary member (the same rule as adding one). Never the owner,
 * never oneself, only a member of the caller's own business. Every action is audited.
 *
 * <p>A removed member is signed out everywhere, cannot switch back in (OrganizationService.isMember), and is refused at
 * sign-in (AuthService.buildClaims) — instead of being handed a brand-new empty business.
 */
@Service
@RequiredArgsConstructor
public class TeamMemberService {

    static final String ACTIVE = "ACTIVE";
    static final String INACTIVE = "INACTIVE";

    private final MembershipRepository memberships;
    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final ControlPlaneAuditService audit;
    private final AuthService authService;

    @Transactional
    public void remove(Long callerUserId, Long orgId, boolean callerIsOwner, Long targetUserId, String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("Say why this person is being removed.");
        Membership m = target(callerUserId, orgId, callerIsOwner, targetUserId);
        if (INACTIVE.equalsIgnoreCase(m.getStatus())) throw new ValidationException("This person was already removed.");
        m.setStatus(INACTIVE);
        memberships.save(m);
        refreshTokens.deleteByUserId(targetUserId);          // signed out on every device
        audit.tenantAction(ControlPlaneAuditService.MEMBER_REMOVE, ControlPlaneAuditService.ENTITY_MEMBER,
                emailOf(targetUserId), ACTIVE, INACTIVE, reason.trim());
    }

    @Transactional
    public void reactivate(Long callerUserId, Long orgId, boolean callerIsOwner, Long targetUserId) {
        Membership m = target(callerUserId, orgId, callerIsOwner, targetUserId);
        if (!INACTIVE.equalsIgnoreCase(m.getStatus())) throw new ValidationException("This person is already active.");
        m.setStatus(ACTIVE);
        memberships.save(m);
        audit.tenantAction(ControlPlaneAuditService.MEMBER_REACTIVATE, ControlPlaneAuditService.ENTITY_MEMBER,
                emailOf(targetUserId), INACTIVE, ACTIVE, null);
    }

    @Transactional
    public void resendInvite(Long callerUserId, Long orgId, boolean callerIsOwner, Long targetUserId) {
        Membership m = target(callerUserId, orgId, callerIsOwner, targetUserId);
        if (INACTIVE.equalsIgnoreCase(m.getStatus())) throw new ValidationException("Reactivate this person first.");
        User u = users.findById(targetUserId).orElseThrow(() -> new ValidationException("No such member."));
        if (u.getInvitedAt() == null) {
            throw new ValidationException("This person has already set a password — they can use “Forgot password?” on the login page.");
        }
        authService.sendPasswordResetEmail(u.getEmail());   // a fresh link; every older one is cancelled
        audit.tenantAction(ControlPlaneAuditService.INVITE_RESEND, ControlPlaneAuditService.ENTITY_MEMBER,
                u.getEmail(), null, null, null);
    }

    /** The target's membership in the caller's business, after the who-may rules. */
    private Membership target(Long callerUserId, Long orgId, boolean callerIsOwner, Long targetUserId) {
        if (orgId == null || targetUserId == null) throw new ValidationException("No such member.");
        if (targetUserId.equals(callerUserId)) throw new ValidationException("You cannot do this to your own account.");
        Membership m = memberships.findByUserIdAndOrganizationId(targetUserId, orgId)
                .orElseThrow(() -> new ValidationException("No such member in this business."));
        String role = m.getRole() == null ? "" : m.getRole().toUpperCase(java.util.Locale.ROOT);
        if ("OWNER".equals(role)) throw new ValidationException("The owner cannot be removed.");
        if (!callerIsOwner && !"USER".equals(role)) {
            throw new ValidationException("Only the owner can do this to an administrator.");
        }
        return m;
    }

    private String emailOf(Long userId) {
        return users.findById(userId).map(User::getEmail).orElse(String.valueOf(userId));
    }
}
