package com.myplus.education.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 3.3 — the student-side authority rule, tested with no Spring, no DB and no Docker.
 *
 * <p>Its guardian twin ({@code ChildResolver.isMine}) got the same treatment for the same reason: this is
 * the check with the highest consequence in the slice, so it is a pure function that can be read and tested
 * in isolation rather than a branch buried in a controller.
 *
 * <p><b>A caveat worth stating, because 3.1b earned it the hard way:</b> these cases prove the RULE, not the
 * deployment. Twelve green tests over {@code PortalScopeFilter} sat alongside a filter that was failing open,
 * because they fed it a header format the gateway does not send. What proves a student session is actually
 * confined is {@code student-portal.cy.js} case 1, against a real login.
 */
class StudentResolverTest {

    private static final Set<String> MINE = Set.of("EN-1001");

    @Test
    @DisplayName("my own enrolment number is mine")
    void my_own_number_matches() {
        assertTrue(StudentResolver.isMe("EN-1001", MINE));
    }

    @Test
    @DisplayName("another student's number is NOT mine — the whole point of the class")
    void another_students_number_is_refused() {
        assertFalse(StudentResolver.isMe("EN-1002", MINE));
        assertFalse(StudentResolver.isMe("en-1001", MINE), "matching is exact, never case-folded");
        assertFalse(StudentResolver.isMe("EN-1001 ", MINE), "and never trimmed into a match");
    }

    @Test
    @DisplayName("blank, null and an empty set are refusals — never 'everything'")
    void missing_input_is_never_permissive() {
        // The failure that matters is a MISSING value being read as a wildcard. Each of these is the
        // shape a bug takes when a parameter is absent, and every one of them must deny.
        assertFalse(StudentResolver.isMe(null, MINE));
        assertFalse(StudentResolver.isMe("", MINE));
        assertFalse(StudentResolver.isMe("   ", MINE));
        assertFalse(StudentResolver.isMe("EN-1001", null));
        assertFalse(StudentResolver.isMe("EN-1001", List.of()));
    }

    @Test
    @DisplayName("a substring of my number is not my number")
    void substrings_do_not_match() {
        // Enrolment numbers are free text and schools do reuse prefixes across years — EN-100 must not
        // open EN-1001. Exact membership, not startsWith or contains.
        assertFalse(StudentResolver.isMe("EN-100", MINE));
        assertFalse(StudentResolver.isMe("EN-10011", MINE));
    }

    @Test
    @DisplayName("the two feature switches are separate constants — students-off must not read portal-off")
    void the_two_switches_are_distinct_keys() {
        // Cheap, and it catches a real copy-paste: if these ever became the same string, turning the
        // student portal on would turn the guardian portal on with it, silently.
        assertEquals("edu.portal.enabled", StudentResolver.PORTAL_ENABLED);
        assertEquals("edu.portal.students.enabled", StudentResolver.STUDENTS_ENABLED);
        assertNotEquals(StudentResolver.PORTAL_ENABLED, StudentResolver.STUDENTS_ENABLED);
    }
}
