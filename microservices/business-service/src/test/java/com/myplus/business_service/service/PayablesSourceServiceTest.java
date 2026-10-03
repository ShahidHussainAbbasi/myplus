package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.business_service.entity.PayablesSource;
import com.myplus.business_service.repository.PayablesSourceRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.FinanceClient;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/** FP-4b — who may flip a tenant's payables switch, and when it is refused (rulings 4 and 5). */
class PayablesSourceServiceTest {

    private final PayablesSourceRepo repo = mock(PayablesSourceRepo.class);
    private final VenderRepo venders = mock(VenderRepo.class);
    private final FinanceClient finance = mock(FinanceClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FinanceClient> provider = mock(ObjectProvider.class);
    private final PayablesSourceService svc = new PayablesSourceService(repo, venders, provider, mock(AuditService.class));

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private static void as(String role) {
        AuthenticatedUser u = new AuthenticatedUser(1L, "x@myplus.com",
                List.of(new SimpleGrantedAuthority(role), new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), 1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private void figures(String business, String financePurchase, String glDiff) {
        when(provider.getIfAvailable()).thenReturn(finance);
        when(venders.sumDueByOrg(13L)).thenReturn(new BigDecimal(business));
        when(finance.payablesSummary()).thenReturn(Map.of("bySource", Map.of("PURCHASE", Map.of("netOwed", financePurchase))));
        when(finance.payablesReconciliation()).thenReturn(Map.of("subledgerOpen", "100", "glAccountsPayable", "90", "difference", glDiff));
        when(repo.findById(13L)).thenReturn(Optional.empty());
    }

    @Test @DisplayName("an owner (or anyone not the platform operator) can neither read nor flip the switch")
    void ownerRefused() {
        as("ROLE_OWNER");
        assertThatThrownBy(() -> svc.status(13L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.switchTo(13L, "FINANCE", "go")).isInstanceOf(AccessDeniedException.class);
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("⭐ business and finance disagree → switching to FINANCE is refused, with the difference")
    void differenceBlocks() {
        as("ROLE_ADMIN");
        figures("11130", "11030", "0");
        assertThatThrownBy(() -> svc.switchTo(13L, "FINANCE", "go live")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("disagree by 100");
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("they agree → switched, with the evidence kept; a GL difference WARNS but does not block")
    void agreeSwitches() {
        as("ROLE_ADMIN");
        figures("9700", "9700", "-18000");
        Map<String, Object> out = svc.switchTo(13L, "finance", "parity reached");
        assertThat(out.get("source")).isEqualTo("FINANCE");
        assertThat((BigDecimal) out.get("glDifference")).isEqualByComparingTo("-18000");
        verify(repo).save(org.mockito.ArgumentMatchers.argThat(r -> "FINANCE".equals(r.getSource())
                && r.getBusinessDue().compareTo(new BigDecimal("9700")) == 0 && "parity reached".equals(r.getReason())));
    }

    @Test @DisplayName("back to BUSINESS is always allowed (the rollback), but never without a reason")
    void rollbackAlwaysAllowed() {
        as("ROLE_ADMIN");
        figures("11130", "11030", "0");
        assertThatThrownBy(() -> svc.switchTo(13L, "BUSINESS", " ")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Say why");
        assertThat(svc.switchTo(13L, "BUSINESS", "rollback").get("source")).isEqualTo("BUSINESS");
    }

    @Test @DisplayName("no row means BUSINESS; reading the source is one key lookup")
    void defaultBusiness() {
        when(repo.findById(5L)).thenReturn(Optional.empty());
        PayablesSource f = new PayablesSource();
        f.setSource("FINANCE");
        when(repo.findById(13L)).thenReturn(Optional.of(f));
        assertThat(svc.readsFromFinance(5L)).isFalse();
        assertThat(svc.readsFromFinance(13L)).isTrue();
        assertThat(svc.readsFromFinance(null)).isFalse();
    }
}
