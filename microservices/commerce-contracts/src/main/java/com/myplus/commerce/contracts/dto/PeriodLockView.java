package com.myplus.commerce.contracts.dto;

import lombok.*;

/** The org's period-close state as read from finance-service: {@code lockedThrough} (ISO date) or null when open. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PeriodLockView {
    private String lockedThrough;
}
