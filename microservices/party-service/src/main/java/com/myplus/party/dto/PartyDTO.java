package com.myplus.party.dto;

import lombok.*;

/** Party CRUD + upsert payload/response. Common identity only. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyDTO {
    private Long id;                 // partyId (null on create / upsert-request)
    private String partyType;        // CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT | OTHER
    private String name;
    private String contact;
    private String email;
    private String address;
    private String notes;
    private Boolean active;
}
