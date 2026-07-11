package com.myplus.business_service.service.gl;

import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.security.GatewayIdentityForwarding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default GL transport: a synchronous HTTP call to finance-service via {@link FinanceClient}, impersonating the
 * tenant through the gateway with {@link GatewayIdentityForwarding#runAs} (so a relay/scheduled delivery with no
 * inbound request still carries org + user). Active unless {@code gl.publisher} is set to another transport.
 */
@Component
@ConditionalOnProperty(name = "gl.publisher", havingValue = "http", matchIfMissing = true)
public class HttpGlEventPublisher implements GlEventPublisher {

    @Autowired(required = false)
    private FinanceClient financeClient;   // shared GL client; null if finance is unwired in this deployment

    @Override
    public boolean isAvailable() {
        return financeClient != null;
    }

    @Override
    public void publish(PostingEventRequest req, Long userId, Long organizationId) {
        GatewayIdentityForwarding.runAs(userId, organizationId, () -> financeClient.postEvent(req));
    }
}
