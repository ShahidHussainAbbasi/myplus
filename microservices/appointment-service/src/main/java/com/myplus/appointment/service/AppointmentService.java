package com.myplus.appointment.service;

import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.dto.BookingRequest;
import com.myplus.appointment.entity.Booking;
import com.myplus.appointment.entity.Provider;
import com.myplus.appointment.entity.Venue;
import com.myplus.appointment.entity.Attendee;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.BookingRepository;
import com.myplus.appointment.repository.ProviderRepository;
import com.myplus.appointment.repository.VenueRepository;
import com.myplus.appointment.repository.AttendeeRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final BookingRepository repo;
    private final VenueRepository hospitalRepo;
    private final AttendeeRepository patientRepo;
    private final ProviderRepository doctorRepo;
    private final ModelMapper mapper;

    /**
     * This bean, through its Spring proxy (slice SCHED-1 B2).
     *
     * <p>Required so {@link #bookPublicAttempt}'s {@code REQUIRES_NEW} actually starts a new transaction. A
     * direct {@code this.bookPublicAttempt(...)} bypasses the proxy and the annotation does nothing at all —
     * a trap already recorded against this codebase.
     */
    private final ObjectProvider<AppointmentService> self;

    @Transactional
    public AppointmentDTO create(AppointmentDTO dto, Long orgId) {
        Booking a = mapper.map(dto, Booking.class);
        a.setId(null);
        a.setOrganizationId(orgId);
        return mapper.map(repo.save(a), AppointmentDTO.class);
    }

    public List<AppointmentDTO> list(Long orgId) {
        return enrich(repo.findByOrganizationId(orgId), orgId);
    }

    public List<AppointmentDTO> listByHospital(Long hospitalId, Long orgId) {
        return enrich(repo.findByVenueIdAndOrganizationId(hospitalId, orgId), orgId);
    }

    public AppointmentDTO get(Long id, Long orgId) {
        Booking a = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        return enrich(List.of(a), orgId).get(0);
    }

    /** Map bookings to DTOs and resolve provider/venue/attendee display names (org-scoped, one query each). */
    private List<AppointmentDTO> enrich(List<Booking> appts, Long orgId) {
        Map<Long, Provider> docs = doctorRepo.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(Provider::getId, d -> d, (a, b) -> a));
        Map<Long, Venue> hosps = hospitalRepo.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(Venue::getId, h -> h, (a, b) -> a));
        Map<Long, Attendee> pats = patientRepo.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(Attendee::getId, p -> p, (a, b) -> a));
        return appts.stream().map(a -> {
            AppointmentDTO dto = mapper.map(a, AppointmentDTO.class);
            Provider d = a.getProviderId() == null ? null : docs.get(a.getProviderId());
            Venue h = a.getVenueId() == null ? null : hosps.get(a.getVenueId());
            Attendee p = a.getAttendeeId() == null ? null : pats.get(a.getAttendeeId());
            if (d != null) dto.setDoctorName(d.getName());
            if (h != null) dto.setHospitalName(h.getName());
            if (p != null) { dto.setPatientName(p.getName()); dto.setPatientPhone(p.getPhone()); }
            return dto;
        }).toList();
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Booking a = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found: " + id));
        repo.delete(a);
    }

    /**
     * Anonymous public booking, retrying if someone takes our queue number first.
     *
     * <h3>NOT {@code @Transactional}, and that is the whole point (slice SCHED-1 B2)</h3>
     *
     * The queue number is assigned by a read-then-increment, which is a check-then-act: two concurrent
     * bookings both read 7 and both write 8. V4's {@code uk_booking_queue} now makes that impossible in the
     * database — but a raw duplicate-key error is not an answer a caller can act on, so the collision has to
     * be retried.
     *
     * <p><b>The first cut retried INSIDE the transaction and did not work.</b> A 10-way concurrent test
     * returned {@code "null id in Booking entry (don't flush the Session after an exception occurs)"}: once
     * a constraint violation fires, the Hibernate session is unrecoverable, so no loop sharing that session
     * can succeed however it is written. Measured, not reasoned about.
     *
     * <p>So the retry lives out here, and each attempt runs in its own transaction and session via
     * {@link #bookPublicAttempt} — called through {@code self} so the proxy, and therefore
     * {@code REQUIRES_NEW}, is actually applied.
     */
    public AppointmentDTO bookPublic(BookingRequest req) {
        for (int attempt = 0; ; attempt++) {
            try {
                return self.getObject().bookPublicAttempt(req);
            } catch (DataIntegrityViolationException e) {
                // Somebody took this queue number between our read and our write. uk_booking_queue caught
                // it; this loop decides what the loser is told.
                if (attempt >= 4) {
                    // An honest refusal beats a wrong number: "try again" is true, whereas handing out a
                    // queue position somebody else also holds is exactly the defect §9d described.
                    throw new IllegalArgumentException(
                            "Too many people are booking at once. Please try again in a moment.");
                }
            }
        }
    }

    /**
     * ONE booking attempt, in its own transaction. Retried by {@link #bookPublic} on a queue collision.
     *
     * <p>Replicates the legacy flow: infer the org from the target venue, compute the provider's daily
     * capacity, assign the next queue number, enforce the daily limit, reuse or create the attendee by
     * phone within the org, and reject blocked attendees.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppointmentDTO bookPublicAttempt(BookingRequest req) {
        Venue h = hospitalRepo.findById(req.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + req.getHospitalId()));
        Long orgId = h.getOrganizationId();
        Provider d = doctorRepo.findById(req.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + req.getDoctorId()));

        Attendee patient = patientRepo.findFirstByPhoneAndOrganizationId(req.getPatientPhone(), orgId).orElse(null);
        if (patient != null && patient.isBlocked()) {
            throw new IllegalArgumentException("This number is blocked from booking appointments.");
        }

        int capacity = capacityFor(d);
        String date = req.getDate() != null ? req.getDate() : LocalDate.now().toString();
        int lastAppointed = repo.findFirstByVenueIdAndProviderIdAndDateOrderByIdDesc(h.getId(), d.getId(), date)
                .map(a -> a.getPatientsAppointed() == null ? 0 : a.getPatientsAppointed()).orElse(0);
        int appointed = lastAppointed + 1;
        if (appointed > capacity) {
            throw new IllegalArgumentException("Today's appointments reached the limit. Please try again tomorrow.");
        }

        if (patient == null) {
            patient = patientRepo.save(Attendee.builder()
                    .organizationId(orgId).name(req.getPatientName()).phone(req.getPatientPhone())
                    .email(req.getPatientEmail()).address(req.getPatientAddress()).blocked(false).build());
        }

        // A collision on uk_booking_queue throws DataIntegrityViolationException, which bookPublic() above
        // catches and retries in a FRESH transaction. It cannot be caught here: this session is finished
        // the moment the constraint fires.
        Booking a = repo.save(Booking.builder()
                .organizationId(orgId).venueId(h.getId()).providerId(d.getId()).attendeeId(patient.getId())
                .appointmentType(req.getAppointmentType())
                .dateTime(req.getDateTime() != null ? req.getDateTime() : LocalDateTime.now().toString())
                .date(date)
                .patientsToVisit(capacity == Integer.MAX_VALUE ? null : capacity)
                .patientsAppointed(appointed).patientsVisited(0).build());
        AppointmentDTO dto = mapper.map(a, AppointmentDTO.class);
        dto.setDoctorName(d.getName());
        dto.setHospitalName(h.getName());
        dto.setPatientName(patient.getName());
        dto.setPatientPhone(patient.getPhone());
        return dto;
    }

    /** Daily capacity: "count" -> fixed offerValue; time-based -> (hours*60)/offerValue; unknown -> unlimited. */
    private int capacityFor(Provider d) {
        Integer val = d.getAppointmentOfferValue();
        if (val == null || val <= 0) {
            return Integer.MAX_VALUE;
        }
        if ("count".equalsIgnoreCase(d.getAppointmentOfferType())) {
            return val;
        }
        try {
            int hours = Integer.parseInt(d.getTimeOut().split(":")[0]) - Integer.parseInt(d.getTimeIn().split(":")[0]);
            int slots = (hours * 60) / val;
            return slots > 0 ? slots : Integer.MAX_VALUE;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
