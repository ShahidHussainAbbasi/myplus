package com.myplus.education.service;

import com.myplus.education.entity.GradeBand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Slice 1.4 (D5) — validates a grading scale as a WHOLE.
 *
 * Per-band checks are not enough: a band is only correct relative to its neighbours. An overlap makes a
 * letter ambiguous (85% is both A and B) and a gap makes a percentage ungradeable (nothing covers 33–39).
 * So the unit of validation is the SET, which is also why this cannot live in a Bean Validation annotation.
 *
 * Pure and static, so the whole matrix is testable without a database — matching {@code FeeValidator},
 * {@code MarksValidator} and {@code ExamLockGuard}.
 */
public final class BandValidator {

    private BandValidator() { }

    /**
     * @return every problem with the scale, in reading order. Empty means valid.
     *
     * An EMPTY scale is valid: a school that has not configured grading keeps working, and marks still
     * show a percentage with no letter (D2). Refusing "no bands" would force every tenant to set grading
     * up before they could use marks at all.
     */
    public static List<String> validateSet(List<GradeBand> bands) {
        List<String> problems = new ArrayList<>();
        if (bands == null || bands.isEmpty()) return problems;

        // Per-band sanity first — an inverted or out-of-range band makes the ordering checks meaningless.
        for (GradeBand b : bands) {
            String label = b.getName() == null || b.getName().isBlank() ? "(unnamed band)" : b.getName();
            if (b.getName() == null || b.getName().isBlank()) {
                problems.add("Every band needs a name");
            }
            Integer min = b.getMinPercent(), max = b.getMaxPercent();
            if (min == null || max == null) {
                problems.add(label + " needs both a minimum and a maximum percentage");
                continue;
            }
            if (min < 0 || min > 100 || max < 0 || max > 100) {
                problems.add(label + " must sit between 0 and 100 (got " + min + "–" + max + ")");
            }
            if (min > max) {
                problems.add(label + " starts above where it ends (" + min + "–" + max + ")");
            }
            if (b.getGpaPoints() != null && b.getGpaPoints() < 0) {
                problems.add(label + " has negative GPA points");
            }
        }
        if (!problems.isEmpty()) return problems;   // ordering checks need sane bands to mean anything

        List<GradeBand> sorted = new ArrayList<>(bands);
        sorted.sort(Comparator.comparing(GradeBand::getMinPercent));

        // The scale must cover 0–100 exactly once: contiguous, inclusive ranges.
        if (sorted.get(0).getMinPercent() != 0) {
            problems.add("The lowest band must start at 0% (starts at " + sorted.get(0).getMinPercent() + "%)");
        }
        GradeBand top = sorted.get(sorted.size() - 1);
        if (top.getMaxPercent() != 100) {
            problems.add("The highest band must end at 100% (ends at " + top.getMaxPercent() + "%)");
        }
        for (int i = 1; i < sorted.size(); i++) {
            GradeBand prev = sorted.get(i - 1), cur = sorted.get(i);
            int expected = prev.getMaxPercent() + 1;
            if (cur.getMinPercent() <= prev.getMaxPercent()) {
                problems.add("Bands " + prev.getName() + " (" + prev.getMinPercent() + "–" + prev.getMaxPercent()
                        + ") and " + cur.getName() + " (" + cur.getMinPercent() + "–" + cur.getMaxPercent()
                        + ") overlap — a mark in the overlap would have two grades");
            } else if (cur.getMinPercent() > expected) {
                problems.add("Nothing covers " + expected + "–" + (cur.getMinPercent() - 1)
                        + "% between " + prev.getName() + " and " + cur.getName());
            }
        }
        return problems;
    }
}
