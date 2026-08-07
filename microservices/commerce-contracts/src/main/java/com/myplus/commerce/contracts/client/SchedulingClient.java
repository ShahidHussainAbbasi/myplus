package com.myplus.commerce.contracts.client;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Slice SCHED-1 (B3) — booking time in the shared scheduling core.
 * Design: microservices/docs/slices/sched-1-scheduling-core.md
 *
 * <p>Contract only (DIP): the proxy is built from a load-balanced RestClient in each consuming service,
 * against {@code lb://appointment-service/api/scheduling}.
 *
 * <h3>Note what this interface does NOT contain</h3>
 *
 * No doctor, no hospital, no patient — and no teacher, guardian or parents' evening either. It speaks only
 * of <b>providers</b>, <b>slots</b>, <b>attendees</b> and an opaque <b>ref</b>. That is the whole result of
 * decision D-9: {@code appointment-service} was unusable by education precisely because its vocabulary had
 * become a clinic's, so the shared surface must stay domain-free or the next consumer inherits education's
 * words instead.
 *
 * <p>The {@code ref} is a string this service never interprets. Education passes its meeting-event key; a
 * clinic could pass a session id. <b>The core schedules time; the domain knows why.</b>
 */
@HttpExchange(accept = "application/json")
public interface SchedulingClient {

    /**
     * Publish slots by cutting {@code from}–{@code to} into pieces of {@code minutes} each.
     *
     * <p><b>Idempotent</b>: re-running the same generation creates nothing and reports what already
     * existed, so extending an evening adds only the new part. Returns {@code {created, alreadyExisted}}.
     *
     * <p>{@code from}/{@code to} are ISO-8601 local date-times ({@code 2026-10-01T18:00:00}).
     */
    @PostExchange("/slots/generate")
    Map<String, Object> generate(@RequestParam("providerId") Long providerId,
                                 // OPTIONAL, and it must be: a parents' evening has no venue — it happens
                                 // at the school, which is not a row anybody creates. Declared required in
                                 // the first cut, which made every education publish fail with
                                 // "Missing request parameter value 'venueId'". Same root cause as V5,
                                 // where booking.venue_id was NOT NULL for the same clinic-shaped reason.
                                 @RequestParam(value = "venueId", required = false) Long venueId,
                                 @RequestParam(value = "ref", required = false) String ref,
                                 @RequestParam("from") String from,
                                 @RequestParam("to") String to,
                                 @RequestParam("minutes") int minutes,
                                 @RequestParam("capacity") int capacity);

    /** The slots published under one ref, each with {@code capacity}, {@code booked} and {@code available}. */
    @GetExchange("/slots")
    Map<String, Object> slots(@RequestParam("ref") String ref);

    /**
     * Book a slot for an attendee. <b>Idempotent per (slot, attendee)</b> — a double-clicked Book returns
     * the existing booking with {@code alreadyBooked: true} rather than an error, because the caller asked
     * for a booking and has one.
     */
    @PostExchange("/bookings")
    Map<String, Object> book(@RequestParam("slotId") Long slotId,
                             @RequestParam("attendeeId") Long attendeeId,
                             // Optional for the same reason as generate's: a null required param is a
                             // client-side failure before the request is even sent.
                             @RequestParam(value = "ref", required = false) String ref);

    @DeleteExchange("/bookings/{id}")
    Map<String, Object> cancel(@org.springframework.web.bind.annotation.PathVariable("id") Long id);
}
