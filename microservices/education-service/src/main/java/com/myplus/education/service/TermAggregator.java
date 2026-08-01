package com.myplus.education.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slice 1.5 — the term aggregate and class ranking, as PURE functions.
 *
 * Everything here takes its inputs as arguments and touches no repository, so the maths that decides what
 * goes on a child's report card is testable without a database, a Spring context or Docker. The DB work
 * lives in {@link ReportCardService}.
 *
 * The formula (design D3):
 * <pre>
 *   paperPercent = GradingService.percentFor(mark, paper)     ← 1.4, absent policy applied there
 *   examPercent  = mean(paperPercent) over that exam's papers ← papers weigh equally (see below)
 *   termPercent  = Σ (weight × examPercent) / Σ (weight)      ← over CONTRIBUTING exams only
 * </pre>
 */
public final class TermAggregator {

    private TermAggregator() { }

    /**
     * One subject row as computed for a card. Not an entity — {@link com.myplus.education.entity.ReportCardLine}
     * is what this becomes if and when the card is published.
     *
     * @param percent null means "does not count toward an average": not marked yet, or absent while
     *                {@code edu.grading.absentCountsAsZero} is off (1.4 D3). It must never be read as 0.
     */
    public record LineView(Long examId, String examName, String subjectName, Integer maxMarks,
                           Integer marksObtained, boolean absent, Double percent,
                           String gradeName, Double gpaPoints, int sequence) { }

    /**
     * The weighted term percentage, or null when nothing has been marked yet.
     *
     * <p>Two rules that are easy to get wrong and are the reason this is a named function rather than a
     * loop inside a controller:
     *
     * <ol>
     *   <li><b>A null paperPercent leaves BOTH sides of the mean.</b> Counting it as 0 in the denominator
     *       would quietly undo 1.4 D3 — the setting exists precisely so an excluded paper disappears.</li>
     *   <li><b>An exam with nothing marked leaves the divisor too.</b> If the final exam (weight 60) has no
     *       marks yet, dividing by 100 would report a pass as a fail. A card produced mid-term should say
     *       "72% of what has been examined", not "29% of the year". D2 is what stops that provisional
     *       figure being PUBLISHED.</li>
     * </ol>
     *
     * <p>A null or missing weight counts as 0, so such an exam contributes nothing. That is not silent
     * data loss: the weights-total-100 check (D2) refuses to publish a term configured that way, and
     * preview names the shortfall.
     */
    public static Double weightedTermPercent(List<LineView> lines, Map<Long, Integer> weightByExam) {
        if (lines == null || lines.isEmpty()) return null;

        // exam -> [sum of counted percentages, how many counted]
        Map<Long, double[]> byExam = new LinkedHashMap<>();
        for (LineView l : lines) {
            if (l.percent() == null) continue;          // rule 1: leaves both sides
            double[] cell = byExam.computeIfAbsent(l.examId(), k -> new double[2]);
            cell[0] += l.percent();
            cell[1] += 1;
        }

        double weighted = 0, divisor = 0;
        for (Map.Entry<Long, double[]> e : byExam.entrySet()) {
            double[] cell = e.getValue();
            if (cell[1] == 0) continue;                 // rule 2: nothing marked, so no weight either
            Integer w = weightByExam == null ? null : weightByExam.get(e.getKey());
            int weight = w == null ? 0 : w;
            if (weight <= 0) continue;
            weighted += weight * (cell[0] / cell[1]);
            divisor += weight;
        }
        if (divisor <= 0) return null;
        return Math.round((weighted / divisor) * 10.0) / 10.0;
    }

    /** The mean GPA across the lines that carry one, or null when the school runs letters without GPA. */
    public static Double meanGpa(List<LineView> lines) {
        if (lines == null) return null;
        double sum = 0;
        int n = 0;
        for (LineView l : lines) {
            if (l.gpaPoints() == null) continue;
            sum += l.gpaPoints();
            n++;
        }
        if (n == 0) return null;
        return Math.round((sum / n) * 100.0) / 100.0;
    }

    /**
     * Class positions from term percentages, highest first, <b>ties sharing a rank</b> (D4).
     *
     * <p>Two students on 88% are both 1st and the next is 3rd. Breaking the tie on enrolment number, name
     * or entry order would invent a distinction the marks do not support — and it is a child's parent who
     * reads the result.
     *
     * <p>A student with no term percentage (nothing marked) is <b>unranked</b>, not last: ranking them
     * bottom would state a failure the data does not show. Their entry is absent from the returned map.
     *
     * @param percentByStudent enrolment number → term percentage (null percentages permitted)
     * @return enrolment number → rank, containing only students who have a percentage
     */
    public static Map<String, Integer> rank(Map<String, Double> percentByStudent) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (percentByStudent == null || percentByStudent.isEmpty()) return out;

        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> e : percentByStudent.entrySet()) {
            if (e.getValue() != null) ranked.add(e);
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int position = 0;
        Double previous = null;
        int rank = 0;
        for (Map.Entry<String, Double> e : ranked) {
            position++;
            // Equal scores share the earlier rank; the next distinct score jumps to its ordinal position.
            if (previous == null || Double.compare(e.getValue(), previous) != 0) {
                rank = position;
                previous = e.getValue();
            }
            out.put(e.getKey(), rank);
        }
        return out;
    }
}
