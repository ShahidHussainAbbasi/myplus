package com.myplus.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.appointment.entity.Provider;
import com.myplus.appointment.entity.Venue;
import com.myplus.appointment.repository.ProviderRepository;
import com.myplus.appointment.repository.VenueRepository;

/** P-BOOK-1 — the anonymous booking page reads public fields only, and a venue lists only its own doctors. */
class PublicDirectoryServiceTest {

    VenueRepository venues; ProviderRepository doctors; PublicDirectoryService service;

    @BeforeEach
    void setUp() {
        venues = mock(VenueRepository.class); doctors = mock(ProviderRepository.class);
        service = new PublicDirectoryService(venues, doctors);
    }

    @Test
    void venues_carry_no_contact_detail_or_organisation() {
        when(venues.findAll()).thenReturn(List.of(
                Venue.builder().id(2L).organizationId(9L).name("Zainab Clinic").city("Lahore").email("z@x.pk").phone("0300").build(),
                Venue.builder().id(1L).organizationId(8L).name("Al-Shifa").city("Karachi").email("a@x.pk").phone("0301").build()));
        var out = service.venues();
        assertThat(out).extracting(PublicDirectoryService.PublicVenue::getName).containsExactly("Al-Shifa", "Zainab Clinic");
        // the public shape has only id, name, city: no email, phone or organisation id can leak
        assertThat(Arrays.stream(PublicDirectoryService.PublicVenue.class.getDeclaredFields()).map(f -> f.getName()))
                .containsExactlyInAnyOrder("id", "name", "city");
    }

    @Test
    void a_venue_lists_only_its_own_organisations_doctors_with_public_fields() {
        when(venues.findById(1L)).thenReturn(Optional.of(Venue.builder().id(1L).organizationId(8L).name("Al-Shifa").build()));
        when(doctors.findByVenueIdAndOrganizationId(1L, 8L)).thenReturn(List.of(
                Provider.builder().id(5L).organizationId(8L).venueId(1L).name("Dr Sana").speciality("ENT")
                        .email("s@x.pk").mobile("0333").fee("1500").timeIn("09:00").timeOut("13:00").build()));
        var out = service.doctorsAt(1L);
        assertThat(out).extracting(PublicDirectoryService.PublicDoctor::getName).containsExactly("Dr Sana");
        assertThat(Arrays.stream(PublicDirectoryService.PublicDoctor.class.getDeclaredFields()).map(f -> f.getName()))
                .containsExactlyInAnyOrder("id", "name", "speciality", "dayFrom", "dayTo", "timeIn", "timeOut")
                .doesNotContain("email", "mobile", "fee", "organizationId");
    }

    @Test
    void an_unknown_venue_or_doctor_is_not_found() {
        when(venues.findById(77L)).thenReturn(Optional.empty());
        when(doctors.findById(78L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.doctorsAt(77L)).hasMessage("Hospital not found: 77");
        assertThatThrownBy(() -> service.doctor(78L)).hasMessage("Doctor not found: 78");
    }
}
