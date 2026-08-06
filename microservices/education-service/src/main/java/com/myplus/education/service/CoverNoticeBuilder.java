package com.myplus.education.service;

import java.time.LocalDate;

/**
 * Slice N1 — <b>PURE.</b> Builds the cover-assigned notice and decides whether it can be sent.
 *
 * Design: microservices/docs/slices/edu-N1-notification-outbox.md
 *
 * <p>No Spring, no repositories, no clock. Everything it needs arrives as an argument, so the message a
 * teacher actually receives is testable on every {@code mvn test} with no database and no Docker — the same
 * treatment given to {@code ClashDetector} (2.1), {@code FreeTeacherFinder} (2.2),
 * {@code LeaveBalanceCalculator} (2.3) and {@code HomeworkRules} (2.4).
 *
 * <p>The text is deliberately plain: it is read on a phone, in a corridor, minutes before the lesson.
 */
public final class CoverNoticeBuilder {

    private CoverNoticeBuilder() { }

    /** A ready-to-queue notice: the resolved address plus the rendered text. */
    public record Notice(String recipientEmail, String subject, String body) { }

    /**
     * Can this address be sent to at all?
     *
     * <p>Blank and null are the common case — a staff record entered without an email. The {@code @} check
     * mirrors {@code EmailService}, which silently skips anything without one; without the same check here a
     * junk value would be queued, retried and dead-lettered instead of being reported at once as NO_EMAIL.
     */
    public static boolean sendable(String email) {
        return email != null && !email.isBlank() && email.contains("@");
    }

    /**
     * Render the notice.
     *
     * <p>Every field is optional except the date, because a school part-way through setting up its
     * timetable still has to be able to assign cover. A missing name is omitted rather than printed as
     * "null", and the room clause disappears entirely when there is no room — 2.1 D3 records that
     * {@code Grade.room} is a bare Long with no room master behind it, so it is frequently absent.
     */
    public static Notice build(String teacherName, String email, String className,
                               String subjectName, String periodName, LocalDate date, String room) {
        String subject = "Cover assigned" + (className == null || className.isBlank() ? "" : " — " + className)
                + (date == null ? "" : " on " + date);

        StringBuilder b = new StringBuilder();
        b.append(teacherName == null || teacherName.isBlank() ? "Hello," : "Hello " + teacherName + ",");
        b.append("\n\nYou have been assigned to cover a lesson.\n\n");
        if (date != null)                                    b.append("Date:    ").append(date).append('\n');
        if (periodName != null && !periodName.isBlank())     b.append("Period:  ").append(periodName).append('\n');
        if (className != null && !className.isBlank())       b.append("Class:   ").append(className).append('\n');
        if (subjectName != null && !subjectName.isBlank())   b.append("Subject: ").append(subjectName).append('\n');
        if (room != null && !room.isBlank())                 b.append("Room:    ").append(room).append('\n');
        b.append("\nPlease speak to the office if you cannot take this lesson.");

        return new Notice(email, subject, b.toString());
    }
}
