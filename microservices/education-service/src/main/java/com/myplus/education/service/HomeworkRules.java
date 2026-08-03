package com.myplus.education.service;

import com.myplus.education.entity.HomeworkSubmission;
import com.myplus.education.entity.SubmissionState;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Slice 2.4 — the homework rules that are pure arithmetic and judgement, with no database in them.
 *
 * <p>Same shape as {@code ClashDetector} (2.1), {@code FreeTeacherFinder} (2.2) and
 * {@code LeaveBalanceCalculator} (2.3): every input is an argument, so these test with no Spring, no DB and
 * no Docker.
 */
public final class HomeworkRules {

    private HomeworkRules() { }

    /**
     * D5 — late is DERIVED, never stored.
     *
     * <p>A stored {@code late} flag freezes a judgement that changes the moment a teacher extends a
     * deadline. Deriving it means extending {@code dueOn} correctly un-lates everyone who beat the new
     * date, which is the whole reason not to store it.
     *
     * <p>Submitting ON the due date is not late — a deadline is the last acceptable day, not the first
     * unacceptable one.
     */
    public static boolean isLate(LocalDate submittedOn, LocalDate dueOn) {
        if (submittedOn == null || dueOn == null) return false;
        return submittedOn.isAfter(dueOn);
    }

    /**
     * Past its due date with nothing recorded — what the screen highlights.
     *
     * <p>Deliberately NOT the same as {@link SubmissionState#NOT_DONE}: this is an observation about the
     * calendar, that one is a teacher's judgement. The system may point out that a deadline has passed; it
     * must not conclude on its own that a child failed to do the work (D3).
     */
    public static boolean isOverdueUnrecorded(SubmissionState state, LocalDate dueOn, LocalDate today) {
        if (state != null) return false;                       // something IS recorded
        if (dueOn == null || today == null) return false;      // no deadline to be past
        return today.isAfter(dueOn);
    }

    /** A mark must be present, non-negative, and within the ceiling. Returns null when it is fine. */
    public static String validateMarks(Integer marksObtained, Integer maxMarks) {
        if (marksObtained == null) return null;                // ungraded is legitimate, not an error
        if (marksObtained < 0) return "Marks cannot be negative";
        if (maxMarks != null && marksObtained > maxMarks) {
            return "Marks (" + marksObtained + ") exceed the maximum of " + maxMarks;
        }
        return null;
    }

    /**
     * Homework can be deleted only while nothing has been graded.
     *
     * <p>A submission is a record of a child's work and a teacher's judgement of it. Losing that to a
     * mis-click on the task it hangs from is not recoverable, so the delete is refused rather than
     * cascaded. An ungraded SUBMITTED or NOT_DONE row is a note, not a judgement, and does not block.
     */
    public static boolean canDelete(Collection<HomeworkSubmission> submissions) {
        for (HomeworkSubmission s : submissions == null ? List.<HomeworkSubmission>of() : submissions) {
            if (s != null && s.getState() == SubmissionState.MARKED) return false;
        }
        return true;
    }

    /**
     * How many of a class have something recorded — the completion count on the list screen.
     *
     * <p>Counts rows, which is exactly right given D2: a student with no row has nothing recorded, and
     * that is what the number is meant to convey.
     */
    public static int recordedCount(Collection<HomeworkSubmission> submissions) {
        return submissions == null ? 0 : (int) submissions.stream().filter(s -> s != null).count();
    }
}
