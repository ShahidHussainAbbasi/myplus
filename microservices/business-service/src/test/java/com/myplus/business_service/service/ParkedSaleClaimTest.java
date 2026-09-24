package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplus.business_service.entity.ParkedSale;
import com.myplus.business_service.repository.ParkedSaleRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.web.exception.ResourceNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PARK-CLAIM-1 — resuming a parked sale takes it off the shelf, exactly once.
 *
 * <p>The defect this guards: resume read the cart and the till then called /deleteParked separately. That call
 * needs DELETE_PRIVILEGE, a USER-role cashier does not hold it, and the till made it silently — so the parked
 * sale survived every resume and could be completed again as a second invoice.
 *
 * <p>Pure logic — mocked repository, real ObjectMapper — so it runs on every {@code mvn test}. The row lock that
 * makes a concurrent second DELETE count 0 is InnoDB's; what is asserted here is that the count is what decides.
 */
@ExtendWith(MockitoExtension.class)
class ParkedSaleClaimTest {

    private static final long ORG = 15L, USER = 7L, ID = 42L;

    @Mock ParkedSaleRepo repo;
    @Mock RequestUtil requestUtil;
    ParkedSaleService service;

    @BeforeEach
    void setUp() {
        service = new ParkedSaleService(repo, new ObjectMapper(), requestUtil);
    }

    private ParkedSale row(String cartJson) {
        ParkedSale p = new ParkedSale();
        p.setId(ID);
        p.setOrganizationId(ORG);
        p.setUserId(USER);
        p.setCartJson(cartJson);
        return p;
    }

    @Test
    @DisplayName("claim returns the stored cart — trade discount included — and deletes the row")
    void claimReturnsCartAndDeletes() {
        when(repo.findByIdAndOrganizationIdAndUserId(ID, ORG, USER))
                .thenReturn(Optional.of(row("{\"sales\":[{\"productId\":1}],\"tradeDiscount\":2}")));
        when(repo.deleteScoped(ID, ORG, USER)).thenReturn(1);

        JsonNode cart = service.claim(ID, ORG, USER);

        assertThat(cart.get("sales").size()).isEqualTo(1);
        assertThat(cart.get("tradeDiscount").asDouble()).isEqualTo(2.0);
        verify(repo).deleteScoped(ID, ORG, USER);
    }

    @Test
    @DisplayName("a second claim that deletes nothing is NOT_FOUND — the sale cannot be resumed twice")
    void secondClaimLosesTheRace() {
        when(repo.findByIdAndOrganizationIdAndUserId(ID, ORG, USER)).thenReturn(Optional.of(row("{}")));
        when(repo.deleteScoped(ID, ORG, USER)).thenReturn(0);   // the first claim already removed it

        assertThatThrownBy(() -> service.claim(ID, ORG, USER)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("another cashier's (or tenant's) parked id is NOT_FOUND and nothing is deleted")
    void foreignIdIsNotFoundAndUntouched() {
        when(repo.findByIdAndOrganizationIdAndUserId(ID, ORG, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(ID, ORG, 99L)).isInstanceOf(ResourceNotFoundException.class);
        verify(repo, never()).deleteScoped(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("an unreadable cart is refused and the row is KEPT — a basket is never destroyed unread")
    void unreadableCartIsKept() {
        when(repo.findByIdAndOrganizationIdAndUserId(ID, ORG, USER)).thenReturn(Optional.of(row("{not json")));

        assertThatThrownBy(() -> service.claim(ID, ORG, USER)).isInstanceOf(IllegalStateException.class);
        verify(repo, never()).deleteScoped(anyLong(), anyLong(), anyLong());
    }
}
