package com.myplus.party.dto;

import lombok.*;

/** A role a party plays in one module. Mirrors the contracts' {@code PartyRoleRef}; display data only. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyRoleDTO {
    private String module;       // business | education | welfare | pharma
    private String role;         // CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT
    private Long localId;        // the module's own primary key
    private String label;        // short display caption

    /** Only used in the BULK backfill payload, where each item names its own party. Ignored elsewhere. */
    private Long partyId;
}
