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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.education.entity.Grade;
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
 * active tenant (organizationId from the gateway) and the owning user.
 *
 * <p><b>Finding D (docs/slices/edu-D-analytics-perf.md) — this class used to compute, and now
 * assembles.</b> The original note here read:
 *
 * <blockquote>"Datasets are per-school and small, so aggregation is done in-memory from the scoped
 * lists rather than with bespoke SQL — keeps the query surface tiny and DB-agnostic."</blockquote>
 *
 * That assumption held for students and staff and was wrong about <b>attendance</b>, which carries one
 * row per student per day: roughly 400,000 rows a year for a 2,000-student school, every one hydrated
 * into an entity and iterated three times to produce an average and two short series. Five whole tables
 * were loaded on every dashboard render.
 *
 * <p>Every figure below is now a scoped aggregate computed by the database, which returns a few dozen
 * rows in total. <b>The response contract is unchanged</b> — same keys, same values — and
 * {@code dashboard.cy.js} passes untouched, which is how we know this was a rewrite and not a redesign.
 *
 * <p>The one thing that legitimately differs: <b>label ORDER</b> within the breakdown series. It was
 * previously "order first encountered while scanning the table", i.e. arbitrary and data-dependent;
 * it is now a stable {@code order by}. Nothing asserts or depends on the old order, and a chart legend
 * that reshuffles when a row is inserted was never a feature.
 */
@Controller
public class AnalyticsController {

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

    /** The attendance trend shows the last N days that actually HAVE records, not the last N calendar days. */
    private static final int TREND_DAYS = 30;

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
    @Transactional(readOnly = true)
    public GenericResponse getDashboardAnalytics() {
        try {
            AuthenticatedUser user = requestUtil.getCurrentUser();
            Long userId = user == null ? null : user.getUserId();
            Long orgId = user == null ? null : user.getOrganizationId();

            // Grades are the ONE table still read whole, deliberately: it holds tens of rows, it is the
            // label source for two charts, and grouping by id in SQL still needs a name to render.
            Map<Long, String> gradeNames = new LinkedHashMap<>();
            for (Grade g : gradeRepository.findScoped(orgId, userId)) gradeNames.put(g.getId(), gradeLabel(g));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kpis", buildKpis(orgId, userId));
            out.put("enrollTrend", enrollTrend(orgId, userId));
            out.put("feeTrend", feeTrend(orgId, userId));
            out.put("attendanceTrend", attendanceTrend(orgId, userId));
            out.put("studentsByClass", studentsByClass(orgId, userId, gradeNames));
            out.put("collectionByClass", collectionByClass(orgId, userId, gradeNames));
            out.put("attendanceByClass", attendanceByClass(orgId, userId));
            out.put("genderSplit", groupedCounts(studentRepository.countByGenderScoped(orgId, userId), "Unspecified"));
            out.put("studentStatus", groupedCounts(studentRepository.countByStatusScoped(orgId, userId), "Active"));
            out.put("paymentModes", groupedCounts(feeCollectionRepository.sumByReceivedInScoped(orgId, userId), "Cash"));
            out.put("staffByDesignation", groupedCounts(staffRepository.countByDesignationScoped(orgId, userId), "Other"));

            return new GenericResponse("SUCCESS", out);
        } catch (Exception e) {
            appUtil.le(getClass(), e);
            return new GenericResponse("ERROR", e.getMessage());
        }
    }

