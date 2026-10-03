package com.myplus.business_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.business_service.repository.VenderRepo;
import com.myplus.common.security.AuthenticatedUser;

/** FP-4c — finance's stamp lands only on the CALLER's tenant, suppliers only, never negative. */
class InternalPayableBalanceControllerTest {

    private final VenderRepo repo = mock(VenderRepo.class);
    private final InternalPayableBalanceController ctl = new InternalPayableBalanceController(repo);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static void asOrg(Long org) {
        AuthenticatedUser u = new AuthenticatedUser(9L, "svc", List.of(new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), org);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test @DisplayName("no tenant on the request → refused, nothing stamped")
    void failClosed() {
        assertThatThrownBy(() -> ctl.stamp(List.of(new InternalPayableBalanceController.Balance("VENDOR", 7L, BigDecimal.ONE, BigDecimal.ZERO, 5L))))
                .isInstanceOf(IllegalStateException.class);
        verify(repo, never()).stampPayable(anyLong(), any(), any(), any(), anyLong());
    }

    @Test @DisplayName("stamped within the caller's org; a customer row is skipped; negatives floored at 0")
    void stamps() {
        asOrg(13L);
        when(repo.stampPayable(eq(7L), eq(13L), any(), any(), eq(5L))).thenReturn(1);
        var out = ctl.stamp(List.of(
                new InternalPayableBalanceController.Balance("VENDOR", 7L, new BigDecimal("-3"), new BigDecimal("40"), 5L),
                new InternalPayableBalanceController.Balance("CUSTOMER", 8L, BigDecimal.TEN, BigDecimal.ZERO, 5L)));
        assertThat(out.get("stamped")).isEqualTo(1);
        verify(repo).stampPayable(eq(7L), eq(13L), org.mockito.ArgumentMatchers.argThat(b -> b.signum() == 0),
                org.mockito.ArgumentMatchers.argThat(b -> b.compareTo(new BigDecimal("40")) == 0), eq(5L));
        verify(repo, never()).stampPayable(eq(8L), any(), any(), any(), anyLong());
    }
}
