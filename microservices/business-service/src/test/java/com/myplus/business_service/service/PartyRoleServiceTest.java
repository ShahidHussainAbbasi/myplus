package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.VenderDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;

/**
 * DR-2 — badges, link and unlink, without a database. What a defect here would break: a badge on the wrong row,
 * a link that moved the local stamp without telling party-service (or the reverse), an unlink that gave a record
 * with nothing to leave a partner of its own.
 */
class PartyRoleServiceTest {

    private CustomerRepo customers;
    private VenderRepo venders;
    private PartyClient party;
    private AuditService audit;
    private PartySetOffService setOffs;
    private com.myplus.business_service.repository.PartySetOffRepo setOffRepo;
    private PartyRoleService service;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerRepo.class);
        venders = mock(VenderRepo.class);
        party = mock(PartyClient.class);
        audit = mock(AuditService.class);
        service = new PartyRoleService(mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "customerRepo", customers);
        ReflectionTestUtils.setField(service, "venderRepo", venders);
        ReflectionTestUtils.setField(service, "partyClient", party);
        ReflectionTestUtils.setField(service, "auditService", audit);
        setOffs = mock(PartySetOffService.class);
        // The real methods never return null (they reduce from ZERO); a bare mock returns null for a BigDecimal, which
        // made every test that reaches position() without stubbing these fail with an NPE in the SUM, not in the code
        // under test. Default them to the real contract; a test that cares stubs a specific id, which wins.
        when(setOffs.settleableReceivable(any())).thenReturn(java.math.BigDecimal.ZERO);
        when(setOffs.settleablePayable(any())).thenReturn(java.math.BigDecimal.ZERO);
        setOffRepo = mock(com.myplus.business_service.repository.PartySetOffRepo.class);
        ReflectionTestUtils.setField(service, "setOffs", setOffs);
        ReflectionTestUtils.setField(service, "setOffRepo", setOffRepo);
    }

    private static Customer customer(long id, Long partyId) {
        Customer c = new Customer();
        c.setCustomerId(id); c.setPartyId(partyId); c.setName("C" + id); c.setContact("03001234567");
        return c;
    }

    private static Vender vender(long id, Long partyId) {
        Vender v = new Vender();
        v.setId(id); v.setPartyId(partyId); v.setName("V" + id); v.setMobile("03007654321");
        return v;
    }

    @Test
    @DisplayName("badges: only customers whose partner holds a supplier record are marked; no partner = no badge")
    void badges() {
        CustomerDTO a = new CustomerDTO(); a.setPartyId(10L);
        CustomerDTO b = new CustomerDTO(); b.setPartyId(11L);
        CustomerDTO none = new CustomerDTO();
        when(venders.partyIdsAmong(any(), any(), any())).thenReturn(List.of(10L));
        service.markCustomers(List.of(a, b, none));
        assertThat(a.getAlsoSupplier()).isTrue();
        assertThat(b.getAlsoSupplier()).isFalse();
        assertThat(none.getAlsoSupplier()).isFalse();

        VenderDTO v = new VenderDTO(); v.setPartyId(11L);
        when(customers.partyIdsAmong(any(), any(), any())).thenReturn(List.of(11L));
        service.markVenders(List.of(v));
        assertThat(v.getAlsoCustomer()).isTrue();
    }

    @Test
    @DisplayName("link: the supplier's role moves to the customer's partner in party-service, THEN the local stamp + audit")
    void link_moves_the_supplier_to_the_customers_partner() {
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(customer(1, 100L)));
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(vender(2, 200L)));

        assertThat(service.link(1L, 2L)).isTrue();
        verify(party).link(eq(100L), any(PartyRoleRef.class));
        verify(venders).updatePartyId(2L, 100L);
        verify(audit).record(eq("PARTY_LINK"), eq("VENDOR"), eq("2"), any(), any());
    }

    @Test
    @DisplayName("link: already one partner is a no-op, not an error, and calls nobody")
    void link_twice_is_a_no_op() {
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(customer(1, 100L)));
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(vender(2, 100L)));
        assertThat(service.link(1L, 2L)).isFalse();
        verify(party, never()).link(anyLong(), any());
        verify(venders, never()).updatePartyId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("link: another tenant's record reads as not found (anti-IDOR), and nothing is written")
    void link_refuses_a_record_outside_the_tenant() {
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(customer(1, 100L)));
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.link(1L, 2L)).isInstanceOf(PartyRoleService.Refusal.class).hasMessageContaining("not found");
        verify(party, never()).link(anyLong(), any());
    }

    @Test
    @DisplayName("position: sums every customer and supplier record of the partner; net and set-off limit follow")
    void position_sums_both_sides() {
        Customer c1 = customer(1, 100L); c1.setDueAmount(new java.math.BigDecimal("30000")); c1.setCreditBalance(new java.math.BigDecimal("500"));
        Customer c2 = customer(3, 100L); c2.setDueAmount(null);   // a record that never owed: null reads as 0
        Vender v = vender(2, 100L); v.setDueAmount(new java.math.BigDecimal("50000"));
        when(customers.findByPartyIdsScoped(eq(List.of(100L)), any(), any())).thenReturn(List.of(c1, c2));
        when(venders.findByPartyIdScoped(eq(100L), any(), any())).thenReturn(List.of(v));
        // What a set-off can clear: ordinary invoices / bills only (an installment invoice would be excluded upstream).
        when(setOffs.settleableReceivable(1L)).thenReturn(new java.math.BigDecimal("30000"));
        when(setOffs.settleableReceivable(3L)).thenReturn(java.math.BigDecimal.ZERO);
        when(setOffs.settleablePayable(2L)).thenReturn(new java.math.BigDecimal("50000"));

        var pos = service.position(100L);
        assertThat(pos.getReceivable()).isEqualByComparingTo("30000");
        assertThat(pos.getPayable()).isEqualByComparingTo("50000");
        assertThat(pos.getStoreCredit()).isEqualByComparingTo("500");
        assertThat(pos.getNetIfSetOff()).as("we owe them 20,000 net").isEqualByComparingTo("-20000");
        assertThat(pos.getSetOffLimit()).as("a set-off can clear at most the smaller side").isEqualByComparingTo("30000");
        assertThat(pos.getCustomers()).hasSize(2);
        assertThat(pos.getSuppliers()).hasSize(1);
    }

    @Test
    @DisplayName("link: refused while a set-off stands on either record — reverse it first")
    void link_refused_while_a_set_off_stands() {
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(customer(1, 100L)));
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(vender(2, 200L)));
        when(setOffRepo.countPostedFor(any(), eq(1L), eq(2L))).thenReturn(1L);
        assertThatThrownBy(() -> service.link(1L, 2L)).isInstanceOf(PartyRoleService.Refusal.class).hasMessageContaining("set-off");
        verify(party, never()).link(anyLong(), any());
    }

    @Test
    @DisplayName("hint: receiving from a customer whose partner we owe as a supplier names what we owe; applies nothing")
    void payment_hint_names_the_other_side() {
        Customer c = customer(1, 100L); c.setDueAmount(new java.math.BigDecimal("30000"));
        Vender v = vender(2, 100L); v.setDueAmount(new java.math.BigDecimal("50000"));
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(c));
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(v));
        when(customers.findByPartyIdsScoped(eq(List.of(100L)), any(), any())).thenReturn(List.of(c));
        when(venders.findByPartyIdScoped(eq(100L), any(), any())).thenReturn(List.of(v));
        when(setOffs.settleableReceivable(1L)).thenReturn(new java.math.BigDecimal("30000"));
        when(setOffs.settleablePayable(2L)).thenReturn(new java.math.BigDecimal("50000"));

        var receiving = service.paymentHint("CUSTOMER", 1L);
        assertThat((java.math.BigDecimal) receiving.get("otherSideOpen")).isEqualByComparingTo("50000");
        assertThat((java.math.BigDecimal) receiving.get("setOffLimit")).isEqualByComparingTo("30000");
        var paying = service.paymentHint("VENDOR", 2L);
        assertThat((java.math.BigDecimal) paying.get("otherSideOpen")).isEqualByComparingTo("30000");
        verify(venders, never()).updateDueAmount(anyLong(), any());
        verify(customers, never()).save(any());
    }

    @Test
    @DisplayName("hint: a customer with no supplier role (or no partner yet) says nothing — zero, never an error")
    void payment_hint_is_zero_for_one_role() {
        Customer c = customer(1, 100L); c.setDueAmount(new java.math.BigDecimal("30000"));
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(c));
        when(customers.findByPartyIdsScoped(eq(List.of(100L)), any(), any())).thenReturn(List.of(c));
        when(venders.findByPartyIdScoped(eq(100L), any(), any())).thenReturn(List.of());
        assertThat((java.math.BigDecimal) service.paymentHint("CUSTOMER", 1L).get("otherSideOpen")).isEqualByComparingTo("0");

        Customer noParty = customer(5, null);
        when(customers.findByIdScoped(eq(5L), any(), any())).thenReturn(Optional.of(noParty));
        assertThat((java.math.BigDecimal) service.paymentHint("CUSTOMER", 5L).get("otherSideOpen")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("position: a partner with no record in this tenant is refused, never an empty position (anti-IDOR)")
    void position_of_a_foreign_partner_is_refused() {
        when(customers.findByPartyIdsScoped(any(), any(), any())).thenReturn(List.of());
        when(venders.findByPartyIdScoped(anyLong(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.position(999L)).isInstanceOf(PartyRoleService.Refusal.class)
                .hasMessageContaining("No customer or supplier");
    }

    @Test
    @DisplayName("unlink: a record that shares its partner with nothing is refused before party-service is called")
    void unlink_refuses_a_record_with_nothing_to_leave() {
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(vender(2, 200L)));
        when(customers.countByPartyScoped(eq(200L), any(), any())).thenReturn(0L);
        when(venders.countByPartyScoped(eq(200L), any(), any())).thenReturn(1L);
        assertThatThrownBy(() -> service.unlink("VENDOR", 2L)).isInstanceOf(PartyRoleService.Refusal.class)
                .hasMessageContaining("does not share");
        verify(party, never()).detach(any());
    }

    @Test
    @DisplayName("unlink: a new partner is created WITHOUT matching, then stamped locally and audited")
    void unlink_gives_the_record_its_own_partner() {
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(vender(2, 100L)));
        when(customers.countByPartyScoped(eq(100L), any(), any())).thenReturn(1L);
        when(venders.countByPartyScoped(eq(100L), any(), any())).thenReturn(1L);
        when(party.detach(any())).thenReturn(PartyRef.builder().id(300L).build());

        assertThat(service.unlink("VENDOR", 2L)).isEqualTo(300L);
        verify(party).detach(org.mockito.ArgumentMatchers.argThat(r ->
                r.getRole() != null && "VENDOR".equals(r.getRole().getRole()) && Long.valueOf(2L).equals(r.getRole().getLocalId())
                && "03007654321".equals(r.getContact())));
        verify(venders).updatePartyId(2L, 300L);
        verify(audit).record(eq("PARTY_UNLINK"), eq("VENDOR"), eq("2"), any(), any());
    }
}
