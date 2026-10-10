package com.myplus.clinical.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HMS S3b-1 — pharma-service, as the doctor's Submit sees it: one call that records the prescription. The doctor's
 * identity is forwarded, so pharma-service scopes it to the same organisation and checks its own gates (write
 * privilege, prescriptions switched on). {@code externalRef = "enc-<id>"} makes a retried Submit the SAME prescription.
 * Local to clinical-service — no other service needs this view.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface PharmaRxClient {

    @PostExchange("/prescriptions")
    Envelope create(@RequestBody Rx rx);

    /** pharma-service's ApiResponse, with the one field of the answer the clinic keeps. */
    @Data @NoArgsConstructor @AllArgsConstructor
    class Envelope {
        private boolean success;
        private String message;
        private Rx data;
    }

    /** pharma-service's PrescriptionDTO, the fields a doctor's Submit fills. */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    class Rx {
        private Long id;
        private String patientName;
        private String patientPhone;
        private String doctorName;
        private String diagnosis;
        private String notes;
        private Long partyId;
        private String tokenLabel;
        private Long encounterId;
        private String externalRef;
        private String source;
        @Builder.Default
        private List<Item> items = new ArrayList<>();
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    class Item {
        private Long productId;
        private String medicineName;
        private int quantity;
        private String dosage;
        private String frequency;
        private String duration;
    }
}
