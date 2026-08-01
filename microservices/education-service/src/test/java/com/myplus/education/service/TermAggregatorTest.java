package com.myplus.education.service;

import com.myplus.education.service.TermAggregator.LineView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 1.5 — the term aggregate and ranking.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. This is the maths that
 * decides what goes on a child's report card, so it is tested by hand-computed expectations rather than
 * by asserting whatever the code happens to produce.
 */
class TermAggregatorTest {

    private static LineView line(long examId, String subject, Double percent) {
        return new LineView(examId, "Exam " + examId, subject, 100, null, false, percent, null, null, 0);
    }

    private static LineView line(long examId, String subject, Double percent, Double gpa) {
        return new LineView(examId, "Exam " + examId, subject, 100, null, false, percent, "B", gpa, 0);
    }

    private static Map<Long, Integer> weights(Object... pairs) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(((Number) pairs[i]).longValue(), (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("weights 30/70 apply to each exam's mean, not to individual papers")
    void weighted_across_two_exams() {
        // Mid-term (30%): 60 and 80 → mean 70.  Final (70%): 90 and 100 → mean 95.
        // 0.30 × 70 + 0.70 × 95 = 21 + 66.5 = 87.5
        List<LineView> lines = List.of(
                line(1, "Maths", 60.0), line(1, "English", 80.0),
                line(2, "Maths", 90.0), line(2, "English", 100.0));
        assertEquals(87.5, TermAggregator.weightedTermPercent(lines, weights(1, 30, 2, 70)));
    }

    @Test
    @DisplayName("a null percentage leaves BOTH sides of the mean — it is never a zero")
    void null_percent_excluded_from_both_sides() {
        // Only Maths counts, so the exam mean is 60 — not 30, which is what treating null as 0 would give.
        List<LineView> lines = List.of(line(1, "Maths", 60.0), line(1, "English", null));
        assertEquals(60.0, TermAggregator.weightedTermPercent(lines, weights(1, 100)));
    }

    @Test
    @DisplayName("an unmarked exam leaves the DIVISOR too, so a mid-term card is not a failure")
    void unmarked_exam_leaves_the_divisor() {
        // Mid-term 30% marked at 80; final 70% not marked at all.
        // Correct: 80 (80% of what has been examined). Wrong: 0.30 × 80 = 24.
        List<LineView> lines = List.of(line(1, "Maths", 80.0), line(2, "Maths", null));
        assertEquals(80.0, TermAggregator.weightedTermPercent(lines, weights(1, 30, 2, 70)));
    }

    @Test
    @DisplayName("nothing marked at all yields null, not zero")
    void nothing_marked_is_null() {
        assertNull(TermAggregator.weightedTermPercent(List.of(line(1, "Maths", null)), weights(1, 100)));
        assertNull(TermAggregator.weightedTermPercent(List.of(), weights(1, 100)));
        assertNull(TermAggregator.weightedTermPercent(null, weights(1, 100)));
    }

    @Test
    @DisplayName("an exam with no weight contributes nothing rather than skewing the divisor")
    void missing_weight_contributes_nothing() {
        List<LineView> lines = List.of(line(1, "Maths", 80.0), line(2, "Maths", 40.0));
        // Exam 2 has no weight, so only exam 1 counts. The weights-total-100 check (D2) is what stops
        // this being PUBLISHED; the aggregate itself must not silently average in an unweighted exam.
        assertEquals(80.0, TermAggregator.weightedTermPercent(lines, weights(1, 100)));
    }

    @Test
    @DisplayName("absent counting as zero pulls the mean down — the policy arrives as a 0.0 percentage")
    void absent_as_zero_is_just_a_zero_percentage() {
        // GradingService decides 0.0 vs null (1.4 D3); the aggregate only has to honour whichever arrives.
        List<LineView> counted = List.of(line(1, "Maths", 80.0), line(1, "English", 0.0));
        assertEquals(40.0, TermAggregator.weightedTermPercent(counted, weights(1, 100)));

        List<LineView> excluded = List.of(line(1, "Maths", 80.0), line(1, "English", null));
        assertEquals(80.0, TermAggregator.weightedTermPercent(excluded, weights(1, 100)));
    }

    @Test
    @DisplayName("mean GPA ignores lines without one; no GPA anywhere yields null")
    void mean_gpa() {
        assertEquals(3.5, TermAggregator.meanGpa(List.of(
                line(1, "Maths", 90.0, 4.0), line(1, "English", 70.0, 3.0))));
        assertNull(TermAggregator.meanGpa(List.of(line(1, "Maths", 90.0))));
    }

    @Test
    @DisplayName("ties share a rank and the next distinct score skips — 1, 1, 3")
    void ties_share_a_rank() {
        Map<String, Double> percents = new LinkedHashMap<>();
        percents.put("A1", 88.0);
        percents.put("A2", 88.0);
        percents.put("A3", 70.0);
        Map<String, Integer> ranks = TermAggregator.rank(percents);
        assertEquals(1, ranks.get("A1"));
        assertEquals(1, ranks.get("A2"));
        assertEquals(3, ranks.get("A3"), "the next distinct score takes its ordinal position, not 2");
    }

    @Test
    @DisplayName("a student with no percentage is UNRANKED, not last")
    void unmarked_student_is_unranked() {
        Map<String, Double> percents = new LinkedHashMap<>();
        percents.put("A1", 55.0);
        percents.put("A2", null);
        Map<String, Integer> ranks = TermAggregator.rank(percents);
        assertEquals(1, ranks.get("A1"));
        assertFalse(ranks.containsKey("A2"), "ranking them bottom would state a failure the data does not show");
    }

    @Test
    @DisplayName("ranking is highest-first and handles a single student and an empty class")
    void rank_edges() {
        Map<String, Double> one = new LinkedHashMap<>();
        one.put("A1", 41.0);
        assertEquals(1, TermAggregator.rank(one).get("A1"));
        assertTrue(TermAggregator.rank(new LinkedHashMap<>()).isEmpty());
        assertTrue(TermAggregator.rank(null).isEmpty());
    }

    @Test
    @DisplayName("the term percentage is rounded to one decimal place")
    void rounding() {
        // 55 and 56 → 55.5 exactly; 55, 56, 58 → 56.333… → 56.3
        assertEquals(55.5, TermAggregator.weightedTermPercent(
                List.of(line(1, "A", 55.0), line(1, "B", 56.0)), weights(1, 100)));
        assertEquals(56.3, TermAggregator.weightedTermPercent(
                List.of(line(1, "A", 55.0), line(1, "B", 56.0), line(1, "C", 58.0)), weights(1, 100)));
    }
}
