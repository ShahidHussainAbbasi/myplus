package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.GradeDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;
import com.myplus.education.util.ScopedDeleter;

/** Flat (legacy) Grade endpoints. userId-scoped; resolves school branch name by schoolId. */
@Controller
public class GradeController {

    @Autowired
    private GradeRepository gradeRepository;
    @Autowired
    private SchoolRepository schoolRepository;
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

    /** P4 role×branch visibility — see StudentController.visibleStudents() for the rule. */
    private List<Grade> visibleGrades() {
        if (requestUtil.isOwnerSuper()) return gradeRepository.findScoped(orgId(), userId());
        java.util.Set<Long> schools = requestUtil.accessibleSchoolIds();
        if (schools.isEmpty()) return gradeRepository.findScoped(orgId(), userId());
        return gradeRepository.findScopedBySchools(orgId(), schools);
    }

    private GradeDTO toDto(Grade g) {
        GradeDTO dto = new GradeDTO();
        dto.setId(g.getId());
        dto.setUserId(g.getUserId());
        dto.setName(g.getName());
        dto.setCode(g.getCode());
        dto.setSection(g.getSection());
        dto.setSchoolId(g.getSchoolId());
        dto.setStatus(g.getStatus());
        dto.setFee(g.getFee());
        dto.setRoom(g.getRoom());
        dto.setTimeFromStr(g.getTimeFrom() == null ? "" : g.getTimeFrom().toString());
        dto.setTimeToStr(g.getTimeTo() == null ? "" : g.getTimeTo().toString());
        dto.setDatedStr(appUtil.getDateStr(g.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(g.getUpdated()));
        if (g.getSchoolId() != null) {
            schoolRepository.findById(g.getSchoolId())
                    .ifPresent(s -> dto.setSchoolName(s.getBranchName()));
        }
        return dto;
    }

    @RequestMapping(value = "/getUserGrade", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserGrade(final HttpServletRequest request) {
        try {
            List<Grade> objs = visibleGrades();
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserGrades", method = RequestMethod.GET)
    @ResponseBody
    public String getUserGrades(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Grade> objs = visibleGrades();
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

    @RequestMapping(value = "/getAllGrade", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllGrade(final HttpServletRequest request) {
        try {
            // Tenant- AND branch-scoped: every grade the caller may see in the active org.
            List<Grade> all = visibleGrades();
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addGrade", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addGrade(final GradeDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId())) {
                boolean exists = gradeRepository.findScoped(orgId, userId).stream().anyMatch(g ->
                        g.getName() != null && g.getName().equalsIgnoreCase(dto.getName())
                                && java.util.Objects.equals(g.getSchoolId(), dto.getSchoolId()));
                if (exists) {
                    return new GenericResponse("FOUND", "The Grade '" + dto.getName() + "' already exists");
                }
            }
            Grade obj = (dto.getId() != null)
                    ? gradeRepository.findById(dto.getId()).orElseGet(Grade::new)
                    : new Grade();
            // P4 anti-IDOR: an edit names a row by id, so it must live in a branch the caller may access.
            if (dto.getId() != null && obj.getId() != null && !requestUtil.canAccessSchool(obj.getSchoolId())) {
                return new GenericResponse("NOT_FOUND", "Grade not found");
            }
            Long school = dto.getSchoolId() != null ? dto.getSchoolId() : requestUtil.activeSchoolId();
            if (!requestUtil.canAccessSchool(school)) {
                return new GenericResponse("FAILED", "You do not have access to that branch.");
            }
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setCode(dto.getCode());
            obj.setSection(dto.getSection());
            obj.setSchoolId(school);
            obj.setStatus(dto.getStatus());
            obj.setFee(dto.getFee());
            obj.setRoom(dto.getRoom());
            if (!appUtil.isEmptyOrNull(dto.getTimeFromStr())) {
                obj.setTimeFrom(LocalTime.parse(dto.getTimeFromStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getTimeToStr())) {
                obj.setTimeTo(LocalTime.parse(dto.getTimeToStr()));
            }
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Grade saved = gradeRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "", dto)
                    : new GenericResponse("SUCCESS", "", dto);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteGrade", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteGrade(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: the caller's own tenant and own branch only (see ScopedDeleter).
                scopedDeleter.deleteScoped(gradeRepository, ids,
                        Grade::getOrganizationId, Grade::getUserId, Grade::getSchoolId);
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
