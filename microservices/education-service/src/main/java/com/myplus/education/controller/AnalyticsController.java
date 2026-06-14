package com.myplus.education.controller;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.Attendance;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Staff;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.AttendanceRepository;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.GuardianRepository;
import com.myplus.education.repository.SchoolRepository;
import com.myplus.education.repository.StaffRepository;
import com.myplus.education.repository.StudentRepository;
import com.myplus.education.util.AppUtil;
import com.myplus.education.util.GenericResponse;
import com.myplus.education.util.RequestUtil;

/**
 * Rich, org-scoped analytics for the education owner dashboard (slice 22).
 * One round-trip returns KPI headlines plus chart-ready series across four lenses:
 * Finance, Students/Enrollment, Attendance, and Staff/HR. All figures are scoped to the
 * active tenant (organizationId from the gateway) and the owning user, mirroring the
 * findScoped(orgId, userId) pattern used everywhere else in this service.
 *
 * Datasets are per-school and small, so aggregation is done in-memory from the scoped
 * lists rather than with bespoke SQL — keeps the query surface tiny and DB-agnostic.
 */
@Controller
public class AnalyticsController {

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

    @Autowired private StudentRepository studentRepository;
    @Autowired private FeeCollectionRepository feeCollectionRepository;
    @Autowired private AttendanceRepository attendanceRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private GradeRepository gradeRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private RequestUtil requestUtil;
    @Autowired private AppUtil appUtil;

