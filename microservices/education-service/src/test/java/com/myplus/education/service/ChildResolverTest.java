package com.myplus.education.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 3.1 — the intersection rule that decides whether a guardian may see a child.
 *
 * Pure: no Spring, no database, no Docker, so it runs on every {@code mvn test}. This is the
 * highest-consequence check in the programme — getting it wrong means a stranger reads a child's record —
 * which is exactly why it is a static function with its inputs passed in.
 */
class ChildResolverTest {

    private static Set<String> mine(String... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }

    @Test
    @DisplayName("my own child is mine")
    void own_child_matches() {
        assertTrue(ChildResolver.isMine("S-1042", mine("S-1042", "S-1088")));
        assertTrue(ChildResolver.isMine("S-1088", mine("S-1042", "S-1088")));
    }

    @Test
    @DisplayName("another guardian's child is NOT mine — the case this slice exists to get right")
    void other_childs_id_is_refused() {
        assertFalse(ChildResolver.isMine("S-9999", mine("S-1042", "S-1088")));
    }

    @Test
    @DisplayName("a guardian with no children matches nothing")
    void empty_set_matches_nothing() {
        assertFalse(ChildResolver.isMine("S-1042", Set.of()));
        assertFalse(ChildResolver.isMine("S-1042", null));
    }

    @Test
    @DisplayName("null and blank enrolment numbers never match")
    void null_and_blank_never_match() {
        assertFalse(ChildResolver.isMine(null, mine("S-1042")));
        assertFalse(ChildResolver.isMine("", mine("S-1042")));
        assertFalse(ChildResolver.isMine("   ", mine("S-1042")));
        // A blank must not match a blank entry either, if one ever got into the set.
        assertFalse(ChildResolver.isMine("   ", mine("")));
    }

    @Test
    @DisplayName("the caller's surrounding whitespace is tolerated, because a URL may carry it")
    void surrounding_whitespace_is_trimmed() {
        assertTrue(ChildResolver.isMine("  S-1042  ", mine("S-1042")));
    }

    @Test
    @DisplayName("matching is EXACT — no case-folding")
    void matching_is_case_sensitive() {
        // Enrolment numbers are opaque identifiers. Case-folding here would let "s-1042" reach a child
        // recorded as "S-1042", and every loosening of this comparison is a way for a crafted value to
        // match something it should not.
        assertFalse(ChildResolver.isMine("s-1042", mine("S-1042")));
    }

    @Test
    @DisplayName("matching is EXACT — no prefix or substring matching")
    void matching_is_not_a_prefix() {
        // "S-104" must not reach "S-1042", and "S-10420" must not reach it either.
        assertFalse(ChildResolver.isMine("S-104", mine("S-1042")));
        assertFalse(ChildResolver.isMine("S-10420", mine("S-1042")));
    }

    @Test
    @DisplayName("a sibling set works — one guardian, several children")
    void siblings() {
        Set<String> siblings = mine("S-1", "S-2", "S-3");
        assertTrue(ChildResolver.isMine("S-2", siblings));
        assertFalse(ChildResolver.isMine("S-4", siblings));
    }
}
