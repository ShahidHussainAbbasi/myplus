package com.myplus.campaign.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    private String recipientEmail;
    private String recipientPhone;

    @Enumerated(EnumType.STRING)
    private Status status;

    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime openedAt;
    private LocalDateTime clickedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public enum Status { SENT, DELIVERED, OPENED, CLICKED, BOUNCED, UNSUBSCRIBED }
}
