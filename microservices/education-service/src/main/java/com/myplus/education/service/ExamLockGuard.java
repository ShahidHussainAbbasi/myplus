package com.myplus.education.service;

import com.myplus.education.entity.ExamStatus;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Slice 1.2 (D5) — the ONE place the exam-lock rule lives. No controller re-checks status inline, the
 * same way no controller re-derives the current term (1.1).
 *
 * The problem it exists for: changing {@code maxMarks} from 50 to 100 after marks are entered silently
 * halves every student's percentage. There is no error and no trace — just report cards that disagree
 * with the marksheets printed last week. 1.3 audits marks EDITS; for the DEFINITION a lock is cheaper
 * and stronger, because an audit trail tells you afterwards who broke it while a lock stops them.
 *
 * Only the fields that RESTATE existing marks are frozen. Name, date and times stay editable in every
 * state, because rescheduling a paper harms nothing and a lock that blocks it would just get unlocked
 * routinely — which is how locks stop meaning anything.
 */
public final class ExamLockGuard {

    private ExamLockGuard() { }

    /**
     * Changing any of these re-interprets marks that already exist:
     * maxMarks/passMarks change what a score MEANS, subjectId moves the marks to another subject, and
     * termId moves the whole result into a different reporting period.
     */
    public static final Set<String> RESTATING_FIELDS =
            new LinkedHashSet<>(Arrays.asList("maxMarks", "passMarks", "subjectId", "termId"));

    /**
     * @return a human refusal naming the fix, or {@code null} when the edit is allowed.
     *
     * Returns a message rather than throwing because every caller answers with a {@code GenericResponse};
     * an exception would just be caught and flattened into a worse message one line later.
     */
    public static String refusalFor(ExamStatus status, Collection<String> changedFields) {
        if (status != ExamStatus.LOCKED) return null;
        if (changedFields == null || changedFields.isEmpty()) return null;

        Set<String> blocked = new LinkedHashSet<>();
        for (String f : changedFields) {
            if (f != null && RESTATING_FIELDS.contains(f)) blocked.add(f);
        }
        if (blocked.isEmpty()) return null;   // rescheduling a locked exam is fine

        return "Marks have already been entered for this exam, so " + String.join(", ", blocked)
                + " cannot be changed — it would restate every student's result. "
                + "Unlock the exam first if this is really intended.";
    }

    /** True when the exam is frozen against mark-restating edits. Convenience for read-side flags. */
    public static boolean isLocked(ExamStatus status) {
        return status == ExamStatus.LOCKED;
    }
}
