package com.myplus.business_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feature flag for the strangler cutover (slice 33, D2). While {@code false} (default), sells use the
 * existing local-{@code Stock} path; flipping it on routes sells through the inventory reservation saga
 * (Phase 6c). Set via {@code trade.saga.enabled=true} once the saga path is verified, then local Stock is
 * removed at cutover (6d).
 */
@Component
@ConfigurationProperties(prefix = "trade.saga")
@Getter
@Setter
public class TradeSagaProperties {

    /** Route sells through the inventory reservation saga instead of local Stock. Default off (strangler). */
    private boolean enabled = false;
}
