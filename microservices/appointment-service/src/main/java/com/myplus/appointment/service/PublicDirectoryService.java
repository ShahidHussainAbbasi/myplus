package com.myplus.appointment.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.appointment.entity.Provider;
import com.myplus.appointment.entity.Venue;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.ProviderRepository;
import com.myplus.appointment.repository.VenueRepository;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * P-BOOK-1 — what an ANONYMOUS visitor of the public booking page may read: the venues, a venue's doctors, a doctor's
 * timings. Public fields only — never a venue's or doctor's email, phone, mobile, address or fee, and never the
 * organisation id.
 *
 * <p>The page used to read the logged-in, organisation-scoped lists ({@code /hospitals}, {@code /doctors}). Without a
 * login the monolith fell back to a direct URL that does not exist inside Docker, so the public page listed no venue
 * at all; signed in, it listed only the visitor's own business. These reads are what the page always meant.
 */
@Service
@RequiredArgsConstructor
public class PublicDirectoryService {

    private final VenueRepository venues;
    private final ProviderRepository doctors;

    @Value @Builder
    public static class PublicVenue {
        Long id;
        String name;
        String city;
    }

    @Value @Builder
    public static class PublicDoctor {
        Long id;
        String name;
        String speciality;
        String dayFrom;
        String dayTo;
        String timeIn;
        String timeOut;
    }

    @Transactional(readOnly = true)
    public List<PublicVenue> venues() {
        return venues.findAll().stream()
                .sorted(Comparator.comparing(v -> v.getName() == null ? "" : v.getName().toLowerCase()))
                .map(v -> PublicVenue.builder().id(v.getId()).name(v.getName()).city(v.getCity()).build())
                .toList();
    }

    /** A venue's doctors: those AT that venue and of that venue's own organisation (the H1 rule, read side). */
    @Transactional(readOnly = true)
    public List<PublicDoctor> doctorsAt(Long venueId) {
        Venue v = venues.findById(venueId).orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + venueId));
        return doctors.findByVenueIdAndOrganizationId(v.getId(), v.getOrganizationId()).stream()
                .sorted(Comparator.comparing(d -> d.getName() == null ? "" : d.getName().toLowerCase()))
                .map(PublicDirectoryService::publicDoctor).toList();
    }

    @Transactional(readOnly = true)
    public PublicDoctor doctor(Long id) {
        return doctors.findById(id).map(PublicDirectoryService::publicDoctor)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + id));
    }

    static PublicDoctor publicDoctor(Provider d) {
        return PublicDoctor.builder().id(d.getId()).name(d.getName()).speciality(d.getSpeciality())
                .dayFrom(d.getDayFrom()).dayTo(d.getDayTo()).timeIn(d.getTimeIn()).timeOut(d.getTimeOut()).build();
    }
}
