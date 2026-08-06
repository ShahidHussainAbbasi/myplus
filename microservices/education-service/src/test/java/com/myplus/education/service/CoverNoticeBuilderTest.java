package com.myplus.education.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice N1 — the cover notice, tested with no Spring, no database and no Docker, so it runs on every
 * {@code mvn test}.
 *
 * <p>What is asserted here is what a teacher actually receives. The Cypress gate can only prove a notice was
 * QUEUED; only these cases prove it says the right thing.
 */
class CoverNoticeBuilderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

    // ── sendable: the decision that separates "queued" from "nobody was told" ────────────────────────

    @Test
    @DisplayName("a null or blank address is not sendable — the common case, a staff record with no email")
    void blank_is_not_sendable() {
        assertFalse(CoverNoticeBuilder.sendable(null));
        assertFalse(CoverNoticeBuilder.sendable(""));
        assertFalse(CoverNoticeBuilder.sendable("   "));
    }

    @Test
    @DisplayName("an address with no @ is not sendable — EmailService would silently skip it, so it must "
            + "be reported as NO_EMAIL now rather than queued, retried and dead-lettered")
    void junk_without_at_is_not_sendable() {
        assertFalse(CoverNoticeBuilder.sendable("not-an-address"));
        assertFalse(CoverNoticeBuilder.sendable("07700 900123"));
    }

    @Test
    @DisplayName("a real address is sendable")
    void real_address_is_sendable() {
        assertTrue(CoverNoticeBuilder.sendable("teacher@school.test"));
    }

    // ── the message itself ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the body names class, subject, period and date — the four facts needed to walk to the room")
    void body_names_the_lesson() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                "A Khan", "khan@school.test", "Class 5 B", "Mathematics", "Period 3", DAY, "R12");

        assertTrue(n.body().contains("Class 5 B"), n.body());
        assertTrue(n.body().contains("Mathematics"), n.body());
        assertTrue(n.body().contains("Period 3"), n.body());
        assertTrue(n.body().contains("2026-08-06"), n.body());
        assertTrue(n.body().contains("R12"), n.body());
        assertTrue(n.body().contains("A Khan"), n.body());
    }

    @Test
    @DisplayName("the subject line carries the class and date, because that is all a phone preview shows")
    void subject_is_scannable() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                "A Khan", "khan@school.test", "Class 5 B", "Mathematics", "Period 3", DAY, null);

        assertTrue(n.subject().contains("Class 5 B"), n.subject());
        assertTrue(n.subject().contains("2026-08-06"), n.subject());
    }

    @Test
    @DisplayName("a missing room omits the whole line — 2.1 D3: Grade.room is a bare Long with no room "
            + "master, so it is frequently absent and must never print as 'null'")
    void missing_room_omits_the_line() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                "A Khan", "khan@school.test", "Class 5 B", "Mathematics", "Period 3", DAY, null);

        assertFalse(n.body().contains("Room"), n.body());
        assertFalse(n.body().contains("null"), n.body());
    }

    @Test
    @DisplayName("a blank room is treated as absent, not printed as an empty label")
    void blank_room_omits_the_line() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                "A Khan", "khan@school.test", "Class 5 B", "Mathematics", "Period 3", DAY, "  ");
        assertFalse(n.body().contains("Room"), n.body());
    }

    @Test
    @DisplayName("a school still setting up its timetable can assign cover: every field but the date may "
            + "be missing, and nothing renders as 'null'")
    void half_configured_school_still_gets_a_usable_notice() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                null, "khan@school.test", null, null, null, DAY, null);

        assertFalse(n.body().contains("null"), n.body());
        assertFalse(n.subject().contains("null"), n.subject());
        assertTrue(n.body().contains("2026-08-06"), n.body());
        assertTrue(n.body().contains("cover"), n.body());
    }

    @Test
    @DisplayName("the address is carried through to the notice unchanged — it is what the outbox stores")
    void notice_carries_the_recipient() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                "A Khan", "khan@school.test", "Class 5 B", "Mathematics", "Period 3", DAY, "R12");
        assertEquals("khan@school.test", n.recipientEmail());
    }

    @Test
    @DisplayName("no marks and no behaviour data leak into the body — outbox rows outlive the event")
    void body_is_operational_only() {
        CoverNoticeBuilder.Notice n = CoverNoticeBuilder.build(
                "A Khan", "khan@school.test", "Class 5 B", "Mathematics", "Period 3", DAY, "R12");
        String b = n.body().toLowerCase();
        assertFalse(b.contains("mark"), b);
        assertFalse(b.contains("grade "), b);
        assertFalse(b.contains("behaviour"), b);
    }
}
