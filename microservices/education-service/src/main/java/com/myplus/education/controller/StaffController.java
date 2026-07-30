package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
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
import com.myplus.common.settings.SettingsService;
import com.myplus.education.dto.StaffDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Staff;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/** Flat (legacy) Staff endpoints. userId-scoped; links grades (EAGER) the staff teaches. */
@Controller
public class StaffController {

    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private GradeRepository gradeRepository;
    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
    @Autowired
    private AppUtil appUtil;
    @Autowired
    private SettingsService settingsService;   // reads the org's edu.staff.branchScoped policy

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private StaffDTO toDto(Staff s) {
        StaffDTO dto = new StaffDTO();
        dto.setId(s.getId());
        dto.setUserId(s.getUserId());
        dto.setName(s.getName());
        dto.setEmail(s.getEmail());
        dto.setMobile(s.getMobile());
        dto.setPhone(s.getPhone());
        dto.setAddress(s.getAddress());
        dto.setDesignation(s.getDesignation());
        dto.setGender(s.getGender());
        dto.setQualification(s.getQualification());
        dto.setMartialStatus(s.getMartialStatus());
        dto.setStatus(s.getStatus());
        dto.setStaffDOBStr(s.getStaffDOB() == null ? "" : appUtil.getLocalDateStr(s.getStaffDOB()));
        dto.setTimeInStr(s.getTimeIn() == null ? "" : s.getTimeIn().toString());
        dto.setTimeOutStr(s.getTimeOut() == null ? "" : s.getTimeOut().toString());
        dto.setDatedStr(appUtil.getDateStr(s.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(s.getUpdated()));
        if (s.getGrades() != null) {
            dto.setGradeIds(s.getGrades().stream().map(Grade::getId).collect(Collectors.toSet()));
            dto.setGradeNames(s.getGrades().stream().map(Grade::getName).collect(Collectors.toSet()));
        }
        return dto;
    }

    /**
     * Staff branch visibility — OFF by default (org-wide), opt-in per org via {@code edu.staff.branchScoped}
     * on the Configuration screen. When on, a staff member is visible only if they are assigned to a class at
     * one of the caller's accessible branches (derived via Staff.grades → Grade.schoolId — no staff.school_id
     * needed, and a teacher covering two campuses stays visible from either).
     *
     * Three escape hatches, matching GuardianController: setting off, owner/super, or a caller with no branch
     * grants ⇒ org-wide. A staff member assigned to NO class has no derivable branch and stays visible under
     * every setting — hiding them would make rows vanish the moment the toggle flips (design D4).
     */
    private List<Staff> branchVisible(List<Staff> rows) {
        if (!settingsService.getBool("edu.staff.branchScoped")) return rows;   // org-wide (default)
        if (requestUtil.isOwnerSuper()) return rows;
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return rows;
        // ONE query for the whole request — the branch's class ids — then an in-memory membership test.
        java.util.Set<Long> branchGradeIds = gradeRepository.findScopedBySchools(orgId(), schools).stream()
                .map(Grade::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return rows.stream().filter(s -> {
            List<Grade> assigned = s.getGrades();
            if (assigned == null || assigned.isEmpty()) return true;   // D4: unattributed stays visible
            return assigned.stream().anyMatch(g -> g != null && branchGradeIds.contains(g.getId()));
        }).collect(Collectors.toList());
    }

    @RequestMapping(value = "/getUserStaff", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserStaff(final HttpServletRequest request) {
        try {
            List<Staff> objs = branchVisible(staffRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserStaffs", method = RequestMethod.GET)
    @ResponseBody
    public String getUserStaffs(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            // Scoped too: a picker that offered staff the list screen hides would be a way around the policy.
            List<Staff> objs = branchVisible(staffRepository.findScoped(orgId(), userId()));
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

    @RequestMapping(value = "/getAllStaff", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllStaff(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all staff in the active organization, not every tenant's — and,
            // when the branch policy is on, all staff at the caller's branches.
            List<Staff> all = branchVisible(staffRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addStaff", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addStaff(final StaffDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = staffRepository.findScoped(orgId, userId).stream()
                        .anyMatch(s -> s.getName() != null && s.getName().equalsIgnoreCase(dto.getName()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Staff '" + dto.getName() + "' already exists");
                }
            }
            // Anti-IDOR: an edit names a row by a client-supplied id, so it must be resolved WITHIN the
            // caller's tenant. A bare findById followed by the setOrganizationId below would have moved
            // another org's row into this one — taking it from its owner, not merely editing it.
            Staff obj;
            if (dto.getId() != null) {
                obj = staffRepository.findByIdScoped(dto.getId(), orgId, userId).orElse(null);
                if (obj == null) return new GenericResponse("NOT_FOUND", "Staff not found");
            } else {
                obj = new Staff();
            }
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setEmail(dto.getEmail());
            obj.setMobile(dto.getMobile());
            obj.setPhone(dto.getPhone());
            obj.setAddress(dto.getAddress());
            obj.setDesignation(dto.getDesignation());
            obj.setGender(dto.getGender());
            obj.setQualification(dto.getQualification());
            obj.setMartialStatus(dto.getMartialStatus());
            obj.setStatus(dto.getStatus());
            if (!appUtil.isEmptyOrNull(dto.getStaffDOBStr())) {
                obj.setStaffDOB(appUtil.getLocalDate(dto.getStaffDOBStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getTimeInStr())) {
                obj.setTimeIn(LocalTime.parse(dto.getTimeInStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getTimeOutStr())) {
                obj.setTimeOut(LocalTime.parse(dto.getTimeOutStr()));
            }
            // Scoped too: an unchecked findById here let a caller attach ANOTHER tenant's class to their
            // own staff member, leaking that class back through every staff read.
            if (dto.getGradeIds() != null) {
                List<Grade> grades = new ArrayList<>();
                for (Long gid : dto.getGradeIds()) {
                    if (!appUtil.isEmptyOrNull(gid)) {
                        gradeRepository.findByIdScoped(gid, orgId, userId).ifPresent(grades::add);
                    }
                }
                obj.setGrades(grades);
            }
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Staff saved = staffRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteStaff", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteStaff(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: only rows in the caller's own tenant are deleted (see ScopedDeleter).
                scopedDeleter.deleteScoped(staffRepository, ids,
                        Staff::getOrganizationId, Staff::getUserId, null);
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
