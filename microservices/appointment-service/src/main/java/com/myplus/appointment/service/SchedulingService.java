package com.myplus.appointment.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.appointment.entity.Booking;
import com.myplus.appointment.entity.Slot;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.BookingRepository;
import com.myplus.appointment.repository.SlotRepository;

/**
 * Slice SCHED-1 (B2) — the DOMAIN-NEUTRAL scheduling surface.
 *
 * <h3>What this service knows, and what it refuses to know</h3>
 *
 * It knows providers, time windows and who booked them. It does not know what a doctor is, what a teacher
 * is, or what a parents' evening is — {@code externalRef} is an opaque string the consumer gives meaning
 * to. That boundary is the entire point of D-9: {@code appointment-service} became unusable by education
 * precisely because it had learned what a Hospital was.
 *
 * <h3>Concurrency is handled the way the queue path learned to handle it</h3>
 *
 * Booking is a check-then-act (is there room? then insert), so the guarantee is the UNIQUE key
 * {@code uk_booking_slot_attendee} and the capacity check is only a friendlier message first. As on the
 * clinic path, <b>the retry cannot live inside the transaction</b>: a violated constraint leaves the
 * Hibernate session unusable. Each attempt therefore runs in its own transaction through {@code self}.
 */
@Service
@RequiredArgsConstructor
public class SchedulingService {

    private final SlotRepository slotRepo;
    private final BookingRepository bookingRepo;
    /** This bean via its proxy — a direct self-call would bypass {@code REQUIRES_NEW} entirely. */
    private final ObjectProvider<SchedulingService> self;

