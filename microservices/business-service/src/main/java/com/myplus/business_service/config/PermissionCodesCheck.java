package com.myplus.business_service.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.myplus.business_service.controller.BusinessDashboardController;
import com.myplus.common.security.PermissionCatalogCheck;

/**
 * RW-0c / RW-4a — at start-up, check that every permission code this service names exists in auth-service's
 * catalogue ({@link PermissionCatalogCheck}). Logs only; never stops the service.
 */
@Component
public class PermissionCodesCheck {

    private final RestClient.Builder loadBalanced;

    public PermissionCodesCheck(@LoadBalanced RestClient.Builder loadBalanced) { this.loadBalanced = loadBalanced; }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        PermissionCatalogCheck.verify(loadBalanced, "business-service", BusinessDashboardController.CODES_NAMED);
    }
}
