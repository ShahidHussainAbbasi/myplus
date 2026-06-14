package com.myplus.education.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OwnerDTO {
    private Long id;
    private Long userId;
    private String name;
    private String email;
    private String mobile;
    private String address;
    private String status;
    private String datedStr;
    private String updatedStr;
}
