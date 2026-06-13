package com.myplus.education.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.dto.StudentDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Guardian;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.service.FeeService;
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
    private FeeService feeService;
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
            // On new registration, auto-register the opening due if the org's fee policy says so.
            if (appUtil.isEmptyOrNull(dto.getId()) && !appUtil.isEmptyOrNull(saved)) {
                try {
                    if (Boolean.TRUE.equals(feeService.settingFor(orgId, userId).getAutoRegisterDues())) {
                        feeService.registerOpeningDue(orgId, userId, saved);
                    }
                } catch (Exception ex) {
                    appUtil.le(getClass(), ex); // dues registration is best-effort; don't fail the student save
                }
            }
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

    // ---- Slice 15: CSV bulk import ----
    // Header: enrollNo,name,gradeName,gender,guardianName,mobile,status
    @RequestMapping(value = "/impStudents", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse impStudents(@RequestParam("file") MultipartFile file) {
        List<String> errors = new ArrayList<>();
        int created = 0, skipped = 0;
        try {
            if (file == null || file.isEmpty()) {
                return new GenericResponse("INVALID", "No file uploaded");
            }
            Long org = orgId(), uid = userId();
            Set<String> existing = studentRepository.findScoped(org, uid).stream()
                    .map(Student::getEnrollNo).filter(Objects::nonNull)
                    .map(String::toLowerCase).collect(Collectors.toCollection(java.util.HashSet::new));
            Map<String, Long> gradeByName = gradeRepository.findScoped(org, uid).stream()
                    .filter(g -> g.getName() != null)
                    .collect(Collectors.toMap(g -> g.getName().toLowerCase(), Grade::getId, (a, b) -> a));
            Map<String, Long> guardianByName = guardianRepository.findScoped(org, uid).stream()
                    .filter(g -> g.getName() != null)
                    .collect(Collectors.toMap(g -> g.getName().toLowerCase(), Guardian::getId, (a, b) -> a));
            boolean autoDues = Boolean.TRUE.equals(feeService.settingFor(org, uid).getAutoRegisterDues());

            BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int n = 0;
            String[] header = null;
            while ((line = br.readLine()) != null) {
                n++;
                if (line.trim().isEmpty()) continue;
                String[] cols = splitCsv(line);
                if (header == null) { header = lower(cols); continue; }
                Map<String, String> row = rowMap(header, cols);
                String enrollNo = row.get("enrollno");
                String name = row.get("name");
                if (isBlank(enrollNo) || isBlank(name)) {
                    errors.add("row " + n + ": enrollNo and name are required"); skipped++; continue;
                }
                if (existing.contains(enrollNo.toLowerCase())) {
                    errors.add("row " + n + ": enrollNo '" + enrollNo + "' already exists"); skipped++; continue;
                }
                Student s = new Student();
                s.setOrganizationId(org);   // tenant scope
                s.setUserId(uid);           // audit
                s.setEnrollNo(enrollNo.trim());
                s.setName(name.trim());
                s.setGender(row.get("gender"));
                s.setMobile(row.get("mobile"));
                s.setStatus(isBlank(row.get("status")) ? "ACTIVE" : row.get("status"));
                String gradeName = row.get("gradename");
                if (!isBlank(gradeName)) s.setGradeId(gradeByName.get(gradeName.toLowerCase()));
                String guardianName = row.get("guardianname");
                if (!isBlank(guardianName)) s.setGuardianId(guardianByName.get(guardianName.toLowerCase()));
                s.setEnrollDate(LocalDate.now());
                Student saved = studentRepository.save(s);
                existing.add(enrollNo.toLowerCase());
                if (autoDues) {
                    try { feeService.registerOpeningDue(org, uid, saved); } catch (Exception ignore) { }
                }
                created++;
            }
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("created", created);
        summary.put("skipped", skipped);
        summary.put("errors", errors);
        return new GenericResponse("SUCCESS", "Imported " + created + " student(s)", summary);
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private String[] splitCsv(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim().replaceAll("^\"|\"$", "").trim();
        }
        return parts;
    }

    private String[] lower(String[] cols) {
        String[] out = new String[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = cols[i] == null ? "" : cols[i].toLowerCase();
        return out;
    }

    private Map<String, String> rowMap(String[] header, String[] cols) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            m.put(header[i], i < cols.length ? cols[i] : "");
        }
        return m;
    }
}
