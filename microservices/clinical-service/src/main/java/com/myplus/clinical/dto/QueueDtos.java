package com.myplus.clinical.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HMS S2 — tokens, the board and the doctors, as the front desk sends and sees them. */
public final class QueueDtos {

    private QueueDtos() {}

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class IssueRequest {
        private Long patientId;
        private Long providerId;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MoveRequest {
        private String reason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TokenView {
        private Long id;
        private String tokenLabel;
        private Integer tokenNo;
        private String status;
        private Long patientId;
        private String patientName;
        private String mrn;
        private Long providerId;
        private String providerName;
        private LocalDate visitDate;
        /** WAITING tokens ahead of this one in the same doctor's line (0 = next). Null once not waiting. */
        private Integer ahead;
        private String parkReason;
        private LocalDateTime createdAt;
        private LocalDateTime calledAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private Long version;
    }

    /** A doctor as the clinic shows them: who, the token letter, and today's numbers against the limit. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DoctorView {
        private Long id;
        private String name;
        private String speciality;
        private String fee;
        private String tokenPrefix;
        /** The doctor's usual daily limit; null = no limit. */
        private Integer usualLimit;
        /** Today's limit after any one-day change; null = no limit. */
        private Integer todayLimit;
        private boolean todayChanged;
        private boolean closedToday;
        private long issuedToday;
        private long waitingNow;
        private long withDoctorNow;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class NewDoctorRequest {
        private String name;
        private String speciality;
        private String fee;
        private String mobile;
        /** Patients a day; null or 0 = no limit (client decision B-03). */
        private Integer dailyLimit;
    }

    /** One day's change to a doctor's limit. {@code reset=true} goes back to the usual limit. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DayRequest {
        private Long providerId;
        /** yyyy-MM-dd; blank = today. */
        private String date;
        /** null or 0 = no limit that day. */
        private Integer limit;
        private boolean closed;
        private boolean reset;
    }
}