    /**
     * Publish slots by cutting a window into equal pieces.
     *
     * <p>Idempotent by construction: {@code uk_slot_provider_time} means re-running the same generation
     * cannot create a second copy of a slot. Existing slots are skipped and REPORTED rather than failing
     * the whole call — a school extending an evening from 18:00–19:00 to 18:00–20:00 should get the extra
     * hour, not an error about the hour it already published.
     */
    @Transactional
    public Map<String, Object> generate(Long orgId, Long providerId, Long venueId, String externalRef,
                                        LocalDateTime from, LocalDateTime to, int minutes, int capacity) {
        if (orgId == null || providerId == null) {
            throw new IllegalArgumentException("Organisation and provider are required");
        }
        List<SlotConflictDetector.Window> windows = SlotConflictDetector.generate(from, to, minutes);
        if (windows.isEmpty()) {
            // The pure detector already refused the input; say so in the caller's terms rather than
            // returning an empty success that looks like it worked.
            throw new IllegalArgumentException(
                    "That window produces no slots — check the start, the end and the slot length.");
        }

        int created = 0, existed = 0;
        for (SlotConflictDetector.Window w : windows) {
            try {
                self.getObject().saveSlot(orgId, providerId, venueId, externalRef, w, capacity);
                created++;
            } catch (DataIntegrityViolationException e) {
                // Same rule as book(): confirm it was uk_slot_provider_time and not some other violation.
                // Counting an unrelated failure as "already existed" would report a successful publish for
                // an evening that has no slots.
                if (!slotRepo.existsByOrganizationIdAndProviderIdAndStartsAt(orgId, providerId, w.startsAt())) {
                    throw e;
                }
                existed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("alreadyExisted", existed);
        return out;
    }

    /** ONE slot, in its own transaction, so a duplicate does not poison the whole generation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSlot(Long orgId, Long providerId, Long venueId, String externalRef,
                         SlotConflictDetector.Window w, int capacity) {
        slotRepo.save(Slot.builder()
                .organizationId(orgId).providerId(providerId).venueId(venueId).externalRef(externalRef)
                .startsAt(w.startsAt()).endsAt(w.endsAt())
                .capacity(capacity <= 0 ? 1 : capacity)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build());
    }

    /** The slots published under one reference, each with how many places are left. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByRef(Long orgId, String externalRef) {
        List<Slot> slots = slotRepo.findByRefScoped(externalRef, orgId);
        if (slots.isEmpty()) return List.of();

        // One query for every booking across these slots, rather than one per slot — the N+1 shape
        // education's 1.5 was caught by.
        List<Long> ids = new ArrayList<>();
        for (Slot s : slots) ids.add(s.getId());
        Map<Long, Integer> taken = new LinkedHashMap<>();
        for (Booking b : bookingRepo.findBySlotIdInAndOrganizationId(ids, orgId)) {
            taken.merge(b.getSlotId(), 1, Integer::sum);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Slot s : slots) {
            int used = taken.getOrDefault(s.getId(), 0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slotId", s.getId());
            m.put("providerId", s.getProviderId());
            m.put("startsAt", s.getStartsAt() == null ? null : s.getStartsAt().toString());
            m.put("endsAt", s.getEndsAt() == null ? null : s.getEndsAt().toString());
            m.put("capacity", s.getCapacity());
            m.put("booked", used);
            m.put("available", Math.max(0, (s.getCapacity() == null ? 1 : s.getCapacity()) - used));
            out.add(m);
        }
        return out;
    }

    /**
     * Book a slot for an attendee. Idempotent per (slot, attendee).
     *
     * <p>A double-clicked Book is ONE booking, reported as success rather than as an error — the caller
     * asked for a booking and has one. That is {@code uk_booking_slot_attendee} doing the work; the check
     * below only produces the friendlier answer when it wins the race.
     */
    public Map<String, Object> book(Long orgId, Long slotId, Long attendeeId, String externalRef) {
        try {
            return self.getObject().bookAttempt(orgId, slotId, attendeeId, externalRef);
        } catch (DataIntegrityViolationException e) {
            // ⚠ ASK THE DATABASE WHAT HAPPENED — never assume which constraint fired.
            //
            // The first cut caught this exception and declared "already booked" unconditionally. Every
            // booking then failed a NOT NULL check on venue_id (fixed in V5) and the API answered 200 with
            // NOTHING WRITTEN — a hard failure reported as an idempotent success. The gate caught it
            // because the first booking of a fresh slot came back as alreadyBooked, which is impossible.
            //
            // DataIntegrityViolationException covers ANY integrity violation, so it is only evidence that
            // something was rejected. This is the one interpretation that is safe to make, and only after
            // confirming it.
            if (bookingRepo.existsBySlotIdAndAttendeeIdAndOrganizationId(slotId, attendeeId, orgId)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("slotId", slotId);
                out.put("alreadyBooked", true);
                return out;
            }
            throw e;   // a DIFFERENT constraint — it must surface, not masquerade as success
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> bookAttempt(Long orgId, Long slotId, Long attendeeId, String externalRef) {
        if (attendeeId == null) throw new IllegalArgumentException("An attendee is required");

        // Anti-IDOR: the slot id came from the request, so it is resolved WITHIN the caller's tenant.
        Slot slot = slotRepo.findByIdScoped(slotId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found: " + slotId));

        // IDENTITY BEFORE CAPACITY, and the order is the whole point.
        //
        // A re-click by someone who already holds this slot is not a capacity question — they are not
        // asking for another place, they are asking for the one they have. Checking capacity first told
        // them "that time has just been taken" about their OWN booking, which the gate caught on a
        // capacity-1 slot: correct refusal, addressed to the wrong person.
        if (bookingRepo.existsBySlotIdAndAttendeeIdAndOrganizationId(slotId, attendeeId, orgId)) {
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("slotId", slotId);
            already.put("alreadyBooked", true);
            return already;
        }

        long used = bookingRepo.countBySlotIdAndOrganizationId(slotId, orgId);
        int capacity = slot.getCapacity() == null ? 1 : slot.getCapacity();
        if (used >= capacity) {
            // A capacity race can still slip past this check — two callers both read used = capacity - 1.
            // That is why capacity is enforced here and identity by the unique key: the pair covers the
            // case either alone would miss, and overbooking by one is visible rather than silent.
            throw new IllegalArgumentException("That time has just been taken. Please choose another.");
        }

        Booking b = bookingRepo.save(Booking.builder()
                .organizationId(orgId).slotId(slotId).attendeeId(attendeeId)
                .providerId(slot.getProviderId()).venueId(slot.getVenueId())
                .appointmentType(externalRef)
                .date(slot.getStartsAt() == null ? null : slot.getStartsAt().toLocalDate().toString())
                .dateTime(slot.getStartsAt() == null ? null : slot.getStartsAt().toString())
                .build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookingId", b.getId());
        out.put("slotId", slotId);
        out.put("alreadyBooked", false);
        return out;
    }

    /** Cancel a booking. Scoped by tenant, and silent when there is nothing to cancel (idempotent). */
    @Transactional
    public void cancel(Long orgId, Long bookingId) {
        bookingRepo.findByIdAndOrganizationId(bookingId, orgId).ifPresent(bookingRepo::delete);
    }
}
