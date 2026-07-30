package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.SubjectDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Subject;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.SubjectRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/** Flat (legacy) Subject endpoints. userId-scoped; carries the linked grade name. */
@Controller
public class SubjectController {

    @Autowired
    private SubjectRepository subjectRepository;
    @Autowired
    private GradeRepository gradeRepository;
    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private ScopedDeleter scopedDeleter;   // anti-IDOR bulk delete
    @Autowired
    private AppUtil appUtil;
    @Autowired
    private com.myplus.common.settings.SettingsService settingsService;   // edu.subject.branchScoped policy

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private SubjectDTO toDto(Subject s) {
        SubjectDTO dto = new SubjectDTO();
        dto.setId(s.getId());
        dto.setUserId(s.getUserId());
        dto.setName(s.getName());
        dto.setCode(s.getCode());
        dto.setPublisher(s.getPublisher());
        dto.setEdition(s.getEdition());
        dto.setStatus(s.getStatus());
        dto.setDatedStr(appUtil.getDateStr(s.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(s.getUpdated()));
        if (s.getGrade() != null) {
            dto.setGradeId(s.getGrade().getId());
            dto.setGradeName(s.getGrade().getName());
        }
        return dto;
    }

    /**
     * Subject branch visibility — OFF by default (org-wide: one shared curriculum), opt-in per org via
     * {@code edu.subject.branchScoped}. When on, a subject is visible only if the class it is attached to
     * sits at one of the caller's accessible branches (derived via Subject.grade → Grade.schoolId).
     *
     * Escape hatches as elsewhere: setting off, owner/super, or no branch grants ⇒ org-wide. A subject
     * attached to NO class stays visible under every setting (design D4).
     *
     * Callers must hold an open transaction — {@code Subject.grade} is LAZY and this service runs with
     * {@code open-in-view: false}. Every caller below is already {@code @Transactional(readOnly = true)}.
     */
    private List<Subject> branchVisible(List<Subject> rows) {
        if (!settingsService.getBool("edu.subject.branchScoped")) return rows;   // org-wide (default)
        if (requestUtil.isOwnerSuper()) return rows;
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return rows;
        java.util.Set<Long> branchGradeIds = gradeRepository.findScopedBySchools(orgId(), schools).stream()
                .map(Grade::getId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return rows.stream().filter(s -> {
            Grade g = s.getGrade();
            if (g == null || g.getId() == null) return true;   // D4: unattributed stays visible
            return branchGradeIds.contains(g.getId());
        }).collect(Collectors.toList());
    }

    @RequestMapping(value = "/getUserSubject", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getUserSubject(final HttpServletRequest request) {
        try {
            List<Subject> objs = branchVisible(subjectRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "", new java.util.ArrayList<SubjectDTO>());
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // readOnly tx added with the branch filter: Subject.grade is LAZY and this service runs with
    // open-in-view:false, so branchVisible() must navigate it inside an open session.
    @RequestMapping(value = "/getUserSubjects", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public String getUserSubjects(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            // Scoped too: a picker offering subjects the list screen hides would be a way around the policy.
            List<Subject> objs = branchVisible(subjectRepository.findScoped(orgId(), userId()));
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

    @RequestMapping(value = "/getAllSubject", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getAllSubject(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all subjects in the active organization, not every tenant's.
            List<Subject> all = branchVisible(subjectRepository.findScoped(orgId(), userId()));
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "", new java.util.ArrayList<SubjectDTO>());
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addSubject", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse addSubject(final SubjectDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = subjectRepository.findScoped(orgId, userId).stream()
                        .anyMatch(s -> s.getName() != null && s.getName().equalsIgnoreCase(dto.getName()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Subject '" + dto.getName() + "' already exists");
                }
            }
            // Anti-IDOR: an edit names a row by a client-supplied id, so it must be resolved WITHIN the
            // caller's tenant. A bare findById followed by the setOrganizationId below would have moved
            // another org's row into this one — taking it from its owner, not merely editing it.
            Subject obj;
            if (dto.getId() != null) {
                obj = subjectRepository.findByIdScoped(dto.getId(), orgId, userId).orElse(null);
                if (obj == null) return new GenericResponse("NOT_FOUND", "Subject not found");
            } else {
                obj = new Subject();
            }
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setCode(dto.getCode());
            obj.setPublisher(dto.getPublisher());
            obj.setEdition(dto.getEdition());
            obj.setStatus(dto.getStatus());
            // Scoped: an unchecked findById let a caller attach ANOTHER tenant's class to their subject.
            if (dto.getGradeId() != null) {
                Grade g = gradeRepository.findByIdScoped(dto.getGradeId(), orgId, userId).orElse(null);
                obj.setGrade(g);
            }
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Subject saved = subjectRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteSubject", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteSubject(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: only rows in the caller's own tenant are deleted (see ScopedDeleter).
                scopedDeleter.deleteScoped(subjectRepository, ids,
                        Subject::getOrganizationId, Subject::getUserId, null);
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
