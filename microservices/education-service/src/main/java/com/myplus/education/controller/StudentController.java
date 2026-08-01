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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.myplus.education.util.ScopedDeleter;

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
    private com.myplus.education.service.PartyBridgeService partyBridgeService;   // P3: shared party master bridge
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
    private com.myplus.education.service.StudentVisibilityService studentVisibilityService;

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

    /**
     * P4 role×branch visibility. Empty grants ⇒ no branch filter (single-branch / unassigned / legacy ⇒ exactly
     * today's behaviour). An owner is never narrowed by grants. Otherwise the caller sees their branches' students
     * — the whole roster of those schools, not just the ones they entered (see StudentRepository for why).
     */
    private List<Student> visibleStudents() {
        // Extracted in 1.5: the rule itself lives in StudentVisibilityService, because three controllers
        // held byte-identical copies of a VISIBILITY check and a fourth was about to be written.
        return studentVisibilityService.visibleStudents(orgId(), userId());
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
        dto.setPartyId(s.getPartyId());   // P3: shared party master id
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
        dto.setCreditBalance(s.getCreditBalance());   // slice 0.2b: fee credit held for this student
        dto.setDatedStr(appUtil.getDateStr(s.getDated()));
        dto.setUpdatedStr(appUtil.getDateStr(s.getUpdated()));
        // NOTE: school/grade/guardian NAMES are resolved by toDtos() in one batched query each — NOT here.
        // Doing per-row findById here was an N+1: a 500-student list fired ~1500 extra queries.
        return dto;
    }

    /**
     * Map a whole list, resolving the school/grade/guardian display names with ONE query per lookup table
     * (findAllById over the distinct ids on the page) instead of a findById per row. This is the batch that
     * replaces the N+1 that toDto used to do.
     */
    private List<StudentDTO> toDtos(List<Student> students) {
        // One query per lookup table over the distinct ids on the page (findAllById), then map in memory.
        Set<Long> schoolIds = distinct(students, Student::getSchoolId);
        Set<Long> gradeIds = distinct(students, Student::getGradeId);
        Set<Long> guardianIds = distinct(students, Student::getGuardianId);

        Map<Long, String> schoolNames = new HashMap<>();
        if (!schoolIds.isEmpty())
            schoolRepository.findAllById(schoolIds).forEach(x -> schoolNames.put(x.getId(), x.getBranchName()));
        Map<Long, String> gradeNames = new HashMap<>();
        if (!gradeIds.isEmpty())
            gradeRepository.findAllById(gradeIds).forEach(x -> gradeNames.put(x.getId(), x.getName()));
        Map<Long, String> guardianNames = new HashMap<>();
        if (!guardianIds.isEmpty())
            guardianRepository.findAllById(guardianIds).forEach(x -> guardianNames.put(x.getId(), x.getName()));

        return students.stream().map(s -> {
            StudentDTO dto = toDto(s);
            if (s.getSchoolId() != null) dto.setSchoolName(schoolNames.get(s.getSchoolId()));
            if (s.getGradeId() != null) dto.setGradeName(gradeNames.get(s.getGradeId()));
            if (s.getGuardianId() != null) dto.setGuardianName(guardianNames.get(s.getGuardianId()));
            return dto;
        }).collect(Collectors.toList());
    }

    /** The distinct non-null values of a Long-valued getter over a list (the ids to batch-load). */
    private Set<Long> distinct(List<Student> students, java.util.function.Function<Student, Long> idOf) {
        Set<Long> ids = new java.util.HashSet<>();
        for (Student s : students) { Long id = idOf.apply(s); if (id != null) ids.add(id); }
        return ids;
    }

    @RequestMapping(value = "/getUserStudent", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getUserStudent(final HttpServletRequest request) {
        try {
            List<Student> objs = visibleStudents();
            if (appUtil.isEmptyOrNull(objs)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", toDtos(objs));
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
            List<Student> objs = visibleStudents();
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
            // Tenant- AND branch-scoped: "all" means every student the caller may see in the active org.
            List<Student> all = visibleStudents();
            if (appUtil.isEmptyOrNull(all)) {
                return new GenericResponse("NOT_FOUND", "");
            }
            return new GenericResponse("SUCCESS", "", toDtos(all));
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // D-3 privilege map: day-to-day record; a read-only or guest role must not write
    @PreAuthorize("hasAuthority('WRITE_PRIVILEGE')")
    @RequestMapping(value = "/addStudent", method = RequestMethod.POST)
    @ResponseBody
    @Transactional
    public GenericResponse addStudent(final StudentDTO dto, final HttpServletRequest request) {
        try {
            Long userId = userId();
            Long orgId = orgId();
            if (appUtil.isEmptyOrNull(dto.getId()) && !appUtil.isEmptyOrNull(dto.getEnrollNo())) {
                boolean exists = studentRepository.existsByEnrollNoScoped(dto.getEnrollNo(), orgId, userId);
                if (exists) {
                    return new GenericResponse("FOUND", "A student with enroll no '" + dto.getEnrollNo() + "' already exists");
                }
            }
            Student obj = (dto.getId() != null)
                    ? studentRepository.findById(dto.getId()).orElseGet(Student::new)
                    : new Student();
            // P4 anti-IDOR: an edit takes an id from the client, so the row must sit in a branch the caller
            // may access — otherwise a teacher at Branch B could edit a Branch-A student just by knowing the id.
            if (dto.getId() != null && obj.getId() != null && !requestUtil.canAccessSchool(obj.getSchoolId())) {
                return new GenericResponse("NOT_FOUND", "Student not found");
            }
            // The branch a student belongs to: what the form chose, else the caller's active branch. Either way
            // it must be one the caller holds — a client cannot file a student into someone else's school.
            Long school = dto.getSchoolId() != null ? dto.getSchoolId() : requestUtil.activeSchoolId();
            if (!requestUtil.canAccessSchool(school)) {
                return new GenericResponse("FAILED", "You do not have access to that branch.");
            }
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
            obj.setSchoolId(school);
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
            // Runs in the same transaction as the student save (@Transactional): if the due fails,
            // the student is rolled back too — the two writes are atomic.
            if (appUtil.isEmptyOrNull(dto.getId()) && !appUtil.isEmptyOrNull(saved)
                    && Boolean.TRUE.equals(feeService.settingFor(orgId, userId).getAutoRegisterDues())) {
                feeService.registerOpeningDue(orgId, userId, saved);
            }
            partyBridgeService.bridgeStudent(saved);   // P3: link to the shared party master (best-effort, once)
            return appUtil.isEmptyOrNull(saved)
                    ? new GenericResponse("FAILED", "")
                    : new GenericResponse("SUCCESS", "");
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            // Propagate so @Transactional rolls back (student + due); handleUncaught() rebuilds the envelope.
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @RequestMapping(value = "/deleteStudent", method = RequestMethod.POST)
    @ResponseBody
    public boolean deleteStudent(HttpServletRequest req) {
        try {
            String ids = req.getParameter("checked");
            if (!StringUtils.isEmpty(ids)) {
                // Anti-IDOR: the caller's own tenant and own branch only (see ScopedDeleter).
                scopedDeleter.deleteScoped(studentRepository, ids,
                        Student::getOrganizationId, Student::getUserId, Student::getSchoolId);
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
                    feeService.registerOpeningDue(org, uid, saved);
                }
                created++;
            }
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            // Propagate so the whole @Transactional import rolls back (no partial import);
            // handleUncaught() rebuilds the GenericResponse("ERROR", …) envelope.
            throw new RuntimeException(e.getMessage(), e);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("created", created);
        summary.put("skipped", skipped);
        summary.put("errors", errors);
        return new GenericResponse("SUCCESS", "Imported " + created + " student(s)", summary);
    }

    /**
     * Turns an uncaught exception from a transactional write (addStudent, impStudents) back into the
     * GenericResponse("ERROR", …) envelope. The @Transactional method has already exited via exception,
     * so its transaction is rolled back — the write is all-or-nothing.
     */
    // A @PreAuthorize denial throws AccessDeniedException; this controller's broad Exception handler below
    // would otherwise swallow it into a 200 "ERROR" envelope. A more-specific handler wins → clean 403.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<GenericResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(new GenericResponse("FORBIDDEN", "Access denied"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public GenericResponse handleUncaught(Exception e) {
        appUtil.le(getClass(), e);
        return new GenericResponse("ERROR", e.getMessage());
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
