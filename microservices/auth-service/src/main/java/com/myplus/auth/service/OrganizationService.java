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

    @Value("${app.trial-days:14}")
    private int trialDays;

    /**
     * Create a new tenant for {@code owner} with an OWNER membership, applying the plan's entitlement
     * policy: TRIAL is time-boxed ({@code trialEndsAt = now + app.trial-days}, uncapped); DEMO sandboxes
     * get a 50/module cap; FREE/PRO are uncapped with no expiry. This is the signup/provisioning path —
     * {@link #getOrCreatePrimaryOrg} remains only as a legacy safety net.
     */
    @Transactional
    public Organization createTenant(User owner, String name, String type, String plan) {
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
        return org;
    }

    /** Look up an organization by id (used to enrich JWT claims with plan/trial). */
    public Organization findById(Long id) {
        return organizationRepository.findById(id).orElse(null);
    }

    /** Return the user's primary organization, creating it (+ OWNER membership) if none exists. */
    @Transactional
    public Organization getOrCreatePrimaryOrg(User user) {
        List<Organization> owned = organizationRepository.findByOwnerUserId(user.getId());
        if (!owned.isEmpty()) {
            return owned.get(0);
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
                    org.getId().equals(activeOrgId)));
        }
        return views;
    }

    /** Lightweight view of an organization for the switcher UI. */
    public record OrgView(Long id, String name, String role, boolean active) {
    }

    private String defaultOrgName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return (name.isEmpty() ? user.getEmail() : name) + "'s organization";
    }
}
