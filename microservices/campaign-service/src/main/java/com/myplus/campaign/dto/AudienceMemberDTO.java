package com.myplus.campaign.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudienceMemberDTO {
    private Long id;
    private Long audienceId;
    private Long userId;
    private String email;
    private String phone;
    private String firstName;
    private String lastName;
    private boolean isActive;
    private LocalDateTime subscribedAt;
    private LocalDateTime unsubscribedAt;
}
