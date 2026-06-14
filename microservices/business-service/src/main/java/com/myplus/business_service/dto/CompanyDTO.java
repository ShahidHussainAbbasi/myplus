package com.myplus.business_service.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CompanyDTO {
    private Long id;
    @jakarta.validation.constraints.NotBlank(message = "name is required")
    private String name;
    private String phone;
    private String email;
    private String address;
    private Long userId;
    private LocalDateTime createdAt;
    private String datedStr;
    private String updatedStr;
}
