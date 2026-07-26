package com.myplus.party.dto;

import java.util.List;

import lombok.*;

/**
 * The cross-module contact view: one shared identity + every module role it plays ("Firdos — POS customer, pharmacy
 * patient, welfare donor"). Assembled from the local role index in a single query — deliberately NOT a fan-out to the
 * owning modules, so this read has no cross-service latency or partial-failure modes.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PartyContactViewDTO {
    private PartyDTO party;
    private List<PartyRoleDTO> roles;
}
