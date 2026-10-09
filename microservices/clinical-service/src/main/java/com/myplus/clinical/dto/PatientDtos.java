package com.myplus.clinical.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HMS S1 — what the front desk sends and sees. */
public final class PatientDtos {

    private PatientDtos() {}

    /** Register: only {@code phone} is required (client decision M-01). */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RegisterRequest {
        private String phone;
        private String name;
        private String cnic;
        /** yyyy-MM-dd */
        private String dateOfBirth;
        /** M | F | O */
        private String sex;
        /** Only honoured when the clinic allows family members on one phone. */
        private Boolean addFamilyMember;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateRequest {
        private String name;
        private String cnic;
        private String dateOfBirth;
        private String sex;
        /** The version the form loaded — a stale edit is refused, never silently overwritten. */
        private Long version;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PatientView {
        private Long id;
        private String mrn;
        private String phone;
        private String name;
        private String cnic;
        private LocalDate dateOfBirth;
        private String sex;
        private Integer familySeq;
        private String status;
        private Long partyId;
        private Long customerId;
        /** True while the person or the pharmacy customer is not linked yet (a service was unreachable). */
        private boolean linkPending;
        private LocalDateTime createdAt;
        private Long version;
    }

    /** Who is on a phone number, and whether the front desk may add another family member on it. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PhoneLookup {
        private String phone;
        private List<PatientView> patients;
        private boolean familyAllowed;
        private boolean cnicRequired;
    }
}
