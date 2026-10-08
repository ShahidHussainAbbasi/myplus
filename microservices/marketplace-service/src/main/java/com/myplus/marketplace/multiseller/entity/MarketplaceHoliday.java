package com.myplus.marketplace.multiseller.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** MKT-2f — a bank holiday the settlement calendar skips (V34). Only a future day is added or removed. */
@Entity
@Table(name = "mkt_holiday")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceHoliday {

    @Id
    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
