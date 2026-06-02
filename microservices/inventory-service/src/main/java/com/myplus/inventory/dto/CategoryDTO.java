package com.myplus.inventory.dto;

import lombok.*;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CategoryDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private List<CategoryDTO> children;
}
