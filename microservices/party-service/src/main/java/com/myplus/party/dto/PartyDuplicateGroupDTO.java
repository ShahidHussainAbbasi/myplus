package com.myplus.party.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DR-1 — partners in one org that share a phone key or a tax key: probably one business entered twice. Shown to the
 * owner, never merged automatically ({@code keyType}: PHONE | TAX; {@code matchKey} is the normalised key).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyDuplicateGroupDTO {
    private String keyType;
    private String matchKey;
    private List<PartyContactViewDTO> parties;
}
