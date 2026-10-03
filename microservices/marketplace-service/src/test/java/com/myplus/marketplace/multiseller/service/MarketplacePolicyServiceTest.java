package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
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

import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.repository.MarketplacePolicyRepository;

/** MKT-1c — policies: who writes them, what each needs, one default commission, append-only. */
@ExtendWith(MockitoExtension.class)
class MarketplacePolicyServiceTest {

    @Mock MarketplacePolicyRepository policies;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;                              // G-16: actions are audited
    @InjectMocks MarketplacePolicyService service;

    final Map<Long, MarketplacePolicy> rows = new HashMap<>();
    long seq;

    @BeforeEach
    void wire() {
        lenient().when(policies.save(any())).thenAnswer(i -> {
            MarketplacePolicy p = i.getArgument(0);
            if (p.getId() == null) p.setId(++seq);
            rows.put(p.getId(), p);
            return p;
        });
        lenient().when(policies.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<Long>getArgument(0))));
        lenient().when(policies.findFirstByPolicyTypeAndActiveTrueAndIsDefaultTrue(anyString())).thenAnswer(i -> rows.values()
                .stream().filter(p -> p.getPolicyType().equals(i.getArgument(0)) && p.getActive() && p.getIsDefault()).findFirst());
    }

    static OfferDTOs.PolicyRequest warranty(String provider, Integer months) {
        return new OfferDTOs.PolicyRequest("WARRANTY", "12 months — authorised distributor", null, provider, months,
                "Manufacturing defects", "Physical and water damage", null, null, null, null, null);
    }

    static OfferDTOs.PolicyRequest commission(String rate, boolean isDefault) {
        return new OfferDTOs.PolicyRequest("COMMISSION", "Mobiles " + rate, isDefault, null, null, null, null, null,
                null, "ITEMS", new BigDecimal(rate), null);
    }

    @Test
    @DisplayName("[MKT-R14.1] [MKT-R14.2] a warranty names its provider (never assumed MaxTheService) and starts at delivery")
    void warranty() {
        assertThatThrownBy(() -> service.create(warranty(" ", 12))).hasMessageContaining("never assumed to be it");
        OfferDTOs.Policy w = service.create(warranty("Authorised distributor", 12));
        assertThat(w.warrantyStarts()).isEqualTo("DELIVERY");
        assertThat(w.claimProcess()).isEqualTo("Open a MaxTheService support case");
    }

    @Test
    @DisplayName("[MKT-R7.4] commission terms are validated by the domain rule; only one default at a time")
    void commission() {
        assertThatThrownBy(() -> service.create(commission("1.5", false))).hasMessageContaining("between 0% and 100%");
        OfferDTOs.Policy first = service.create(commission("0.10", true));
        OfferDTOs.Policy second = service.create(commission("0.08", true));
        assertThat(rows.get(first.id()).getIsDefault()).isFalse();
        assertThat(rows.get(second.id()).getIsDefault()).isTrue();
        assertThat(rows.get(first.id()).getCommissionRate()).as("terms unchanged — append-only").isEqualByComparingTo("0.10");
    }

    @Test
    @DisplayName("[MKT-R13.3] a deactivated policy cannot be chosen for a new offer")
    void deactivated() {
        OfferDTOs.Policy r = service.create(new OfferDTOs.PolicyRequest("RETURN", "7 days", null, null, null, null,
                null, null, 7, null, null, null));
        service.deactivate(r.id());
        assertThatThrownBy(() -> service.usable(r.id(), "RETURN")).hasMessageContaining("Choose an active return policy");
        assertThatThrownBy(() -> service.usable(r.id(), "WARRANTY")).hasMessageContaining("warranty");
    }

    @Test
    @DisplayName("[MKT-R22.1] only the operator writes policies")
    void operatorOnly() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.create(warranty("X", 1))).isInstanceOf(AccessDeniedException.class);
        assertThat(rows).isEmpty();
    }
}
