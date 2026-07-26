package com.myplus.commerce.contracts.dto;

import lombok.*;

/**
 * The role a party plays in ONE module — the row behind the cross-module contact view ("who is this person to us?").
 * Carried as an optional field on a {@link PartyRef} upsert (so bridging costs no extra call) or posted on its own by a
 * backfill. Display data only: {@code label} is a short caption, NEVER a money or clinical field — the owning module
 * keeps its domain data.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyRoleRef {
    private String module;       // business | education | welfare | pharma
    private String role;         // CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT
    private Long localId;        // the module's own primary key for the record
    private String label;        // short display caption (e.g. the name on the local record)

    /**
     * Only used by the BULK backfill payload, where each item names its own party. Ignored on an upsert (the party is
     * the one being upserted) and on the single-link endpoint (the party is in the path).
     */
    private Long partyId;
}
