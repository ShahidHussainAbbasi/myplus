package com.myplus.business_service.dto;

import lombok.*;

/** Store CRUD payload (never expose the entity to controllers). Mapped to/from {@code Store} by ModelMapper. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoreDTO {
    private Long id;
    private String name;
    private String code;
    private String address;
    private String phone;
    private String status;
    /** P5b: true for the caller's ACTIVE store (JWT activeLocationId). Set by /getMyStores only — the switcher
     *  needs to show which store it is currently on, and only the server knows that. Not persisted. */
    private Boolean active;
}
