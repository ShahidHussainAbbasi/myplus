package com.myplus.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.audit.entity.AuditEvent;
import com.myplus.audit.repository.AuditEventRepository;
import com.myplus.common.security.AuthenticatedUser;

/**
 * E5b — the operator console's Activity panel, split (owner ruling 2026-09-27).
 *
 * <p>Found live: {@code /platform/activity?organizationId=49} answered with org 8's trail — the operator's OWN — under
 * business 49's name, because the org rule fell back to the caller's org when no support session was open. Now an
 * operator without a session sees that business's PLATFORM actions (the platform's own record of what it did to
 * them), never its staff activity and never another org's rows.
 */
class AuditActivityScopeTest {

    private static final long OPERATOR_ORG = 8L;
    private static final long SUBJECT = 49L;

    private AuditEventRepository repo;
    private AuditIngestService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        service = new AuditIngestService(repo);
        when(repo.findByOrg(any(), any(Pageable.class))).thenReturn(List.of(new AuditEvent()));
        when(repo.findByOrgAndActorType(any(), any(), any(Pageable.class))).thenReturn(List.of(new AuditEvent()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static void as(AuthenticatedUser u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private static AuthenticatedUser operator() {
        return new AuthenticatedUser(1L, "admin@myplus.com", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), OPERATOR_ORG);
    }

    @Test
    void an_operator_WITHOUT_a_session_gets_that_business_s_PLATFORM_actions_only__never_their_own_trail() {
        as(operator());
        service.list(null, 50, SUBJECT);
        verify(repo).findByOrgAndActorType(eq(SUBJECT), eq("PLATFORM_OPERATOR"), any(Pageable.class));
        verify(repo, never()).findByOrg(eq(OPERATOR_ORG), any(Pageable.class));
        verify(repo, never()).findByOrg(eq(SUBJECT), any(Pageable.class));
    }

    @Test
    void the_action_filter_keeps_the_same_platform_only_scope() {
        as(operator());
        service.list("PLAN_CHANGE", 50, SUBJECT);
        verify(repo).findByOrgActionAndActorType(eq(SUBJECT), eq("PLAN_CHANGE"), eq("PLATFORM_OPERATOR"), any(Pageable.class));
    }

    @Test
    void with_an_open_session_the_operator_sees_the_whole_trail_of_THAT_business() {
        AuthenticatedUser op = operator();
        op.setSupportOrgId(SUBJECT);
        op.setSupportUntil(LocalDateTime.now().plusMinutes(10));
        as(op);
        service.list(null, 50, SUBJECT);
        verify(repo).findByOrg(eq(SUBJECT), any(Pageable.class));
    }

    @Test
    void an_operator_reading_their_OWN_trail_sees_all_of_it() {
        as(operator());
        service.list(null, 50, OPERATOR_ORG);
        verify(repo).findByOrg(eq(OPERATOR_ORG), any(Pageable.class));
    }

    @Test
    void a_tenant_naming_another_business_still_reads_only_their_own__anti_IDOR_unchanged() {
        as(new AuthenticatedUser(7L, "owner@shop.com", List.of(new SimpleGrantedAuthority("ROLE_OWNER")), 13L));
        service.list(null, 50, SUBJECT);
        verify(repo).findByOrg(eq(13L), any(Pageable.class));
        verify(repo, never()).findByOrgAndActorType(any(), any(), any(Pageable.class));
    }
}
