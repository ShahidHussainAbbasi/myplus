package com.myplus.education.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.education.entity.NoticeAudience;
import com.myplus.education.entity.NoticeStatus;
import com.myplus.education.entity.PortalSubjectType;

import static com.myplus.education.entity.NoticeAudience.*;
import static com.myplus.education.entity.NoticeStatus.DRAFT;
import static com.myplus.education.entity.NoticeStatus.PUBLISHED;
import static com.myplus.education.entity.PortalSubjectType.GUARDIAN;
import static com.myplus.education.entity.PortalSubjectType.STUDENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 3.5 — who a notice reaches, tested with no Spring, no DB and no Docker.
 *
 * <p><b>This method is the authorisation.</b> Reading a notice is not privilege-gated (D5), so a bug here
 * is a disclosure: a GUARDIANS-only notice about fee arrears appearing on a child's screen, or a class
 * notice silently promoted to the whole school. Every case below is therefore written as "who must NOT
 * see this".
 */
class NoticeAudienceResolverTest {

    private static final Long CLASS_5 = 5L;
    private static final Long CLASS_6 = 6L;

    @Test
    @DisplayName("a DRAFT reaches nobody — whatever its audience says")
    void draft_reaches_nobody() {
        // Checked before any audience branch, so no combination can leak an unpublished notice.
        assertFalse(NoticeAudienceResolver.reaches(WHOLE_SCHOOL, null, DRAFT, GUARDIAN, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(WHOLE_SCHOOL, null, DRAFT, STUDENT, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, DRAFT, STUDENT, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(GUARDIANS, null, null, GUARDIAN, CLASS_5),
                "a null status is not a published one");
    }

    @Test
    @DisplayName("WHOLE_SCHOOL reaches both audiences")
    void whole_school_reaches_everyone() {
        assertTrue(NoticeAudienceResolver.reaches(WHOLE_SCHOOL, null, PUBLISHED, GUARDIAN, CLASS_5));
        assertTrue(NoticeAudienceResolver.reaches(WHOLE_SCHOOL, null, PUBLISHED, STUDENT, CLASS_6));
        assertTrue(NoticeAudienceResolver.reaches(WHOLE_SCHOOL, null, PUBLISHED, STUDENT, null),
                "and a caller with no class still gets general notices");
    }

    @Test
    @DisplayName("GUARDIANS excludes students, and STUDENTS excludes guardians — the disclosure cases")
    void audience_separates_the_two_populations() {
        // The reason this matters: a GUARDIANS notice is where a school puts fee deadlines and parents'
        // evening. A STUDENTS notice is where it puts exam instructions. Neither is meant for the other.
        assertTrue(NoticeAudienceResolver.reaches(GUARDIANS, null, PUBLISHED, GUARDIAN, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(GUARDIANS, null, PUBLISHED, STUDENT, CLASS_5));

        assertTrue(NoticeAudienceResolver.reaches(STUDENTS, null, PUBLISHED, STUDENT, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(STUDENTS, null, PUBLISHED, GUARDIAN, CLASS_5));
    }

    @Test
    @DisplayName("ONE_CLASS reaches only that class, both audiences within it")
    void one_class_is_scoped_to_the_class() {
        assertTrue(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, PUBLISHED, STUDENT, CLASS_5));
        assertTrue(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, PUBLISHED, GUARDIAN, CLASS_5),
                "a guardian of a child in that class gets it too");
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, PUBLISHED, STUDENT, CLASS_6));
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, PUBLISHED, GUARDIAN, CLASS_6));
    }

    @Test
    @DisplayName("THE FAIL-OPEN CASE: a ONE_CLASS notice with NO class reaches nobody, never everybody")
    void one_class_with_no_grade_reaches_nobody() {
        // If a missing grade were read as "no filter", a class notice would silently become a whole-school
        // one — the single most likely way this method could leak, and the reason the controller also
        // refuses to SAVE that combination. Both halves, because either alone can be bypassed.
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, null, PUBLISHED, STUDENT, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, null, PUBLISHED, GUARDIAN, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, null, PUBLISHED, STUDENT, null),
                "and two nulls are not a match either");
    }

    @Test
    @DisplayName("a caller with no class never matches a class notice")
    void caller_without_a_class_matches_no_class_notice() {
        // Legitimate and common: a guardian whose child is not yet placed, a student mid-transfer.
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, PUBLISHED, GUARDIAN, null));
        assertFalse(NoticeAudienceResolver.reaches(ONE_CLASS, CLASS_5, PUBLISHED, STUDENT, null));
    }

    @Test
    @DisplayName("a null audience or a null caller type reaches nobody — missing input is never a wildcard")
    void missing_input_is_never_permissive() {
        assertFalse(NoticeAudienceResolver.reaches((NoticeAudience) null, CLASS_5, PUBLISHED, STUDENT, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(WHOLE_SCHOOL, null, PUBLISHED, (PortalSubjectType) null, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(null, null, (NoticeStatus) null, null, null));
    }

    @Test
    @DisplayName("the whole-notice overload agrees with the primitive one, and a null notice reaches nobody")
    void overload_agrees_and_null_is_safe() {
        com.myplus.education.entity.Notice n = new com.myplus.education.entity.Notice();
        n.setAudience(ONE_CLASS);
        n.setGradeId(CLASS_5);
        n.setStatus(PUBLISHED);

        assertTrue(NoticeAudienceResolver.reaches(n, STUDENT, CLASS_5));
        assertFalse(NoticeAudienceResolver.reaches(n, STUDENT, CLASS_6));
        assertFalse(NoticeAudienceResolver.reaches((com.myplus.education.entity.Notice) null, STUDENT, CLASS_5));
    }
}
