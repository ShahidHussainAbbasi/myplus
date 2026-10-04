package com.myplus.education.service;

import java.time.LocalDate;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.contracts.client.SchedulingClient;
import com.myplus.education.entity.MeetingEvent;
import com.myplus.education.entity.MeetingEventStatus;
import com.myplus.education.entity.Staff;
import com.myplus.education.repository.MeetingEventRepository;
import com.myplus.education.repository.StaffRepository;

/**
 * Slice edu-3.4 (on the SCHED-1 core) — <b>the translation layer between a school's words and the
 * scheduling core's.</b>
 *
 * <pre>
 *   education                       scheduling core
 *   ─────────                       ───────────────
 *   Staff (a teacher)      →        providerId
 *   Guardian               →        attendeeId
 *   a parents' evening     →        ref  ("EDU-EVT-7", opaque to the core)
 *   "10 minutes each"      →        minutes
 * </pre>
 *
 * <h3>Why the mapping lives here and nowhere else</h3>
 *
 * The core must never learn what a teacher is — that is the entire content of decision D-9, and the reason
 * {@code appointment-service} could not serve education in the first place (it had learned what a Hospital
 * was). Keeping every translation in one class means the core's vocabulary cannot leak into education's
 * controllers, and education's cannot leak into the core.
 *
 * <h3>The core owns the guarantees; this class owns the domain rules</h3>
 *
 * Double-booking, capacity and idempotency are enforced by UNIQUE keys in the core (SCHED-1 B2). What lives
 * here is what only education can know: whether the evening is open, and whether a child belongs to the
 * guardian doing the booking.
 */
@Service
public class MeetingService {

    /** Owner-configurable, read on the path it governs (C1). A school decides how long a slot is. */
    public static final String SLOT_MINUTES = "edu.meetings.slotMinutes";

    @Autowired private MeetingEventRepository eventRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired(required = false) private SchedulingClient schedulingClient;
    @Autowired private com.myplus.common.settings.SettingsService settingsService;

    /**
     * Publish a teacher's slots for an evening.
     *
     * <p>Idempotent because the core is: re-running for the same teacher and window creates nothing and
     * reports what already existed, so a school extending an evening gets only the new part rather than an
     * error about the part it already published.
     */
    public Map<String, Object> publishSlots(MeetingEvent event, Long staffId,
                                            String fromIso, String toIso, Integer minutesOverride) {
        if (schedulingClient == null) {
            // Optional dependency, surfaced rather than swallowed — the same choice 3.1b made for
            // provisioning: a school must learn now that nothing was published, not when a family calls.
            throw new IllegalStateException("The scheduling service is unavailable. No slots were published.");
        }
        int minutes = minutesOverride != null && minutesOverride > 0
                ? minutesOverride
                : settingsService.getInt(SLOT_MINUTES, 10);

        // capacity 1: a parents' evening slot is one family's ten minutes. A school wanting group sessions
        // would pass a capacity, which the core already supports — it is simply not what this screen means.
        final int slotMinutes = minutes;
        return onceMoreOnTimeout(
                () -> schedulingClient.generate(staffId, null, event.schedulingRef(), fromIso, toIso, slotMinutes, 1),
                "The scheduling service did not answer in time. The slots may already be published — "
                        + "open the evening and check before publishing again.");
    }

