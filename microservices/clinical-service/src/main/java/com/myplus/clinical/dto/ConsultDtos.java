package com.myplus.clinical.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HMS S3a — what the doctor sends and sees. */
public final class ConsultDtos {

    private ConsultDtos() {}

    /** Vitals as typed (strings): the server parses and range-checks them. Blank = not measured. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class EncounterUpdate {
        private String chiefComplaint;
        private String bloodPressure;   // "120/80"
        private String pulse;
        private String temperatureF;
        private String spo2;
        private String weightKg;
        private String heightCm;
        private Long version;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class NoteRequest {
        private String body;
        private Long amendsNoteId;
    }

    /** HMS S3b-1 — one prescription line, as the screen sends and shows it. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RxLine {
        private Long productId;
        private String medicineName;
        private String quantity;     // text from the form; EncounterRules turns it into a whole number
        private String dosage;       // "1 tab"
        private String frequency;    // "TDS"
        private String duration;     // "5 days"
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RxRequest {
        private List<RxLine> lines;
    }

    /** HMS S3b-2 — save the lines on screen as a template. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TemplateRequest {
        private String name;
        private List<RxLine> lines;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TemplateView {
        private Long id;
        private String name;
        private List<RxLine> lines;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class NoteView {
        private Long id;
        private String body;
        private Long authorUserId;
        private Long amendsNoteId;
        private LocalDateTime createdAt;
    }

    /** Who the patient is — shown before anything clinical (two identifiers: name + MRN, and date of birth). */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Identity {
        private Long patientId;
        private String name;
        private String mrn;
        private LocalDate dateOfBirth;
        private Integer ageYears;
        private String sex;
        private String phone;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class EncounterView {
        private Long id;
        private Long tokenId;
        private String tokenLabel;
        private String tokenStatus;
        private String status;
        private Long providerId;
        private String providerName;
        private Identity patient;
        private String chiefComplaint;
        private String bloodPressure;
        private Integer pulse;
        private BigDecimal temperatureF;
        private Integer spo2;
        private BigDecimal weightKg;
        private BigDecimal heightCm;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private List<NoteView> notes;
        private List<RxLine> rxLines;          // S3b-1: the doctor's prescription of this visit
        private Long rxId;                     // set once submitted: the pharmacy's prescription
        private LocalDateTime rxSubmittedAt;
        /** Earlier visits of this patient, newest first (each with its notes). */
        private List<EncounterView> history;
        private Long version;
    }
}
