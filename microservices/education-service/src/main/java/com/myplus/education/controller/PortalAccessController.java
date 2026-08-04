package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.*;
import com.myplus.education.repository.*;
import com.myplus.education.service.EduAuditService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Slice 3.1 — the SCHOOL side of the guardian portal: who is granted access, and who has it withdrawn.
 * Design: microservices/docs/slices/edu-3.1-guardian-portal.md
 *
 * <p>Separate from {@link GuardianPortalController} on purpose. That one is the outsider's surface and is
 * read-only; this one is staff-facing and is the only place access is granted. Keeping them apart means a
 * guardian session can never reach an endpoint that changes access, however the roles evolve.
 *
 * <p><b>{@code ADMIN_PRIVILEGE}</b> — granting an outsider sight of a child's record is a policy act, the
 * same tier as fee settings and report-card publication, not day-to-day teacher work.
 */
@Controller
public class PortalAccessController {

    @Autowired private GuardianPortalAccessRepository portalAccessRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private EduAuditService auditService;
    @Autowired private RequestUtil requestUtil;
    @Autowired private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private static Long parseLong(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    /** Who has portal access, including revoked rows — they are kept, never deleted. */
    @RequestMapping(value = "/getPortalAccess", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getPortalAccess() {
        try {
            Long org = orgId(), uid = userId();
            List<Map<String, Object>> out = new ArrayList<>();
            for (GuardianPortalAccess a : portalAccessRepository.findScoped(org, uid)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("guardianId", a.getGuardianId());
                m.put("guardianName", a.getGuardianName());
                m.put("email", a.getEmail());
                m.put("status", a.getStatus() == null ? null : a.getStatus().name());
                m.put("invitedOn", a.getInvitedOn() == null ? null : a.getInvitedOn().toString());
                m.put("activatedOn", a.getActivatedOn() == null ? null : a.getActivatedOn().toString());
                // How many children this access would actually reach — the number that makes the
                // consequence of granting it legible, rather than a name and an email in a list.
                m.put("childCount", studentRepository
                        .findByGuardianScoped(a.getGuardianId(), org).size());
                out.add(m);
            }
            return new GenericResponse("SUCCESS", "", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Invite a guardian.
     *
     * <p>D3 — invitation only. There is no endpoint anywhere that lets a guardian claim a child by typing an
     * enrolment number; the school, which already knows who the guardians are, grants access explicitly.
     *
     * <p>The email is taken from the GUARDIAN RECORD, never from the request. Accepting an email here would
     * let a staff member point a child's portal at any address they liked, with no trace in the guardian
     * record that they had done so.
     */
    @RequestMapping(value = "/invitePortalAccess", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse invitePortalAccess(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long guardianId = parseLong(request.getParameter("guardianId"));
            if (guardianId == null) return new GenericResponse("ERROR", "Guardian is required");

            Guardian guardian = guardianRepository.findByIdScoped(guardianId, org, uid).orElse(null);
            if (guardian == null) return new GenericResponse("NOT_FOUND", "Guardian not found");
            if (!StringUtils.hasText(guardian.getEmail())) {
                return new GenericResponse("FAILED",
                        "This guardian has no email address on record. Add one before inviting them.");
            }

            GuardianPortalAccess access = portalAccessRepository
                    .findByGuardianScoped(guardianId, org).orElse(null);
            if (access == null) {
                access = GuardianPortalAccess.builder()
                        .guardianId(guardianId)
                        .userId(uid).organizationId(org).dated(LocalDateTime.now())
                        .build();
            } else if (access.getStatus() == PortalStatus.ACTIVE) {
                return new GenericResponse("SUCCESS",
                        guardian.getName() + " already has portal access");
            }
            // Re-inviting is the EXPLICIT act that moves access to a corrected email — the snapshot is
            // never silently refreshed from the guardian record (see the entity javadoc).
            access.setEmail(guardian.getEmail().trim());
            access.setGuardianName(guardian.getName());
            access.setStatus(PortalStatus.INVITED);
            access.setInvitedOn(LocalDate.now());
            access.setRevokedOn(null);
            access.setUpdated(LocalDateTime.now());
            portalAccessRepository.save(access);

            int children = studentRepository.findByGuardianScoped(guardianId, org).size();
            auditService.record("PORTAL_ACCESS_INVITED", "Guardian", String.valueOf(guardianId),
                    "email=" + access.getEmail() + " children=" + children);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", access.getId());
            out.put("childCount", children);
            // The count is surfaced because "you have just given this address sight of 2 children's
            // records" is the fact the person clicking needs, and it is invisible otherwise.
            return new GenericResponse("SUCCESS",
                    guardian.getName() + " invited — this grants sight of " + children + " child(ren)", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    /**
     * Withdraw access. The row is kept as REVOKED, never deleted.
     *
     * <p>"This person used to be able to read this child's record" is exactly what an investigation needs,
     * and deleting the row is how a school loses the ability to answer it — the same rule as a superseded
     * report card (1.5 D5) and a reversed promotion (1.6 D7).
     */
    @RequestMapping(value = "/revokePortalAccess", method = RequestMethod.POST)
    @ResponseBody
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    @Transactional
    public GenericResponse revokePortalAccess(final HttpServletRequest request) {
        try {
            Long org = orgId(), uid = userId();
            Long id = parseLong(request.getParameter("id"));
            if (id == null) return new GenericResponse("ERROR", "Access record is required");

            GuardianPortalAccess access = portalAccessRepository.findByIdScoped(id, org, uid).orElse(null);
            if (access == null) return new GenericResponse("NOT_FOUND", "Access record not found");
            if (access.getStatus() == PortalStatus.REVOKED) {
                return new GenericResponse("SUCCESS", "Access is already revoked");
            }
            access.setStatus(PortalStatus.REVOKED);
            access.setRevokedOn(LocalDate.now());
            access.setUpdated(LocalDateTime.now());
            portalAccessRepository.save(access);

            auditService.record("PORTAL_ACCESS_REVOKED", "Guardian",
                    String.valueOf(access.getGuardianId()), "email=" + access.getEmail());
            // Takes effect on the guardian's NEXT request: nothing about their access is cached (D1).
            return new GenericResponse("SUCCESS", "Portal access revoked");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }
}
