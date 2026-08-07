package com.myplus.appointment;

import com.myplus.appointment.dto.AppointmentDTO;
import com.myplus.appointment.dto.DoctorDTO;
import com.myplus.appointment.entity.Booking;
import com.myplus.appointment.entity.Provider;
import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AppointmentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppointmentServiceApplication.class, args);
    }

    /**
     * Slice SCHED-1 (B1) — the mapping layer that keeps the clinic's API contract still while the model
     * underneath goes domain-neutral.
     *
     * <h3>Why these four TypeMaps have to be explicit</h3>
     *
     * ModelMapper matches by property NAME. The rename moved three fields:
     *
     * <pre>
     *   Booking.venueId    ← was hospitalId      AppointmentDTO.hospitalId  (UNCHANGED — the API)
     *   Booking.providerId ← was doctorId        AppointmentDTO.doctorId    (UNCHANGED)
     *   Booking.attendeeId ← was patientId       AppointmentDTO.patientId   (UNCHANGED)
     *   Provider.venueId   ← was hospitalId      DoctorDTO.hospitalId       (UNCHANGED)
     * </pre>
     *
     * <b>Without these maps nothing fails to compile and nothing throws</b> — ModelMapper simply leaves the
     * unmatched fields null, so every appointment would come back with no hospital, no doctor and no
     * patient, and every write would store nulls. That is standard D9 form 6 (the JSON contract) crossed
     * with its silent-failure mode: the exact shape of defect that reaches a browser rather than a build.
     *
     * <p>They are declared here, next to the bean, rather than inside the services, so there is one place
     * to look when the contract is eventually migrated (the "contract" half of expand/contract).
     */
    @Bean
    public ModelMapper modelMapper() {
        ModelMapper mapper = new ModelMapper();

        // entity → DTO (reads)
        mapper.createTypeMap(Booking.class, AppointmentDTO.class).addMappings(m -> {
            m.map(Booking::getVenueId, AppointmentDTO::setHospitalId);
            m.map(Booking::getProviderId, AppointmentDTO::setDoctorId);
            m.map(Booking::getAttendeeId, AppointmentDTO::setPatientId);
        });
        // DTO → entity (writes)
        mapper.createTypeMap(AppointmentDTO.class, Booking.class).addMappings(m -> {
            m.map(AppointmentDTO::getHospitalId, Booking::setVenueId);
            m.map(AppointmentDTO::getDoctorId, Booking::setProviderId);
            m.map(AppointmentDTO::getPatientId, Booking::setAttendeeId);
        });

        mapper.createTypeMap(Provider.class, DoctorDTO.class)
                .addMappings(m -> m.map(Provider::getVenueId, DoctorDTO::setHospitalId));
        mapper.createTypeMap(DoctorDTO.class, Provider.class)
                .addMappings(m -> m.map(DoctorDTO::getHospitalId, Provider::setVenueId));

        return mapper;
    }
}
