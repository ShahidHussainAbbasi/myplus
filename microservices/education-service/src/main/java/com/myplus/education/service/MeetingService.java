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
        return schedulingClient.generate(staffId, null, event.schedulingRef(), fromIso, toIso, minutes, 1);
    }

    /**
     * The slots for an evening, with each teacher's name attached.
     *
     * <p>The core returns {@code providerId}; a family needs "Miss Khan". Resolved from ONE staff query
     * rather than one per slot — the N+1 shape 1.5 was caught by, and a busy evening is a hundred slots.
     */
    public List<Map<String, Object>> slotsFor(MeetingEvent event, Long orgId) {
        if (schedulingClient == null || event == null) return List.of();

        Map<String, Object> res = schedulingClient.slots(event.schedulingRef());
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
        Map<String, Object> res = schedulingClient.book(slotId, guardianId, event.schedulingRef());
        Object data = res == null ? null : res.get("data");
        return data instanceof Map<?, ?> m ? new LinkedHashMap<>(castMap(m)) : new LinkedHashMap<>();
    }

    /** Cancel a booking in the core. Idempotent there, so a double-clicked Cancel is one cancellation. */
    public void cancel(Long bookingId) {
        if (schedulingClient != null) schedulingClient.cancel(bookingId);
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
}
