package com.myplus.education.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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

    @RequestMapping(value = "/getUserSubject", method = RequestMethod.GET)
    @ResponseBody
    @Transactional(readOnly = true)
    public GenericResponse getUserSubject(final HttpServletRequest request) {
        try {
            List<Subject> objs = subjectRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "", new java.util.ArrayList<SubjectDTO>());
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserSubjects", method = RequestMethod.GET)
    @ResponseBody
    public String getUserSubjects(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Subject> objs = subjectRepository.findScoped(orgId(), userId());
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
            List<Subject> all = subjectRepository.findScoped(orgId(), userId());
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
            Subject obj = (dto.getId() != null)
                    ? subjectRepository.findById(dto.getId()).orElseGet(Subject::new)
                    : new Subject();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setCode(dto.getCode());
            obj.setPublisher(dto.getPublisher());
            obj.setEdition(dto.getEdition());
            obj.setStatus(dto.getStatus());
            if (dto.getGradeId() != null) {
                Grade g = gradeRepository.findById(dto.getGradeId()).orElse(null);
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

    @RequestMapping(value = "/deleteSubject", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteSubject(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        subjectRepository.deleteById(Long.valueOf(id));
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return false;
        }
    }
}
