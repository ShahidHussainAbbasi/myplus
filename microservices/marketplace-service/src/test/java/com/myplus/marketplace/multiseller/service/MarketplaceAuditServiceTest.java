package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import com.myplus.commerce.contracts.client.AuditClient;
import com.myplus.common.outbox.OutboxRelay;
import com.myplus.marketplace.entity.AuditOutbox;
import com.myplus.marketplace.repository.AuditOutboxRepository;

/** G-16 — who an audit row says acted, and that every row can be delivered. */
@ExtendWith(MockitoExtension.class)
class MarketplaceAuditServiceTest {

    @Mock AuditOutboxRepository repo;
    @Mock OutboxRelay relay;
    @Mock ApplicationEventPublisher events;
    @Mock ObjectProvider<AuditClient> client;
    MarketplaceAuditService audit;
    final List<AuditOutbox> rows = new ArrayList<>();

    @BeforeEach
    void wire() {
        audit = new MarketplaceAuditService(repo, relay, events, client);
        when(repo.save(any())).thenAnswer(i -> { AuditOutbox o = i.getArgument(0); o.setId((long) rows.size() + 1); rows.add(o); return o; });
    }

    @Test
    @DisplayName("[MKT-R22.4] a customer's action is SYSTEM with a user id (0, never a person) — a null user was refused by audit-service")
    void customerDeliverable() {
        audit.event(MarketplaceAuditService.CASE_OPENED, MarketplaceAuditService.ENTITY_CASE, "SC-000001", 7L,
                MarketplaceAuditService.Actor.CUSTOMER, null, "RETURN", null, "MKT-000001");
        AuditOutbox r = rows.get(0);
        assertThat(r.getUserId()).isEqualTo(MarketplaceAuditService.NO_STAFF_USER);
        assertThat(r.getActorType()).isEqualTo("SYSTEM");
        assertThat(r.getOrganizationId()).isEqualTo(7L);                    // filed under the seller
        assertThat(r.getDetails()).startsWith("by the customer");
    }

    @Test
    @DisplayName("[MKT-R22.4] an operator's action is PLATFORM_OPERATOR, never derived — and keeps the operator's own user")
    void operatorStated() {
        audit.event(MarketplaceAuditService.RETURN_DECIDED, MarketplaceAuditService.ENTITY_RETURN, "RT-000001", 7L,
                MarketplaceAuditService.Actor.OPERATOR, "REQUESTED", "APPROVED", null, null);
        assertThat(rows.get(0).getActorType()).isEqualTo("PLATFORM_OPERATOR");
        assertThat(rows.get(0).getUserId()).isNotEqualTo(MarketplaceAuditService.NO_STAFF_USER);
    }
}
