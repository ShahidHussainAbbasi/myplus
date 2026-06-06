package com.myplus.education.controller;

import java.time.LocalDateTime;
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
import com.myplus.education.dto.StudentDTO;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Flat (legacy) Student endpoints. userId-scoped; resolves school/grade/guardian display names.
 * NOTE: CSV/Excel import (importCSV/impStudents) and getUserStudentMap are advanced endpoints
 * deferred to a focused follow-up (file-upload + POI parsing).
 */
@Controller
public class StudentController {

    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private SchoolRepository schoolRepository;
    @Autowired
    private GradeRepository gradeRepository;
    @Autowired
    private GuardianRepository guardianRepository;
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

    private StudentDTO toDto(Student s) {
        StudentDTO dto = new StudentDTO();
        dto.setId(s.getId());
        dto.setUserId(s.getUserId());
        dto.setName(s.getName());
        dto.setEnrollNo(s.getEnrollNo());
        dto.setFeeMode(s.getFeeMode());
        dto.setEmail(s.getEmail());
        dto.setMobile(s.getMobile());
        dto.setAddress(s.getAddress());
        dto.setGender(s.getGender());
        dto.setBloodGroup(s.getBloodGroup());
        dto.setStatus(s.getStatus());
        dto.setSchoolId(s.getSchoolId());
        dto.setGuardianId(s.getGuardianId());
        dto.setGradeId(s.getGradeId());
        dto.setVehicleId(s.getVehicleId());
        dto.setDiscountId(s.getDiscountId());
        dto.setNd(s.getNd());
        dto.setEnrollDateStr(appUtil.getLocalDateStr(s.getEnrollDate()));
        dto.setYsStr(appUtil.getLocalDateStr(s.getYs()));
        dto.setYeStr(appUtil.getLocalDateStr(s.getYe()));
        dto.setDateOfBirthStr(appUtil.getLocalDateStr(s.getDateOfBirth()));
        dto.setDatedStr(appUtil.getDateStr(s.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(s.getUpdated()));
        if (s.getSchoolId() != null) {
            schoolRepository.findById(s.getSchoolId()).ifPresent(x -> dto.setSchoolName(x.getBranchName()));
        }
        if (s.getGradeId() != null) {
            gradeRepository.findById(s.getGradeId()).ifPresent(x -> dto.setGradeName(x.getName()));
        }
        if (s.getGuardianId() != null) {
            guardianRepository.findById(s.getGuardianId()).ifPresent(x -> dto.setGuardianName(x.getName()));
        }
        return dto;
    }

    @RequestMapping(value = "/getUserStudent", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserStudent(final HttpServletRequest request) {
        try {
            List<Student> objs = studentRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", objs.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/getUserStudents", method = RequestMethod.GET)
    @ResponseBody
    public String getUserStudents(final HttpServletRequest request) {
        StringBuffer sb = new StringBuffer();
        try {
            List<Student> objs = studentRepository.findScoped(orgId(), userId());
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

    @RequestMapping(value = "/getAllStudent", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getAllStudent(final HttpServletRequest request) {
        try {
            // Tenant-scoped: "all" means all students in the active organization, not every tenant's.
            List<Student> all = studentRepository.findScoped(orgId(), userId());
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", all.stream().map(this::toDto).collect(Collectors.toList()));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/addStudent", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse addStudent(final StudentDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId()) && !appUtil.isEmptyOrNull(dto.getEnrollNo())) {
                boolean exists = studentRepository.findScoped(orgId, userId).stream()
                        .anyMatch(s -> s.getEnrollNo() != null && s.getEnrollNo().equalsIgnoreCase(dto.getEnrollNo()));
                if (exists) {
                    return new GenericResponse("FOUND", "A student with enroll no '" + dto.getEnrollNo() + "' already exists");
                }
            }
            Student obj = (dto.getId() != null)
                    ? studentRepository.findById(dto.getId()).orElseGet(Student::new)
                    : new Student();
            obj.setUserId(userId);              // audit: who created/edited
            obj.setOrganizationId(orgId);       // tenant scope
            obj.setName(dto.getName());
            obj.setEnrollNo(dto.getEnrollNo());
            obj.setFeeMode(dto.getFeeMode());
            obj.setEmail(dto.getEmail());
            obj.setMobile(dto.getMobile());
            obj.setAddress(dto.getAddress());
            obj.setGender(dto.getGender());
            obj.setBloodGroup(dto.getBloodGroup());
            obj.setStatus(dto.getStatus());
            obj.setSchoolId(dto.getSchoolId());
            obj.setGuardianId(dto.getGuardianId());
            obj.setGradeId(dto.getGradeId());
            obj.setVehicleId(dto.getVehicleId());
            obj.setDiscountId(dto.getDiscountId());
            obj.setNd(dto.getNd());
            if (!appUtil.isEmptyOrNull(dto.getEnrollDateStr())) {
                obj.setEnrollDate(appUtil.getLocalDate(dto.getEnrollDateStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getYsStr())) {
                obj.setYs(appUtil.getLocalDate(dto.getYsStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getYeStr())) {
                obj.setYe(appUtil.getLocalDate(dto.getYeStr()));
            }
            if (!appUtil.isEmptyOrNull(dto.getDateOfBirthStr())) {
                obj.setDateOfBirth(appUtil.getLocalDate(dto.getDateOfBirthStr()));
            }
            if (obj.getDated() == null) {
                obj.setDated(LocalDateTime.now());
            }
            obj.setUpdated(LocalDateTime.now());
            Student saved = studentRepository.save(obj);
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    @RequestMapping(value = "/deleteStudent", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteStudent(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                for (String id : ids.split(",")) {
                    if (!StringUtils.isEmpty(id)) {
                        studentRepository.deleteById(Long.valueOf(id));
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
