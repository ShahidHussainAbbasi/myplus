package com.myplus.auth.service;

import com.myplus.auth.entity.Membership;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Organization/membership operations. Today a user is their own tenant: on first login we
 * auto-create their organization ("tenant #1") + an OWNER membership, so all existing single-owner
 * data has a home once domains move from userId- to org-scoping. Multi-org (staff/students joining
 * several orgs) is supported by the model and added when those join flows are built.
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    /**
     * ONB-1 — the tenant's business type is written with the tenant itself.
     *
     * <p>In the SAME transaction as the organization, so a tenant can never exist in the window where it has
     * no shape and therefore resolves to {@code GENERAL} — every capability on. That window would be short,
     * silent, and reachable only by a crash between two commits, which is the worst combination.
     */
    private final com.myplus.auth.repository.OrgSettingRepository orgSettingRepository;

    @Value("${app.trial-days:14}")
    private int trialDays;

    /**
     * Create a new tenant for {@code owner} with an OWNER membership, applying the plan's entitlement
     * policy: TRIAL is time-boxed ({@code trialEndsAt = now + app.trial-days}, uncapped); DEMO sandboxes
     * get a 50/module cap; FREE/PRO are uncapped with no expiry. This is the signup/provisioning path —
     * {@link #getOrCreatePrimaryOrg} remains only as a legacy safety net.
     */
    /** Back-compatible overload: no shape stated. See the {@code shape} parameter's javadoc below. */
    @Transactional
    public Organization createTenant(User owner, String name, String type, String plan) {
        return createTenant(owner, name, type, plan, null);
    }

    /**
     * @param shape ONB-1 — the tenant's business type ({@code Shape} code), written as {@code org.shape}.
     *
     * <p>Null is accepted here and refused at the OPERATOR path ({@code provisionTenant}), which is where the
     * ruling applies. <b>Self-signup still creates a {@code GENERAL} tenant</b> — the registration form has no
     * business-type question yet, so a trial user meets every capability. That is a known follow-on, recorded
     * rather than silently half-fixed: it is the same defect on a different door.
     */
    @Transactional
    public Organization createTenant(User owner, String name, String type, String plan, String shape) {
        LocalDateTime trialEnds = "TRIAL".equals(plan) ? LocalDateTime.now().plusDays(trialDays) : null;
        Integer entryCap = "DEMO".equals(plan) ? 50 : null;
        Organization org = organizationRepository.save(Organization.builder()
                .name((name == null || name.isBlank()) ? defaultOrgName(owner) : name.trim())
                .type(type)
                .ownerUserId(owner.getId())
                .plan(plan)
                .trialEndsAt(trialEnds)
                .entryCap(entryCap)
                .status("ACTIVE")
                .build());
        membershipRepository.save(Membership.builder()
                .userId(owner.getId())
                .organizationId(org.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());
        if (shape != null && !shape.isBlank()) {
            orgSettingRepository.save(com.myplus.auth.entity.OrgSetting.builder()
                    .organizationId(org.getId())
                    .settingKey(com.myplus.common.settings.Shape.settingKey())
                    .settingValue(com.myplus.common.settings.Shape.byCode(shape).code())
                    .updated(LocalDateTime.now())
                    .build());
        }
        return org;
    }


    /** Look up an organization by id (used to enrich JWT claims with plan/trial). */
    public Organization findById(Long id) {
        return organizationRepository.findById(id).orElse(null);
    }

    /** Add {@code userId} to an EXISTING organization with the given membership role (ADMIN/USER).
     *  Idempotent — returns the existing membership if the user is already a member. */
    @Transactional
    public Membership addMember(Long userId, Long orgId, String role) {
        return membershipRepository.findByUserIdAndOrganizationId(userId, orgId)
                .orElseGet(() -> membershipRepository.save(Membership.builder()
                        .userId(userId)
                        .organizationId(orgId)
                        .role(role)
                        .status("ACTIVE")
                        .build()));
    }

    /** All memberships in an organization (for the owner's team list). */
    public List<Membership> membersOf(Long orgId) {
        return membershipRepository.findByOrganizationId(orgId);
    }

    /** Return the user's primary organization, creating it (+ OWNER membership) if none exists.
     *  Precedence: an org they OWN, else one they are an ACTIVE MEMBER of, else a new personal org.
     *  The membership step is what makes a team member (created by an owner via createOrgUser — they hold a
     *  membership but own nothing) resolve to their employer's org. Without it their first login minted them
     *  an empty personal org and the company's catalog/customers/sales became invisible to them. */
    @Transactional
    public Organization getOrCreatePrimaryOrg(User user) {
        List<Organization> owned = organizationRepository.findByOwnerUserId(user.getId());
        if (!owned.isEmpty()) {
            return owned.get(0);
        }
        Organization member = membershipRepository.findByUserId(user.getId()).stream()
                .filter(m -> m.getStatus() == null || "ACTIVE".equalsIgnoreCase(m.getStatus()))
                .map(m -> organizationRepository.findById(m.getOrganizationId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (member != null) {
            return member;
        }
        Organization org = organizationRepository.save(Organization.builder()
                .name(defaultOrgName(user))
                .type(user.getUserType())
                .ownerUserId(user.getId())
                .status("ACTIVE")
                .build());
        if (membershipRepository.findByUserIdAndOrganizationId(user.getId(), org.getId()).isEmpty()) {
            membershipRepository.save(Membership.builder()
                    .userId(user.getId())
                    .organizationId(org.getId())
                    .role("OWNER")
                    .status("ACTIVE")
                    .build());
        }
        return org;
    }

    public List<Membership> membershipsOf(Long userId) {
        return membershipRepository.findByUserId(userId);
    }

    /** Whether the user belongs to the given organization (gate for org switching). */
    public boolean isMember(Long userId, Long orgId) {
        return membershipRepository.findByUserIdAndOrganizationId(userId, orgId).isPresent();
    }

    /** The organizations the user belongs to, with role + which one is currently active. */
    @Transactional
    public List<OrgView> listForUser(Long userId, Long activeOrgId) {
        List<OrgView> views = new ArrayList<>();
        for (Membership m : membershipRepository.findByUserId(userId)) {
            Organization org = organizationRepository.findById(m.getOrganizationId()).orElse(null);
            if (org == null) {
                continue;
            }
            views.add(new OrgView(
                    org.getId(),
                    org.getName(),
                    m.getRole(),
                    org.getId().equals(activeOrgId),
                    org.getType()));
        }
        return views;
    }

    /** Lightweight view of an organization for the switcher UI. */
    public record OrgView(Long id, String name, String role, boolean active, String type) {
        // B2B P0.5: `type` lets the switcher label each org with its module — "Springfield High —
        // Education" vs "Springfield Store — Retail" — which is the difference between a usable
        // switcher and a list of indistinguishable names for a multi-module customer.
    }

    private String defaultOrgName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return (name.isEmpty() ? user.getEmail() : name) + "'s organization";
    }
}
