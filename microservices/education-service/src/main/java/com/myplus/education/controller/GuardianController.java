package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.GuardianDTO;
import com.myplus.education.entity.Guardian;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.common.settings.SettingsService;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/** Flat (legacy) Guardian endpoints. userId-scoped. */
@Controller
public class GuardianController {

    @Autowired
    private GuardianRepository guardianRepository;
    @Autowired
    private StudentRepository studentRepository;   // derive a guardian's branch from the students who reference it
    @Autowired
    private SettingsService settingsService;       // reads the org's edu.guardian.branchScoped policy
    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
    @Autowired
    private AppUtil appUtil;

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private GuardianDTO toDto(Guardian g) {
        GuardianDTO dto = new GuardianDTO();
        dto.setId(g.getId());
        dto.setUserId(g.getUserId());
        dto.setName(g.getName());
        dto.setEmail(g.getEmail());
        dto.setMobile(g.getMobile());
        dto.setPhone(g.getPhone());
        dto.setTempAddress(g.getTempAddress());
        dto.setPermAddress(g.getPermAddress());
        dto.setGender(g.getGender());
        dto.setRelation(g.getRelation());
        dto.setOccupation(g.getOccupation());
        dto.setStatus(g.getStatus());
        dto.setCnic(g.getCnic());
        dto.setDatedStr(appUtil.getDateStr(g.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(g.getUpdated()));
        return dto;
    }

    /**
     * Guardian branch visibility — OFF by default (org-wide: a parent may have children at several campuses),
     * opt-in per org via {@code edu.guardian.branchScoped} on the Configuration screen. When on, a guardian is
     * visible only if a student in the caller's accessible branches references them (derived via
     * Student.guardianId — no guardian.school_id needed, and a cross-campus parent stays visible from either
     * branch). Owner/super or a caller with no branch grants ⇒ org-wide regardless.
     */
    private List<Guardian> branchVisible(List<Guardian> rows) {
        if (!settingsService.getBool("edu.guardian.branchScoped")) return rows;   // org-wide (default)
        if (requestUtil.isOwnerSuper()) return rows;
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return rows;
        java.util.Set<Long> visibleGuardianIds = studentRepository.findScopedBySchools(orgId(), schools).stream()
                .map(Student::getGuardianId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return rows.stream().filter(g -> g.getId() != null && visibleGuardianIds.contains(g.getId()))
                .collect(Collectors.toList());
    }

    @RequestMapping(value = "/getUserGuardian", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserGuardian(final HttpServletRequest request) {
        try {
            List<Guardian> objs = branchVisible(guardianRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserGuardians", method = RequestMethod.GET)
    @ResponseBody
    public String getUserGuardians(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Guardian> objs = branchVisible(guardianRepository.findScoped(orgId(), userId()));
            sb.append("<option value=''>Nothing Selected</option>");
            objs.forEach(d -> {
                if (d != null && d.getId() != null) {
                    sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
                }
            });
        } catch (Exception e) {
            appUtil.le(getClass(), e);
        }
        return sb.toString();
    }

    @RequestMapping(value = "/getAllGuardian", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllGuardian(final HttpServletRequest request) {
        try {
            // Tenant- + (opt-in) branch-scoped: see branchVisible().
            List<Guardian> all = branchVisible(guardianRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addGuardian", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addGuardian(final GuardianDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = guardianRepository.findScoped(orgId, userId).stream()
                        .anyMatch(g -> g.getName() != null && g.getName().equalsIgnoreCase(dto.getName())
                                && g.getCnic() != null && g.getCnic().equalsIgnoreCase(dto.getCnic()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Guardian '" + dto.getName() + "' already exists");
                }
            }
            Guardian obj = (dto.getId() != null)
                    ? guardianRepository.findById(dto.getId()).orElseGet(Guardian::new)
                    : new Guardian();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setEmail(dto.getEmail());
            obj.setMobile(dto.getMobile());
            obj.setPhone(dto.getPhone());
            obj.setTempAddress(dto.getTempAddress());
            obj.setPermAddress(dto.getPermAddress());
            obj.setGender(dto.getGender());
            obj.setRelation(dto.getRelation());
            obj.setOccupation(dto.getOccupation());
            obj.setStatus(dto.getStatus());
            obj.setCnic(dto.getCnic());
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Guardian saved = guardianRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteGuardian", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse deleteGuardian(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (StringUtils.isEmpty(ids)) {
                return new GenericResponse(appUtil.SUCCESS, "Invalid input");
            }
            // Anti-IDOR: only rows in the caller's own tenant are deleted (see ScopedDeleter).
            scopedDeleter.deleteScoped(guardianRepository, ids,
                    Guardian::getOrganizationId, Guardian::getUserId, null);
            return new GenericResponse(appUtil.SUCCESS, "Deleted successfully");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse(appUtil.ERROR, e.getMessage());
        }
    }
}