    @RequestMapping(value = "/getDashboardAnalytics", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse getDashboardAnalytics() {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Long userId = user == null ? null : user.getUserId();
            Long orgId = user == null ? null : user.getOrganizationId();

            List<Student> students = studentRepository.findScoped(orgId, userId);
            List<FeeCollection> fees = feeCollectionRepository.findScoped(orgId, userId);
            List<Attendance> attendance = attendanceRepository.findScoped(orgId, userId);
            List<Staff> staff = staffRepository.findScoped(orgId, userId);
            List<Grade> grades = gradeRepository.findScoped(orgId, userId);

            Map<Long, String> gradeNames = new LinkedHashMap<>();
            for (Grade g : grades) gradeNames.put(g.getId(), gradeLabel(g));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kpis", buildKpis(students, fees, attendance, staff, orgId, userId));
            out.put("enrollTrend", enrollTrend(students));
            out.put("feeTrend", feeTrend(fees));
            out.put("attendanceTrend", attendanceTrend(attendance));
            out.put("studentsByClass", studentsByClass(students, gradeNames));
            out.put("collectionByClass", collectionByClass(fees, students, gradeNames));
            out.put("attendanceByClass", attendanceByClass(attendance));
            out.put("genderSplit", countBy(students, s -> norm(s.getGender(), "Unspecified")));
            out.put("studentStatus", countBy(students, s -> norm(s.getStatus(), "Active")));
            out.put("paymentModes", sumBy(fees, f -> norm(f.getRi(), "Cash"), f -> nz(f.getFp())));
            out.put("staffByDesignation", countBy(staff, s -> norm(s.getDesignation(), "Other")));

            return new GenericResponse("SUCCESS", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- KPI headlines ----------------------------------------------------
    private Map<String, Object> buildKpis(List<Student> students, List<FeeCollection> fees,
            List<Attendance> attendance, List<Staff> staff, Long orgId, Long userId) {
        int currentYear = LocalDate.now().getYear();
        YearMonth thisMonth = YearMonth.now();

        long totalStudents = students.size();
        long freshStudents = students.stream()
                .filter(s -> s.getEnrollDate() != null && s.getEnrollDate().getYear() == currentYear).count();
        long activeStudents = students.stream()
                .filter(s -> s.getStatus() == null || s.getStatus().equalsIgnoreCase("Active")).count();

        long collectedThisMonth = fees.stream()
                .filter(f -> f.getPd() != null && YearMonth.from(f.getPd()).equals(thisMonth))
                .mapToLong(f -> nz(f.getFp())).sum();
        long collectedTotal = fees.stream().mapToLong(f -> nz(f.getFp())).sum();
        long outstanding = fees.stream().mapToLong(f -> nz(f.getDb())).sum();
        long billed = collectedTotal + outstanding;
        double collectionRate = billed > 0 ? (collectedTotal * 100.0 / billed) : 0;

        long attTotal = attendance.size();
        long attPresent = attendance.stream().filter(a -> isPresent(a.getStatus())).count();
        double attendanceRate = attTotal > 0 ? (attPresent * 100.0 / attTotal) : 0;

        long totalStaff = staff.size();
        double ratio = totalStaff > 0 ? (totalStudents * 1.0 / totalStaff) : 0;

        Map<String, Object> k = new LinkedHashMap<>();
        k.put("totalStudents", totalStudents);
        k.put("freshStudents", freshStudents);
        k.put("activeStudents", activeStudents);
        k.put("totalStaff", totalStaff);
        k.put("totalSchools", safeCount(() -> schoolRepository.countByUserId(userId)));
        k.put("totalGuardians", safeCount(() -> guardianRepository.countByUserId(userId)));
        k.put("collectedThisMonth", collectedThisMonth);
        k.put("collectedTotal", collectedTotal);
        k.put("outstanding", outstanding);
        k.put("collectionRate", round1(collectionRate));
        k.put("attendanceRate", round1(attendanceRate));
        k.put("studentTeacherRatio", round1(ratio));
        return k;
    }

    // ---- Time series ------------------------------------------------------
    private Map<String, Object> enrollTrend(List<Student> students) {
        List<YearMonth> months = last12Months();
        Map<YearMonth, Long> byMonth = new TreeMap<>();
        months.forEach(m -> byMonth.put(m, 0L));
        for (Student s : students) {
            if (s.getEnrollDate() == null) continue;
            YearMonth m = YearMonth.from(s.getEnrollDate());
            if (byMonth.containsKey(m)) byMonth.merge(m, 1L, Long::sum);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", months.stream().map(this::monthLabel).toList());
        r.put("data", months.stream().map(byMonth::get).toList());
        return r;
    }

    private Map<String, Object> feeTrend(List<FeeCollection> fees) {
        List<YearMonth> months = last12Months();
        Map<YearMonth, long[]> byMonth = new TreeMap<>();
        months.forEach(m -> byMonth.put(m, new long[2])); // [0]=collected, [1]=due
        for (FeeCollection f : fees) {
            if (f.getPd() == null) continue;
            YearMonth m = YearMonth.from(f.getPd());
            long[] cell = byMonth.get(m);
            if (cell == null) continue;
            cell[0] += nz(f.getFp());
            cell[1] += nz(f.getDa()) + nz(f.getOd());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", months.stream().map(this::monthLabel).toList());
        r.put("collected", months.stream().map(m -> byMonth.get(m)[0]).toList());
        r.put("due", months.stream().map(m -> byMonth.get(m)[1]).toList());
        return r;
    }

    /** Daily attendance rate over the last 30 days that actually have records. */
    private Map<String, Object> attendanceTrend(List<Attendance> attendance) {
        Map<LocalDate, long[]> byDay = new TreeMap<>(); // [0]=present, [1]=total
        for (Attendance a : attendance) {
            if (a.getAttDate() == null) continue;
            long[] cell = byDay.computeIfAbsent(a.getAttDate(), d -> new long[2]);
            if (isPresent(a.getStatus())) cell[0]++;
            cell[1]++;
        }
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        if (days.size() > 30) days = days.subList(days.size() - 30, days.size());
        List<String> labels = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (LocalDate d : days) {
            long[] cell = byDay.get(d);
            labels.add(d.format(DAY_LABEL));
            data.add(cell[1] > 0 ? round1(cell[0] * 100.0 / cell[1]) : 0);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", labels);
        r.put("data", data);
        return r;
    }

    // ---- Breakdowns -------------------------------------------------------
    private Map<String, Object> studentsByClass(List<Student> students, Map<Long, String> gradeNames) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Student s : students) {
            String label = gradeNames.getOrDefault(s.getGradeId(), "Unassigned");
            m.merge(label, 1L, Long::sum);
        }
        return labelsAndData(m);
    }

    private Map<String, Object> collectionByClass(List<FeeCollection> fees, List<Student> students,
            Map<Long, String> gradeNames) {
        Map<String, Long> enrollToClass = new LinkedHashMap<>();
        for (Student s : students) {
            if (s.getEnrollNo() != null) enrollToClass.put(s.getEnrollNo(), s.getGradeId());
        }
        Map<String, Long> m = new LinkedHashMap<>();
        for (FeeCollection f : fees) {
            Long gid = f.getEn() == null ? null : enrollToClass.get(f.getEn());
            String label = gradeNames.getOrDefault(gid, "Unassigned");
            m.merge(label, (long) nz(f.getFp()), Long::sum);
        }
        return labelsAndData(m);
    }

    private Map<String, Object> attendanceByClass(List<Attendance> attendance) {
        Map<String, long[]> m = new LinkedHashMap<>(); // name -> [present,total]
        for (Attendance a : attendance) {
            String label = norm(a.getGn(), "Unassigned");
            long[] cell = m.computeIfAbsent(label, k -> new long[2]);
            if (isPresent(a.getStatus())) cell[0]++;
            cell[1]++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>(m.keySet());
        r.put("labels", labels);
        r.put("data", labels.stream().map(l -> { long[] c = m.get(l); return c[1] > 0 ? round1(c[0] * 100.0 / c[1]) : 0; }).toList());
        return r;
    }

    // ---- Generic helpers --------------------------------------------------
    private <T> Map<String, Object> countBy(List<T> list, java.util.function.Function<T, String> key) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (T t : list) m.merge(key.apply(t), 1L, Long::sum);
        return labelsAndData(m);
    }

    private <T> Map<String, Object> sumBy(List<T> list, java.util.function.Function<T, String> key,
            java.util.function.ToLongFunction<T> val) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (T t : list) m.merge(key.apply(t), val.applyAsLong(t), Long::sum);
        return labelsAndData(m);
    }

    private Map<String, Object> labelsAndData(Map<String, Long> m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", new ArrayList<>(m.keySet()));
        r.put("data", new ArrayList<>(m.values()));
        return r;
    }

    private List<YearMonth> last12Months() {
        List<YearMonth> months = new ArrayList<>();
        YearMonth cur = YearMonth.now();
        for (int i = 11; i >= 0; i--) months.add(cur.minusMonths(i));
        return months;
    }

    private String monthLabel(YearMonth m) { return m.atDay(1).format(MONTH_LABEL); }
    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }
    private boolean isPresent(String s) { return s != null && (s.equalsIgnoreCase("present") || s.equalsIgnoreCase("p")); }
    private String norm(String s, String dflt) { return s == null || s.isBlank() ? dflt : s.trim(); }
    private int nz(Integer i) { return i == null ? 0 : i; }
    private double round1(double d) { return Math.round(d * 10.0) / 10.0; }
    private long safeCount(java.util.function.LongSupplier s) { try { return s.getAsLong(); } catch (Exception e) { return 0; } }
}
