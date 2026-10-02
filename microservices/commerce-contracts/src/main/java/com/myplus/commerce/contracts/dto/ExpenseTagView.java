package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EX-2b — one thing an expense can be tagged to (a school, a vehicle, a land), as the OWNING module names it.
 * {@code type} is the tag kind (SCHOOL, VEHICLE, LAND); {@code label} is what a person reads.
 */
@Data @NoArgsConstructor @AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class ExpenseTagView {
    private String type;
    private Long id;
    private String label;
}
