package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import com.myplus.marketplace.multiseller.domain.ReturnCostPolicy.Party;
import com.myplus.marketplace.multiseller.domain.ReturnCostPolicy.PartySnapshot;
import com.myplus.marketplace.multiseller.domain.ReturnCostPolicy.Reason;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReturnAndSubstitutionPolicyTest {

    /** seller 1, owner 2, custodian 3, fulfiller 4, carrier 5, platform 9 — all different on purpose (§3) */
    private static final PartySnapshot P = new PartySnapshot(1L, 2L, 3L, 4L, 5L, 9L);

    @Test
    @DisplayName("[MKT-R13.1] every row of the source's cost-bearer table")
    void table() {
        assertThat(ReturnCostPolicy.bearerFor(Reason.WRONG_PRODUCT)).isEqualTo(Party.FULFILLER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.DAMAGED_BEFORE_HANDOVER)).isEqualTo(Party.CUSTODIAN);
        assertThat(ReturnCostPolicy.bearerFor(Reason.DEFECTIVE)).isEqualTo(Party.STOCK_OWNER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.NOT_AS_DESCRIBED)).isEqualTo(Party.SELLER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.EXPIRED_OR_UNSAFE)).isEqualTo(Party.STOCK_OWNER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.CHANGE_OF_MIND)).isEqualTo(Party.CUSTOMER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.DELIVERY_FAILURE)).isEqualTo(Party.CARRIER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.ROUTING_ERROR)).isEqualTo(Party.PLATFORM);
        assertThat(ReturnCostPolicy.bearerFor(Reason.DROPSHIP_FAILURE)).isEqualTo(Party.SUPPLIER);
        assertThat(ReturnCostPolicy.bearerFor(Reason.COD_REFUSAL)).isEqualTo(Party.BY_POLICY);
        for (Reason r : Reason.values()) assertThat(ReturnCostPolicy.bearerFor(r)).as(r.name()).isNotNull();
    }

    @Test
    @DisplayName("[MKT-R3.2] [MKT-R13.3] the bearer resolves through the order-time snapshot, never 'the seller' by default")
    void resolvesThroughSnapshot() {
        assertThat(ReturnCostPolicy.bearerOrganization(Reason.WRONG_PRODUCT, P)).isEqualTo(4L);
        assertThat(ReturnCostPolicy.bearerOrganization(Reason.DAMAGED_BEFORE_HANDOVER, P)).isEqualTo(3L);
        assertThat(ReturnCostPolicy.bearerOrganization(Reason.DEFECTIVE, P)).isEqualTo(2L);
        assertThat(ReturnCostPolicy.bearerOrganization(Reason.NOT_AS_DESCRIBED, P)).isEqualTo(1L);
        assertThat(ReturnCostPolicy.bearerOrganization(Reason.ROUTING_ERROR, P)).isEqualTo(9L);
        assertThat(ReturnCostPolicy.bearerOrganization(Reason.CHANGE_OF_MIND, P)).isNull();
    }

    @Test
    @DisplayName("[MKT-R13.4] expired or unsafe goods are escalated at once; nothing else is")
    void escalation() {
        for (Reason r : Reason.values())
            assertThat(ReturnCostPolicy.requiresUrgentEscalation(r)).as(r.name()).isEqualTo(r == Reason.EXPIRED_OR_UNSAFE);
    }

    @Test
    @DisplayName("[MKT-R11.3] variant, colour, size, strength, pack size or brand changes always need the customer")
    void protectedAttributes() {
        Map<String, String> a32 = Map.of("brand", "Samsung", "storage", "128GB", "colour", "Black");
        BigDecimal p = new BigDecimal("52000");
        assertThat(SubstitutionPolicy.requiresCustomerApproval(a32, Map.of("brand", "Samsung", "storage", "64GB",
                "colour", "Black"), p, p, 4, 4)).as("storage").isTrue();
        assertThat(SubstitutionPolicy.requiresCustomerApproval(a32, Map.of("brand", "Samsung", "storage", "128GB",
                "colour", "Blue"), p, p, 4, 4)).as("colour").isTrue();
        assertThat(SubstitutionPolicy.requiresCustomerApproval(Map.of("strength", "500mg", "pack_size", "10"),
                Map.of("strength", "500mg", "pack_size", "20"), p, p, 4, 4)).as("pack size").isTrue();
    }

    @Test
    @DisplayName("[MKT-R11.1] same product, same or lower price, same or earlier promise: reassign without asking")
    void silentReassignment() {
        Map<String, String> a32 = Map.of("brand", "Samsung", "storage", "128 GB", "colour", "black");
        Map<String, String> same = Map.of("Brand", "SAMSUNG", "storage", "128GB", "colour", "Black");
        assertThat(SubstitutionPolicy.requiresCustomerApproval(a32, same, new BigDecimal("52000"),
                new BigDecimal("51500"), 24, 4)).isFalse();
        assertThat(SubstitutionPolicy.requiresCustomerApproval(a32, same, new BigDecimal("51500"),
                new BigDecimal("52000"), 24, 24)).as("price went up").isTrue();
        assertThat(SubstitutionPolicy.requiresCustomerApproval(a32, same, new BigDecimal("52000"),
                new BigDecimal("52000"), 4, 24)).as("arrives later").isTrue();
    }
}
