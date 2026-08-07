package com.myplus.appointment.repository;

import com.myplus.appointment.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByOrganizationId(Long organizationId);
    List<Booking> findByVenueIdAndOrganizationId(Long venueId, Long organizationId);
    Optional<Booking> findByIdAndOrganizationId(Long id, Long organizationId);
    /**
     * SCHED-1: renamed with the entity. A derived query names ENTITY properties, so this method still read
     * {@code HospitalId}/{@code DoctorId} after {@code Booking} moved to {@code venueId}/{@code providerId}
     * — which COMPILES and then throws {@code PropertyReferenceException} at context startup, taking the
     * whole service down. The compiler cannot see it; only booting the app (or this rename) does.
     */
    Optional<Booking> findFirstByVenueIdAndProviderIdAndDateOrderByIdDesc(Long venueId, Long providerId, String date);

    /** How many bookings a slot already holds — checked against {@code Slot.capacity} before accepting one more. */
    long countBySlotIdAndOrganizationId(Long slotId, Long organizationId);

    /** Everything booked against a set of slots, so a screen can show "3 of 6 taken" without N queries. */
    List<Booking> findBySlotIdInAndOrganizationId(List<Long> slotIds, Long organizationId);

    /**
     * Does this attendee already hold this slot?
     *
     * <p>Exists so a caught {@code DataIntegrityViolationException} can be IDENTIFIED rather than assumed.
     * The first cut caught that exception and reported "already booked" for any integrity violation at all
     * — which turned a NOT NULL failure into a fake 200 with nothing written. Asking the database what
     * actually happened is the difference between an idempotent success and a swallowed error.
     */
    boolean existsBySlotIdAndAttendeeIdAndOrganizationId(Long slotId, Long attendeeId, Long organizationId);
}
