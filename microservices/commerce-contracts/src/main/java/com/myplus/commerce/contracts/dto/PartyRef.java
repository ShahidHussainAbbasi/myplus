package com.myplus.commerce.contracts.dto;

import lombok.*;

/**
 * A party/contact reference — the shared identity a module bridges to via {@code id} (the partyId). Used as both the
 * upsert REQUEST (id null; carries the identity to find-or-create) and the RESPONSE (id populated). Common identity
 * only — no domain data.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyRef {
    private Long id;             // partyId (null on an upsert request)
    private String partyType;    // CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT | OTHER
    private String name;
    private String contact;
    private String email;
    private String address;
}
