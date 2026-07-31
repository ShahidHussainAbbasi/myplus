package com.myplus.education.service;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.ExamPaper;
import com.myplus.education.entity.GradeBand;
import com.myplus.education.entity.Mark;
import com.myplus.education.repository.GradeBandRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Slice 1.4 — turns a raw mark into a percentage and a letter.
 *
 * D4: grading is DERIVED, never stored on the mark. Storing the letter would freeze something the owner
 * can re-band tomorrow, leaving two truths with nothing to say which wins — the same reasoning that made
 * "current term" derived (1.1 D3) and a paper's class derived (1.2 D2).
 *
 * The consequence is deliberate and must travel forward: re-banding retroactively changes historical
 * letters. That is correct while results are live, and it is exactly why 1.5 must SNAPSHOT a published
 * report card rather than re-deriving it years later.
 *
 * This is the ONE place the percentage and band rules live; no controller re-implements the comparison.
 */
@Service
public class GradingService {

    /** 1.3 kept `absent` and `0` distinct so this decision could be made here. Default: absent counts. */
    public static final String ABSENT_AS_ZERO = "edu.grading.absentCountsAsZero";
    public static final String ROUND_HALF_UP = "edu.grading.roundHalfUp";

    @Autowired
    private GradeBandRepository gradeBandRepository;

    @Autowired
    private SettingsService settingsService;

    /** The tenant's scale, lowest band first. Empty is valid — a school need not configure grading. */
    @Transactional(readOnly = true)
    public List<GradeBand> scale(Long orgId, Long userId) {
        return gradeBandRepository.findScoped(orgId, userId);
    }

    /**
     * The band containing a percentage, or null when the school has defined no scale (or the percentage
     * falls outside it — which {@code BandValidator} prevents for new scales but cannot for old data).
     *
     * Takes the scale as a parameter so a caller grading 40 students reads the bands ONCE rather than
     * per row — the same batch-not-per-row discipline as 1.1's term stamping.
     */
    public GradeBand bandFor(List<GradeBand> scale, Double percent) {
        if (scale == null || scale.isEmpty() || percent == null) return null;
        for (GradeBand b : scale) {
            if (b.getMinPercent() == null || b.getMaxPercent() == null) continue;
            if (percent >= b.getMinPercent() && percent <= b.getMaxPercent()) return b;
        }
        return null;
    }

    /**
     * A mark as a percentage of its paper, or null when it does not count towards an average.
     *
     * D3 — the absent policy:
     * <ul>
     *   <li>ON (default): an absence is 0%. A student who sat nothing would otherwise show a flattering
     *       average over an empty set, and a report card that hides a missed paper misleads the parent
     *       it is written for.</li>
     *   <li>OFF: null, meaning the paper leaves BOTH sides of the fraction. Returning 0 here would be the
     *       bug the setting exists to prevent.</li>
     * </ul>
     */
    public Double percentFor(Mark mark, ExamPaper paper) {
        if (mark == null || paper == null) return null;
        Integer max = paper.getMaxMarks();
        if (max == null || max <= 0) return null;      // a paper with no ceiling has no percentage

        if (mark.isAbsent()) {
            return absentCountsAsZero() ? 0.0 : null;
        }
        if (mark.getMarksObtained() == null) return null;   // not marked yet is not a zero
        return round(mark.getMarksObtained() * 100.0 / max);
    }

    public boolean absentCountsAsZero() {
        // Fails to the honest default: a missed paper counts against the average unless a school says not.
        try {
            return settingsService.getBool(ABSENT_AS_ZERO);
        } catch (Exception e) {
            return true;
        }
    }

    private double round(double pct) {
        boolean halfUp;
        try {
            halfUp = settingsService.getBool(ROUND_HALF_UP);
        } catch (Exception e) {
            halfUp = true;
        }
        // One decimal place either way — the toggle only decides the .x5 boundary.
        return halfUp ? Math.round(pct * 10.0) / 10.0 : Math.floor(pct * 10.0) / 10.0;
    }
}
