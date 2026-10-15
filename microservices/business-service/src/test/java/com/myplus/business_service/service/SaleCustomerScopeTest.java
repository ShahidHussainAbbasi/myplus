package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * HMS S4-lite — a sale that names a customer by id resolves it INSIDE the seller's organisation. Before, the id was
 * loaded tenant-blind and the seller's organisation stamped onto it: another tenant's customer id would have moved
 * that customer into the seller's business.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleCustomerScopeTest {

    @Mock CustomerRepo customerRepo;
    @Mock RequestUtil requestUtil;
    @Mock PartyBridgeService partyBridgeService;
    @Mock com.myplus.business_service.util.AppUtil appUtil;   // isEmptyOrNull → false: the customer has an id
    @Spy @InjectMocks CustomerService service;

    @BeforeEach
    void seller() {
        when(requestUtil.getCurrentUser()).thenReturn(new AuthenticatedUser(70L, "pharmacist@x", List.of(), 15L));
    }

    private static CustomerHistoryDTO saleFor(Long customerId) {
        CustomerDTO c = new CustomerDTO();
        c.setCustomerId(customerId);
        c.setName("Ali Khan");
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(c);
        return dto;
    }

    @Test
    void another_tenants_customer_id_is_refused_and_nothing_is_saved() {
        when(customerRepo.findByIdScoped(900L, 15L, 70L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveUpdateCustomer(saleFor(900L)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Customer not found");
        verify(service, never()).save(any(Customer.class));
    }

    @Test
    void the_clinic_patients_customer_in_the_same_organisation_is_used_even_if_another_user_created_it() throws Exception {
        Customer reception = new Customer();
        reception.setCustomerId(901L);
        reception.setOrganizationId(15L);
        reception.setUserId(55L);              // registered by reception, sold to by the pharmacist (user 70)
        reception.setName("Ali Khan");
        when(customerRepo.findByIdScoped(901L, 15L, 70L)).thenReturn(Optional.of(reception));
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0)).when(service).save(any(Customer.class));

        Customer used = service.saveUpdateCustomer(saleFor(901L));

        assertThat(used.getCustomerId()).isEqualTo(901L);
        assertThat(used.getOrganizationId()).isEqualTo(15L);
        assertThat(used.getUserId()).as("the creator is kept").isEqualTo(55L);
    }
}