    // ---- KPI headlines ----------------------------------------------------
    private Map<String, Object> buildKpis(Long orgId, Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate yearEnd = yearStart.plusYears(1).minusDays(1);
        YearMonth thisMonth = YearMonth.from(today);

        long totalStudents = studentRepository.countScoped(orgId, userId);
        long freshStudents = studentRepository.countEnrolledBetweenScoped(yearStart, yearEnd, orgId, userId);
        long activeStudents = studentRepository.countActiveScoped(orgId, userId);

        long collectedThisMonth = feeCollectionRepository.sumPaidBetweenScoped(
                thisMonth.atDay(1), thisMonth.atEndOfMonth(), orgId, userId);

        Object[] totals = firstRow(feeCollectionRepository.sumTotalsScoped(orgId, userId));
        long collectedTotal = asLong(totals, 0);
        long outstanding = asLong(totals, 1);
        long billed = collectedTotal + outstanding;
        double collectionRate = billed > 0 ? (collectedTotal * 100.0 / billed) : 0;

        Object[] att = firstRow(attendanceRepository.summariseAllScoped(orgId, userId));
        long attPresent = asLong(att, 0);
        long attTotal = asLong(att, 1);
        double attendanceRate = attTotal > 0 ? (attPresent * 100.0 / attTotal) : 0;

        long totalStaff = staffRepository.countScoped(orgId, userId);
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
    // The window is applied in the QUERY, not sliced in Java after loading everything.

    private Map<String, Object> enrollTrend(Long orgId, Long userId) {
        List<YearMonth> months = last12Months();
        Map<YearMonth, Long> byMonth = new TreeMap<>();
        months.forEach(m -> byMonth.put(m, 0L));

        for (Object[] row : studentRepository.countByEnrolMonthScoped(
                months.get(0).atDay(1), months.get(months.size() - 1).atEndOfMonth(), orgId, userId)) {
            YearMonth m = yearMonthOf(row);
            if (m != null && byMonth.containsKey(m)) byMonth.put(m, asLong(row, 2));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", months.stream().map(this::monthLabel).toList());
        r.put("data", months.stream().map(byMonth::get).toList());
        return r;
    }

    private Map<String, Object> feeTrend(Long orgId, Long userId) {
        List<YearMonth> months = last12Months();
        Map<YearMonth, long[]> byMonth = new TreeMap<>();
        months.forEach(m -> byMonth.put(m, new long[2])); // [0]=collected, [1]=due

        for (Object[] row : feeCollectionRepository.sumByMonthScoped(
                months.get(0).atDay(1), months.get(months.size() - 1).atEndOfMonth(), orgId, userId)) {
            YearMonth m = yearMonthOf(row);
            if (m == null) continue;
            long[] cell = byMonth.get(m);
            if (cell == null) continue;
            cell[0] = asLong(row, 2);
            cell[1] = asLong(row, 3);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", months.stream().map(this::monthLabel).toList());
        r.put("collected", months.stream().map(m -> byMonth.get(m)[0]).toList());
        r.put("due", months.stream().map(m -> byMonth.get(m)[1]).toList());
        return r;
    }

    /**
     * Daily attendance rate over the last 30 days that actually have records.
     *
     * The query returns days newest-first; the newest 30 are taken and then reversed, so the chart
     * still reads oldest → newest exactly as before. Bounding by "the last 30 CALENDAR days" instead
     * would have been simpler SQL and a different chart — a school that records no weekends would
     * suddenly show gaps it never used to.
     */
    private Map<String, Object> attendanceTrend(Long orgId, Long userId) {
        List<Object[]> newestFirst = attendanceRepository.summariseByDayScoped(orgId, userId);
        List<Object[]> window = newestFirst.size() > TREND_DAYS
                ? new ArrayList<>(newestFirst.subList(0, TREND_DAYS))
                : new ArrayList<>(newestFirst);
        java.util.Collections.reverse(window);

        List<String> labels = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (Object[] row : window) {
            LocalDate day = asLocalDate(row[0]);
            if (day == null) continue;
            long present = asLong(row, 1);
            long total = asLong(row, 2);
            labels.add(day.format(DAY_LABEL));
            data.add(total > 0 ? round1(present * 100.0 / total) : 0);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("labels", labels);
        r.put("data", data);
        return r;
    }

    // ---- Breakdowns -------------------------------------------------------

    private Map<String, Object> studentsByClass(Long orgId, Long userId, Map<Long, String> gradeNames) {
        return byGrade(studentRepository.countByGradeScoped(orgId, userId), gradeNames);
    }

    private Map<String, Object> collectionByClass(Long orgId, Long userId, Map<Long, String> gradeNames) {
        // The enrolNo → gradeId join now happens in SQL (see sumPaidByGradeScoped) rather than by
        // building a map of every student and walking every fee row against it.
        return byGrade(feeCollectionRepository.sumPaidByGradeScoped(orgId, userId), gradeNames);
    }

    /** Rows of {@code [gradeId, value]} → labels/data, with an unresolved grade shown as "Unassigned". */
    private Map<String, Object> byGrade(List<Object[]> rows, Map<Long, String> gradeNames) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long gradeId = row[0] == null ? null : ((Number) row[0]).longValue();
            String label = gradeNames.getOrDefault(gradeId, "Unassigned");
            m.merge(label, asLong(row, 1), Long::sum);
        }
        return labelsAndData(m);
    }

    private Map<String, Object> attendanceByClass(Long orgId, Long userId) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (Object[] row : attendanceRepository.summariseByGradeNameScoped(orgId, userId)) {
            labels.add(norm(row[0] == null ? null : row[0].toString(), "Unassigned"));
            long present = asLong(row, 1);
            long total = asLong(row, 2);
            data.add(total > 0 ? round1(present * 100.0 / total) : 0);
        }
        r.put("labels", labels);
        r.put("data", data);
        return r;
    }

    /** Rows of {@code [label, value]} → labels/data, blank/null labels folded onto a default. */
    private Map<String, Object> groupedCounts(List<Object[]> rows, String defaultLabel) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String label = norm(row[0] == null ? null : row[0].toString(), defaultLabel);
            // merge, not put: null and "" and "  " all fold onto the same default label, exactly as
            // the old norm()-keyed in-memory grouping did.
            m.merge(label, asLong(row, 1), Long::sum);
        }
        return labelsAndData(m);
    }

    // ---- Generic helpers --------------------------------------------------

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

    private static Object[] firstRow(List<Object[]> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * A numeric column as a long, treating null as 0.
     *
     * SQL {@code sum()} over zero rows returns NULL where the Java stream it replaced returned 0, so a
     * brand-new tenant with no fees or no attendance would otherwise surface nulls in the payload. The
     * queries coalesce as well; this is the second belt, and it costs nothing.
     */
    private static long asLong(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) return 0L;
        return ((Number) row[i]).longValue();
    }

    /** Rows of {@code [year, month, …]} → a YearMonth. */
    private static YearMonth yearMonthOf(Object[] row) {
        if (row == null || row[0] == null || row[1] == null) return null;
        return YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
    }

    /** JPA may hand back java.sql.Date or LocalDate depending on the driver; accept either. */
    private static LocalDate asLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return null;
    }

    private String monthLabel(YearMonth m) { return m.atDay(1).format(MONTH_LABEL); }

    private String gradeLabel(Grade g) {
        String n = g.getName() == null ? "Class" : g.getName();
        return g.getSection() == null || g.getSection().isBlank() ? n : n + " " + g.getSection();
    }

    // isPresent() is GONE: "was this child at school" is now defined once, in AttendanceRepository's
    // aggregate SQL, shared with the report-card summary added in slice 1.5.

    private String norm(String s, String dflt) { return s == null || s.isBlank() ? dflt : s.trim(); }
    private double round1(double d) { return Math.round(d * 10.0) / 10.0; }
    private long safeCount(java.util.function.LongSupplier s) { try { return s.getAsLong(); } catch (Exception e) { return 0; } }
}
