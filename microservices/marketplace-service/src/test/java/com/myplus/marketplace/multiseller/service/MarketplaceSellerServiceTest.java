package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceAgreementAcceptance;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceAgreementAcceptanceRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

/**
 * MKT-0a — seller onboarding rules. The repositories are an in-memory map so the cases read like the business:
 * accept → PENDING_APPROVAL → the operator decides → the guard answers.
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceSellerServiceTest {

    static final long ORG = 7L;

    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceAgreementAcceptanceRepository acceptances;
    @Mock SellerAccess access;
    @InjectMocks MarketplaceSellerService service;

    final Map<Long, MarketplaceSellerAccount> accountRows = new HashMap<>();
    final Map<String, MarketplaceAgreementAcceptance> acceptRows = new HashMap<>();

    @BeforeEach
    void wire() {
        lenient().when(access.org()).thenReturn(ORG);
        lenient().when(access.userId()).thenReturn(11L);
        lenient().when(access.capabilityOn()).thenReturn(true);
        lenient().when(accounts.findByOrganizationId(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(accountRows.get(i.<Long>getArgument(0))));
        lenient().when(accounts.save(any())).thenAnswer(i -> {
            MarketplaceSellerAccount a = i.getArgument(0);
            a.setVersion(a.getVersion() == null ? 0 : a.getVersion() + 1);
            accountRows.put(a.getOrganizationId(), a);
            return a;
        });
        lenient().when(acceptances.findByOrganizationIdAndAgreementCodeAndAgreementVersion(anyLong(), anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(acceptRows.get(key(i.getArgument(0), i.getArgument(1), i.getArgument(2)))));
        lenient().when(acceptances.saveAndFlush(any())).thenAnswer(i -> {
            MarketplaceAgreementAcceptance a = i.getArgument(0);
            acceptRows.put(key(a.getOrganizationId(), a.getAgreementCode(), a.getAgreementVersion()), a);
            return a;
        });
    }

    static String key(Object org, Object code, Object ver) {
        return org + "|" + code + "|" + ver;
    }

    @Test
    @DisplayName("[MKT-R9.1] [MKT-R20.1] accepting v1 records BOTH agreements, who and when, and applies PENDING_APPROVAL")
    void acceptApplies() {
        SellerDTOs.Acceptance a = service.accept(new SellerDTOs.AcceptRequest("v1", "Shahzad Mobile Shop"));
        assertThat(a.version()).isEqualTo("v1");
        assertThat(a.acceptedBy()).isEqualTo(11L);
        assertThat(a.acceptedAt()).isNotNull();
        assertThat(acceptRows).containsKeys(key(ORG, "SELLER", "v1"), key(ORG, "DATA_SHARING", "v1"));
        assertThat(a.account().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(a.account().displayName()).isEqualTo("Shahzad Mobile Shop");
    }

    @Test
    @DisplayName("[MKT-R22.3] accepting twice is a no-op: same acceptance, no second account, no new rows")
    void idempotent() {
        SellerDTOs.Acceptance first = service.accept(new SellerDTOs.AcceptRequest("v1", "Shop"));
        SellerDTOs.Acceptance second = service.accept(new SellerDTOs.AcceptRequest("v1", "Renamed"));
        assertThat(second.acceptedAt()).isEqualTo(first.acceptedAt());
        assertThat(second.account().displayName()).as("a pending account is not renamed by a re-click").isEqualTo("Shop");
        verify(acceptances, times(2)).saveAndFlush(any());
        verify(accounts, times(1)).save(any());
    }

    @Test
    @DisplayName("[MKT-R9.1] a stale version is refused with a sentence telling the owner to reload")
    void staleVersion() {
        assertThatThrownBy(() -> service.accept(new SellerDTOs.AcceptRequest("v0", "Shop")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Reload the page and read version v1");
        verify(acceptances, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[MKT-R20.0] the capability and the tier are checked BEFORE anything is written")
    void guardsFirst() {
        doThrow(new ValidationException("off")).when(access).assertCapabilityOn();
        assertThatThrownBy(() -> service.accept(new SellerDTOs.AcceptRequest("v1", "Shop"))).hasMessage("off");
        verify(acceptances, never()).saveAndFlush(any());
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("[MKT-R20.1] a first application needs the name customers will see")
    void needsDisplayName() {
        assertThatThrownBy(() -> service.accept(new SellerDTOs.AcceptRequest("v1", " ")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("name customers will see");
    }

    @Test
    @DisplayName("[MKT-R20.1] nobody sells until the operator APPROVES — each refusal names what is missing")
    void guardNamesTheGap() {
        when(access.capabilityOn()).thenReturn(true);
        assertThatThrownBy(service::assertActiveSeller).hasMessageContaining("Accept the marketplace seller and data-sharing agreements");
        service.accept(new SellerDTOs.AcceptRequest("v1", "Shop"));
        assertThatThrownBy(service::assertActiveSeller).hasMessage("MaxTheService is still reviewing your seller account.");
        when(access.capabilityOn()).thenReturn(true);
        service.decide(ORG, new SellerDTOs.DecisionRequest("APPROVE", null, null));
        assertThatCode(service::assertActiveSeller).doesNotThrowAnyException();
        assertThat(service.view().canSell()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R20.1] reject → re-apply → approve → suspend (with reason) → reinstate")
    void lifecycle() {
        service.accept(new SellerDTOs.AcceptRequest("v1", "Shop"));
        assertThatThrownBy(() -> service.decide(ORG, new SellerDTOs.DecisionRequest("REJECT", " ", null)))
                .hasMessageContaining("Give the seller a reason");
        assertThat(service.decide(ORG, new SellerDTOs.DecisionRequest("REJECT", "NTN missing", null)).statusReason())
                .isEqualTo("NTN missing");
        assertThatThrownBy(service::assertActiveSeller).hasMessage("Your seller account is rejected: NTN missing");
        // re-accepting re-applies
        assertThat(service.accept(new SellerDTOs.AcceptRequest("v1", "Shop Ltd")).account().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(accountRows.get(ORG).getStatusReason()).isNull();
        service.decide(ORG, new SellerDTOs.DecisionRequest("APPROVE", null, null));
        service.decide(ORG, new SellerDTOs.DecisionRequest("SUSPEND", "documents expired", null));
        assertThat(service.view().canSell()).isFalse();
        // a suspended seller cannot lift its own suspension by re-accepting
        service.accept(new SellerDTOs.AcceptRequest("v1", "x"));
        assertThat(accountRows.get(ORG).getStatus()).isEqualTo("SUSPENDED");
        assertThat(service.decide(ORG, new SellerDTOs.DecisionRequest("REINSTATE", null, null)).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("[MKT-R19.1] an illegal move is refused in words (approve a pending → suspend is fine; reject an approved is not)")
    void illegalMove() {
        service.accept(new SellerDTOs.AcceptRequest("v1", "Shop"));
        service.decide(ORG, new SellerDTOs.DecisionRequest("APPROVE", null, null));
        assertThatThrownBy(() -> service.decide(ORG, new SellerDTOs.DecisionRequest("REJECT", "late", null)))
                .isInstanceOf(ValidationException.class).hasMessage("A seller account that is approved cannot be moved to rejected.");
    }

    @Test
    @DisplayName("[MKT-R22.1] the operator decides; a tenant cannot, and an unknown business is a 404")
    void operatorOnly() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.decide(ORG, new SellerDTOs.DecisionRequest("APPROVE", null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(null, 0, 10)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("[MKT-R22.1] deciding a business with no seller account is not found")
    void unknownSeller() {
        assertThatThrownBy(() -> service.decide(99L, new SellerDTOs.DecisionRequest("APPROVE", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("[MKT-R22.3] a decision against an outdated version is a conflict, not a silent overwrite")
    void staleDecision() {
        service.accept(new SellerDTOs.AcceptRequest("v1", "Shop"));
        assertThatThrownBy(() -> service.decide(ORG, new SellerDTOs.DecisionRequest("APPROVE", null, 99)))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(accountRows.get(ORG).getStatus()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("[MKT-R20.0] the view reports each condition separately; canSell needs all three")
    void viewParts() {
        when(access.capabilityOn()).thenReturn(false);
        SellerDTOs.SellerView v = service.view();
        assertThat(v.capabilityOn()).isFalse();
        assertThat(v.agreementsCurrent()).isFalse();
        assertThat(v.account()).isNull();
        assertThat(v.canSell()).isFalse();
        assertThat(v.requiredVersion()).isEqualTo("v1");
        verify(accounts).findByOrganizationId(eq(ORG));
    }
}
