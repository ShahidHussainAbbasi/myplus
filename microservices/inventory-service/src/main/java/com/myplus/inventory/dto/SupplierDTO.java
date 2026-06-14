package com.myplus.inventory.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplierDTO {
    private Long id;
    private String name;
    private String contactPerson;
    private String email;
    private String phone;
    private String address;
    private String taxId;
    private String paymentTerms;
    private Boolean isActive;
}
