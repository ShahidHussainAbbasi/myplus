package com.myplus.catalog.dto;

import lombok.*;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CategoryDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    /** PR-2b — read-only here; set through PUT /categories/{id}/markup (owner/admin). */
    private java.math.BigDecimal markupPct;
    private List<CategoryDTO> children;
}
