package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SRA-1 — one row of a business's address list, for the City / Area pickers. A city has no {@code cityId}; an area
 * carries its city's id and name so a picker can be filtered and a row can say "Zahir Pir, Khanpur" without a
 * second request.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AddressOptionDTO {
    private Long id;
    private String name;
    private String status;
    private Long cityId;
    private String cityName;
}
