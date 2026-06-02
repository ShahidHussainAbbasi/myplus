package com.myplus.inventory.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WarehouseDTO {
    private Long id;
    private String name;
    private String location;
    private String address;
    private Float capacity;
    private Long managerId;
}