    /**
     * The slots for an evening, with each teacher's name attached.
     *
     * <p>The core returns {@code providerId}; a family needs "Miss Khan". Resolved from ONE staff query
     * rather than one per slot — the N+1 shape 1.5 was caught by, and a busy evening is a hundred slots.
     */
    public List<Map<String, Object>> slotsFor(MeetingEvent event, Long orgId) {
        if (schedulingClient == null || event == null) return List.of();

        Map<String, Object> res = onceMoreOnTimeout(() -> schedulingClient.slots(event.schedulingRef()),
                "The scheduling service did not answer in time. Reload to see the slots.");
        Object data = res == null ? null : res.get("data");
        if (!(data instanceof List<?> raw)) return List.of();

        Map<Long, String> teacherNames = new HashMap<>();
        for (Staff s : staffRepository.findScoped(orgId, null)) teacherNames.put(s.getId(), s.getName());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("slotId", m.get("slotId"));
            slot.put("startsAt", m.get("startsAt"));
            slot.put("endsAt", m.get("endsAt"));
            slot.put("available", m.get("available"));
            Long providerId = asLong(m.get("providerId"));
            // The core's word is providerId; the family reads a teacher's name. The translation is the
            // whole job of this class.
            slot.put("teacherName", providerId == null ? null : teacherNames.get(providerId));
            out.add(slot);
        }
        return out;
    }

    /**
     * Book a slot for a guardian.
     *
     * <p>Refuses on a CLOSED evening — that is education's rule and only education can enforce it; the core
     * knows nothing about evenings. Everything else (one booking per guardian per slot, capacity, the
     * double-click) is the core's UNIQUE keys, and is not re-implemented here.
     */
    public Map<String, Object> book(MeetingEvent event, Long slotId, Long guardianId) {
        if (schedulingClient == null) {
            throw new IllegalStateException("The scheduling service is unavailable. Nothing was booked.");
        }
        if (event == null || event.getStatus() != MeetingEventStatus.OPEN) {
            throw new IllegalArgumentException("Booking for this evening is closed.");
        }
        Map<String, Object> res = onceMoreOnTimeout(
                () -> schedulingClient.book(slotId, guardianId, event.schedulingRef()),
                "The scheduling service did not answer in time. The booking may already be made — "
                        + "reload the slots before booking again.");
        Object data = res == null ? null : res.get("data");
        return data instanceof Map<?, ?> m ? new LinkedHashMap<>(castMap(m)) : new LinkedHashMap<>();
    }

    /** Cancel a booking in the core. Idempotent there, so a double-clicked Cancel is one cancellation. */
    public void cancel(Long bookingId) {
        if (schedulingClient == null) return;
        onceMoreOnTimeout(() -> { schedulingClient.cancel(bookingId); return null; },
                "The scheduling service did not answer in time. The booking may already be cancelled — "
                        + "reload before trying again.");
    }

    /** The evening a family may book, or null. Newest open evening — a school runs one at a time. */
    @Transactional(readOnly = true)
    public MeetingEvent openEvent(Long orgId) {
        List<MeetingEvent> open = eventRepository.findOpenForPortal(orgId, MeetingEventStatus.OPEN);
        return open.isEmpty() ? null : open.get(0);
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /**
     * SCHED-2 — one more try when the scheduling core does not answer, then the truth.
     *
     * <p>Every call on this client is idempotent on the other side, by a UNIQUE key: a publish creates a slot
     * at most once ({@code uk_slot_provider_time}), a booking once per family per slot
     * ({@code uk_booking_slot_attendee}), a cancel is by id, and listing is a read. So after a timeout the work
     * may well have been done, and asking again is safe: the second answer says so ({@code alreadyExisted},
     * {@code alreadyBooked}).
     *
     * <p>Before this a read timeout fell through to the controller's catch-all and the school saw
     * {@code ERROR} and a raw I/O message — for slots that had been created. If the retry also fails the
     * caller now gets an {@link IllegalStateException} saying what is actually known, which the controllers
     * already answer as FAILED with that sentence, and log.
     */
    <T> T onceMoreOnTimeout(java.util.function.Supplier<T> call, String whenStillUnanswered) {
        try {
            return call.get();
        } catch (org.springframework.web.client.ResourceAccessException first) {
            try {
                return call.get();
            } catch (org.springframework.web.client.ResourceAccessException again) {
                throw new IllegalStateException(whenStillUnanswered, again);
            }
        }
    }
}
