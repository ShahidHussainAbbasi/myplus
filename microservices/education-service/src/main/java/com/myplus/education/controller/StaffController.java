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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.StaffDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Staff;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

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

    @RequestMapping(value = "/getUserStaff", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserStaff(final HttpServletRequest request) {
        try {
            List<Staff> objs = staffRepository.findScoped(orgId(), userId());
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
            List<Staff> objs = staffRepository.findScoped(orgId(), userId());
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
            // Tenant-scoped: "all" means all staff in the active organization, not every tenant's.
            List<Staff> all = staffRepository.findScoped(orgId(), userId());
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
            Staff obj = (dto.getId() != null)
                    ? staffRepository.findById(dto.getId()).orElseGet(Staff::new)
                    : new Staff();
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
            if (dto.getGradeIds() != null) {
                List<Grade> grades = new ArrayList<>();
                for (Long gid : dto.getGradeIds()) {
                    if (!appUtil.isEmptyOrNull(gid)) {
                        gradeRepository.findById(gid).ifPresent(grades::add);
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

    @RequestMapping(value = "/deleteStaff", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteStaff(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        staffRepository.deleteById(Long.valueOf(id));
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
