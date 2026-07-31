package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;

import com.myplus.education.entity.ExamStatus;

import org.junit.jupiter.api.Test;

/**
 * Slice 1.2 (D5) — the exam-lock truth table.
 *
 * This is a matrix of status × changed-field, so it belongs in `mvn test` rather than in Cypress:
 * pure, no database, no Docker, and it enumerates combinations a browser test would never cover.
 *
 * The rule being pinned down: a LOCKED exam refuses only the fields that RESTATE existing marks.
 * Rescheduling stays allowed, because a lock that blocks harmless edits gets unlocked routinely —
 * which is how locks stop meaning anything.
 */
class ExamLockGuardTest {

    @Test
    void draft_and_published_allow_everything() {
        for (ExamStatus s : new ExamStatus[] { ExamStatus.DRAFT, ExamStatus.PUBLISHED }) {
            assertThat(ExamLockGuard.refusalFor(s, Arrays.asList("maxMarks", "passMarks", "subjectId", "termId")))
                    .as("%s must not block anything — marks do not exist yet", s)
                    .isNull();
        }
    }

    @Test
    void locked_refuses_every_field_that_restates_marks() {
        for (String field : ExamLockGuard.RESTATING_FIELDS) {
            String refusal = ExamLockGuard.refusalFor(ExamStatus.LOCKED, Collections.singleton(field));
            assertThat(refusal).as("LOCKED must refuse %s", field).isNotNull();
            assertThat(refusal).as("the refusal names the offending field").contains(field);
            assertThat(refusal).as("and names the fix rather than just saying no").contains("Unlock");
        }
    }

    @Test
    void locked_still_allows_rescheduling_and_renaming() {
        // The deliberate hole in the lock (D5): moving a paper harms nothing.
        assertThat(ExamLockGuard.refusalFor(ExamStatus.LOCKED, Arrays.asList("examDate", "timeFrom", "timeTo", "name")))
                .isNull();
    }

    @Test
    void a_mixed_edit_is_refused_and_reports_only_the_blocked_fields() {
        String refusal = ExamLockGuard.refusalFor(ExamStatus.LOCKED, Arrays.asList("examDate", "maxMarks"));
        assertThat(refusal).isNotNull();
        assertThat(refusal).contains("maxMarks");
        assertThat(refusal).as("the allowed field must not appear in the refusal").doesNotContain("examDate");
    }

    @Test
    void an_edit_that_changes_nothing_is_never_refused() {
        // Saving a locked exam without touching a restating field must succeed — otherwise the screen
        // becomes read-only in practice and users work around it by unlocking.
        assertThat(ExamLockGuard.refusalFor(ExamStatus.LOCKED, Collections.emptyList())).isNull();
        assertThat(ExamLockGuard.refusalFor(ExamStatus.LOCKED, null)).isNull();
    }

    @Test
    void null_status_is_treated_as_unlocked_rather_than_throwing() {
        // Rows written before this slice have no status; they must stay editable, not become bricked.
        assertThat(ExamLockGuard.refusalFor(null, Collections.singleton("maxMarks"))).isNull();
    }

    @Test
    void isLocked_matches_the_refusal_behaviour() {
        assertThat(ExamLockGuard.isLocked(ExamStatus.LOCKED)).isTrue();
        assertThat(ExamLockGuard.isLocked(ExamStatus.DRAFT)).isFalse();
        assertThat(ExamLockGuard.isLocked(ExamStatus.PUBLISHED)).isFalse();
        assertThat(ExamLockGuard.isLocked(null)).isFalse();
    }
}
