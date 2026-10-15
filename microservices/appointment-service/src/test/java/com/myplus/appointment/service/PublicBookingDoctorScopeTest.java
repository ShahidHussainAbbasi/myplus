package com.myplus.appointment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.ObjectProvider;

import com.myplus.appointment.dto.BookingRequest;
import com.myplus.appointment.entity.Provider;
import com.myplus.appointment.entity.Venue;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.AttendeeRepository;
import com.myplus.appointment.repository.BookingRepository;
import com.myplus.appointment.repository.ProviderRepository;
import com.myplus.appointment.repository.VenueRepository;

/**
 * HMS H1 — a public booking may name only a doctor of THIS venue's organisation who sits at THIS venue. The doctor
 * used to be loaded by id alone: another organisation's doctor could be booked into this venue.
 */
class PublicBookingDoctorScopeTest {

    BookingRepository bookings; VenueRepository venues; AttendeeRepository patients; ProviderRepository doctors;
    AppointmentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bookings = mock(BookingRepository.class); venues = mock(VenueRepository.class);
        patients = mock(AttendeeRepository.class); doctors = mock(ProviderRepository.class);
        service = new AppointmentService(bookings, venues, patients, doctors, new ModelMapper(), mock(ObjectProvider.class));
        when(venues.findById(10L)).thenReturn(Optional.of(Venue.builder().id(10L).organizationId(1L).name("Clinic A").build()));
    }

    private static BookingRequest request(Long doctorId) {
        BookingRequest r = new BookingRequest();
        r.setHospitalId(10L);
        r.setDoctorId(doctorId);
        r.setPatientName("Ali");
        r.setPatientPhone("03001234567");
        return r;
    }

    @Test
    void another_organisations_doctor_is_not_found_and_nothing_is_booked() {
        // doctor 99 belongs to organisation 2: the org-scoped lookup finds nothing
        when(doctors.findByIdAndOrganizationId(99L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.bookPublicAttempt(request(99L)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Doctor not found: 99");
        verify(doctors, never()).findById(any());
        verify(bookings, never()).save(any());
        verify(patients, never()).save(any());
    }

    @Test
    void a_doctor_of_this_organisation_at_another_venue_is_not_found_either() {
        when(doctors.findByIdAndOrganizationId(7L, 1L))
                .thenReturn(Optional.of(Provider.builder().id(7L).organizationId(1L).venueId(11L).name("Dr Other Venue").build()));
        assertThatThrownBy(() -> service.bookPublicAttempt(request(7L)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Doctor not found: 7");
        verify(bookings, never()).save(any());
    }
}
