package com.myplus.clinical.config;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HMS S2 — appointment-service, as the clinic sees it: the doctors (providers) and the venue they belong to. The
 * doctor stays appointment-service's (education books parents' evenings on the same core); the clinic only reads
 * the list and adds doctors through it. The caller's identity is forwarded, so appointment-service scopes to the
 * same organisation. Local to clinical-service — no other service needs this view.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface AppointmentDirectoryClient {

    @GetExchange("/api/appointment/doctors")
    Envelope<List<Doctor>> doctors();

    @PostExchange("/api/appointment/doctors")
    Envelope<Doctor> createDoctor(@RequestBody Doctor doctor);

    @GetExchange("/api/appointment/hospitals")
    Envelope<List<Venue>> venues();

    @PostExchange("/api/appointment/hospitals")
    Envelope<Venue> createVenue(@RequestBody Venue venue);

    /** appointment-service's ApiResponse. */
    @Data @NoArgsConstructor @AllArgsConstructor
    class Envelope<T> {
        private boolean success;
        private String message;
        private T data;
    }

    /** appointment-service's DoctorDTO, the fields the clinic uses. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    class Doctor {
        private Long id;
        private Long hospitalId;
        private String name;
        private String speciality;
        private String fee;
        private String mobile;
        private String timeIn;
        private String timeOut;
        /** "count" = a number of patients a day; anything else = minutes per patient. */
        private String appointmentOfferType;
        /** null or 0 = no daily limit. */
        private Integer appointmentOfferValue;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    class Venue {
        private Long id;
        private String name;
        private String phone;
        private String country;
        private String city;
    }
}
