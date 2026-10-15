package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HMS S1 — "the pharmacy customer for this person": find the business customer already linked to {@code partyId},
 * or create one. Sent by clinical-service when reception registers a patient, so the patient IS the customer at the
 * till (one person, two roles — never a second record typed at the counter).
 *
 * <p>Request: {@code partyId}, {@code name}, {@code contact}, {@code cnic}. Response adds {@code customerId} and
 * {@code created} (false when an existing customer was found).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyCustomerRef {
    private Long partyId;
    private String name;
    private String contact;
    private String cnic;
    private Long customerId;
    private Boolean created;
}
