package com.myplus.education.dto;

import java.util.Set;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flat School DTO consumed by the monolith education pages (branch list, owners, dates as strings).
 * Separate from the REST {@code EducationDTOs.SchoolDTO}; this is the legacy/flat shape.
 */
@Data
@NoArgsConstructor
public class SchoolDTO {
    private Long id;
    private Long userId;
    private String name;
    private String branchName;
    private String email;
    private String phone;
    private String address;
    private String status;
    private Set<Long> ownerIds;
    private Set<String> ownerNames;
    private String datedStr;
    private String updatedStr;
}
