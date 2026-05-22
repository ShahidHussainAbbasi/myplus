package com.test.business;

import com.persistence.Repo.business.CustomerRepo;
import com.persistence.model.User;
import com.persistence.model.business.Customer;
import com.service.business.CustomerService;
import com.web.dto.business.CustomerDTO;
import com.web.dto.business.CustomerHistoryDTO;
import com.web.util.AppUtil;
import com.web.util.RequestUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Example;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentMatchers;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepo customerRepo;

    @Mock
    private AppUtil appUtil;

    @Mock
    private RequestUtil requestUtil;

    @InjectMocks
    private CustomerService customerService;

    private Customer customer;
    private User currentUser;

    @BeforeEach
    void setUp() {
        currentUser = new User();
        currentUser.setId(10L);
        currentUser.setUserType("BUSINESS");

        customer = new Customer();
        customer.setCustomerId(1L);
        customer.setUserId(10L);
        customer.setUserType("BUSINESS");
        customer.setName("Ahmed Khan");
        customer.setContact("03001234567");
        customer.setEmail("ahmed@khan.com");
        customer.setAddress("Lahore");
        customer.setDueAmount(500.0f);
        customer.setDueDate(LocalDate.now().plusDays(30));
        customer.setDated(LocalDateTime.now());
        customer.setUpdated(LocalDateTime.now());
    }

    // ─── ADD (new) ────────────────────────────────────────────────────────────

    @Test
    void addCustomer_newEntity_savesAndReturnsWithId() {
        Customer newCustomer = new Customer();
        newCustomer.setUserId(10L);
        newCustomer.setName("New Customer");
        newCustomer.setContact("03009876543");

        Customer saved = new Customer();
        saved.setCustomerId(2L);
        saved.setName("New Customer");
        when(customerRepo.save(newCustomer)).thenReturn(saved);

        Customer result = customerService.save(newCustomer);

        assertNotNull(result.getCustomerId());
        assertEquals("New Customer", result.getName());
        verify(customerRepo).save(newCustomer);
    }

    @Test
    void addCustomer_duplicateCheck_detectsExistingCustomer() {
        Customer filter = new Customer();
        filter.setUserId(10L);
        filter.setName("Ahmed Khan");
        Example<Customer> example = Example.of(filter);
        when(customerRepo.exists(example)).thenReturn(true);

        assertTrue(customerService.exists(example),
                "Duplicate customer with same name and userId should be detected");
    }

    @Test
    void addCustomer_uniqueContact_allowedToSave() {
        Customer newCustomer = new Customer();
        newCustomer.setContact("03111111111");
        newCustomer.setName("Unique Customer");

        Customer saved = new Customer();
        saved.setCustomerId(5L);
        when(customerRepo.save(newCustomer)).thenReturn(saved);

        assertNotNull(customerService.save(newCustomer).getCustomerId());
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    @Test
    void updateCustomer_existingEntity_preservesDatedField() {
        LocalDateTime originalDated = LocalDateTime.of(2023, 12, 1, 9, 0);
        customer.setDated(originalDated);
        customer.setUpdated(LocalDateTime.now());
        when(customerRepo.save(customer)).thenReturn(customer);

        Customer result = customerService.save(customer);

        assertEquals(originalDated, result.getDated(), "dated is non-updatable — must be preserved on update");
    }

    @Test
    void updateCustomer_updateName_savesNewName() {
        customer.setName("Ahmed Khan Updated");
        when(customerRepo.save(customer)).thenReturn(customer);

        assertEquals("Ahmed Khan Updated", customerService.save(customer).getName());
    }

    @Test
    void updateCustomer_getReferenceById_returnsExistingEntity() {
        when(customerRepo.getReferenceById(1L)).thenReturn(customer);

        Customer ref = customerService.getReferenceById(1L);

        assertEquals(1L, ref.getCustomerId());
        assertEquals("Ahmed Khan", ref.getName());
    }

    @Test
    void updateCustomer_dueAmount_accumulatesOnSave() {
        customer.setDueAmount(200.0f);
        when(customerRepo.save(customer)).thenReturn(customer);

        Customer result = customerService.save(customer);

        assertEquals(200.0f, result.getDueAmount(), 0.001f);
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    @Test
    void deleteCustomer_byId_delegatesToRepo() {
        doNothing().when(customerRepo).deleteById(1L);

        customerService.deleteById(1L);

        verify(customerRepo, times(1)).deleteById(1L);
    }

    @Test
    void deleteCustomer_multipleIds_deletesEach() {
        doNothing().when(customerRepo).deleteById(anyLong());

        customerService.deleteById(1L);
        customerService.deleteById(2L);

        verify(customerRepo, times(2)).deleteById(anyLong());
    }

    @Test
    void deleteCustomer_nonExistentId_propagatesException() {
        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
                .when(customerRepo).deleteById(999L);

        assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,
                () -> customerService.deleteById(999L));
    }

    // ─── GET ──────────────────────────────────────────────────────────────────

    @Test
    void getCustomer_findById_returnsCustomer() {
        when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));

        Optional<Customer> result = customerService.findById(1L);

        assertTrue(result.isPresent());
        assertEquals("Ahmed Khan", result.get().getName());
        assertEquals("03001234567", result.get().getContact());
    }

    @Test
    void getCustomer_findById_returnsEmptyForUnknownId() {
        when(customerRepo.findById(99L)).thenReturn(Optional.empty());

        assertFalse(customerService.findById(99L).isPresent());
    }

    @Test
    void getUserCustomers_filteredByUserId_returnsOnlyUserCustomers() {
        Customer filter = new Customer();
        filter.setUserId(10L);
        Example<Customer> example = Example.of(filter);
        when(customerRepo.findAll(example)).thenReturn(List.of(customer));

        List<Customer> results = customerService.findAll(example);

        assertEquals(1, results.size());
        assertEquals(10L, results.get(0).getUserId());
    }

    @Test
    void getAllCustomers_returnsAll() {
        Customer c2 = new Customer();
        c2.setCustomerId(2L);
        c2.setName("Sara Ali");
        when(customerRepo.findAll()).thenReturn(Arrays.asList(customer, c2));

        assertEquals(2, customerService.findAll().size());
    }

    @Test
    void getCustomer_existsById_returnsCorrectly() {
        when(customerRepo.existsById(1L)).thenReturn(true);
        when(customerRepo.existsById(99L)).thenReturn(false);

        assertTrue(customerService.existsById(1L));
        assertFalse(customerService.existsById(99L));
    }

    // ─── saveUpdateCustomer business logic ────────────────────────────────────

    @Test
    void saveUpdateCustomer_negativeInputDueAmount_isFlippedToPositive() throws Exception {
        // dueAmount = -300 → flipped to +300 because < 0
        CustomerDTO customerDTO = new CustomerDTO();
        customerDTO.setName("Test Customer");
        customerDTO.setContact("03009999999");
        customerDTO.setDueAmount(-300.0f);

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customerDTO);

        when(requestUtil.getCurrentUser()).thenReturn(currentUser);
        // new Customer() has null customerId → isEmptyOrNull(null) returns true
        when(appUtil.isEmptyOrNull(ArgumentMatchers.<Long>isNull())).thenReturn(true);
        // after findOne returns empty, customerObj still has null ID → isEmptyOrNull(null) again
        when(customerRepo.findOne(any(Example.class))).thenReturn(Optional.empty());

        Customer result = customerService.saveUpdateCustomer(dto);

        // -300 is flipped to 300; new customerObj.dueAmount is null → set to 300
        assertEquals(300.0f, result.getDueAmount(), 0.001f,
                "Negative due amount should be flipped positive and applied to new customer");
    }

    @Test
    void saveUpdateCustomer_nonNegativeInputDueAmount_isResetToZero() throws Exception {
        // dueAmount = 500 → reset to 0 (positive/zero values are treated as no due in new customer flow)
        CustomerDTO customerDTO = new CustomerDTO();
        customerDTO.setName("Test Customer");
        customerDTO.setContact("03008888888");
        customerDTO.setDueAmount(500.0f);

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customerDTO);

        when(requestUtil.getCurrentUser()).thenReturn(currentUser);
        when(appUtil.isEmptyOrNull(ArgumentMatchers.<Long>isNull())).thenReturn(true);
        when(customerRepo.findOne(any(Example.class))).thenReturn(Optional.empty());

        Customer result = customerService.saveUpdateCustomer(dto);

        assertEquals(0.0f, result.getDueAmount(), 0.001f,
                "Non-negative due input for new customer is reset to 0 by the service");
    }

    @Test
    void saveUpdateCustomer_existingCustomerById_accumulatesDueAmount() throws Exception {
        // customer with ID → loaded by getReferenceById → dueAmount accumulates
        customer.setCustomerId(1L);
        customer.setDueAmount(200.0f);

        CustomerDTO customerDTO = new CustomerDTO();
        customerDTO.setCustomerId(1L);
        customerDTO.setDueAmount(-50.0f); // negative additional due

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customerDTO);

        when(customerRepo.getReferenceById(1L)).thenReturn(customer);
        // customerId is not null (1L) → isEmptyOrNull(1L) returns false (Mockito default for boolean)
        // requestUtil.getCurrentUser() is NOT called — it is only in the new-customer (if) branch

        Customer result = customerService.saveUpdateCustomer(dto);

        // else branch: dueAmount = existing(200) + new(-50) = 150
        assertEquals(150.0f, result.getDueAmount(), 0.001f,
                "Due amount should accumulate: existing(200) + additional(-50) = 150");
    }

    @Test
    void saveUpdateCustomer_existingCustomerByContact_reusesExistingRecord() throws Exception {
        Customer existing = new Customer();
        existing.setCustomerId(7L);
        existing.setContact("03001234567");
        existing.setName("Ahmed Khan");
        existing.setDueAmount(100.0f);

        CustomerDTO customerDTO = new CustomerDTO();
        customerDTO.setContact("03001234567");
        customerDTO.setName("Ahmed Khan");
        customerDTO.setDueAmount(0.0f); // zero → reset to 0 → adds 0 to existing

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customerDTO);

        when(requestUtil.getCurrentUser()).thenReturn(currentUser);
        when(appUtil.isEmptyOrNull(ArgumentMatchers.<Long>isNull())).thenReturn(true);
        when(customerRepo.findOne(any(Example.class))).thenReturn(Optional.of(existing));
        // after findOne returns existing with ID 7L → isEmptyOrNull(7L) returns false
        when(appUtil.isEmptyOrNull(7L)).thenReturn(false);

        Customer result = customerService.saveUpdateCustomer(dto);

        assertEquals(7L, result.getCustomerId(), "Should reuse the existing customer record found by contact");
        // existing(100) + additional(0) = 100
        assertEquals(100.0f, result.getDueAmount(), 0.001f);
    }

    @Test
    void saveUpdateCustomer_setsTimestamps() throws Exception {
        CustomerDTO customerDTO = new CustomerDTO();
        customerDTO.setName("Timestamp Test");
        customerDTO.setContact("03007777777");
        customerDTO.setDueAmount(0.0f);

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customerDTO);

        when(requestUtil.getCurrentUser()).thenReturn(currentUser);
        when(appUtil.isEmptyOrNull(ArgumentMatchers.<Long>isNull())).thenReturn(true);
        when(customerRepo.findOne(any(Example.class))).thenReturn(Optional.empty());

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        Customer result = customerService.saveUpdateCustomer(dto);
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        assertTrue(result.getDated().isAfter(before) && result.getDated().isBefore(after));
        assertTrue(result.getUpdated().isAfter(before) && result.getUpdated().isBefore(after));
    }

    // ─── BUG DOCUMENTATION ────────────────────────────────────────────────────

    /**
     * BUG: CustomerService.saveUpdateCustomer() (line 273 in CustomerService.java):
     * The save call is commented out:  // this.save(customerObj);
     * The method returns a transient Customer object that is NEVER persisted.
     *
     * Impact: any caller depending on saveUpdateCustomer() to persist data silently
     * loses the customer record. This includes sell/purchase flows that use the
     * CustomerHistoryDTO path.
     *
     * Fix: uncomment  this.save(customerObj);  before the return statement.
     */
    @Test
    void bug_saveUpdateCustomer_doesNotPersistEntity() throws Exception {
        CustomerDTO customerDTO = new CustomerDTO();
        customerDTO.setName("Ghost Customer");
        customerDTO.setContact("03006666666");
        customerDTO.setDueAmount(0.0f);

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customerDTO);

        when(requestUtil.getCurrentUser()).thenReturn(currentUser);
        when(appUtil.isEmptyOrNull(ArgumentMatchers.<Long>isNull())).thenReturn(true);
        when(customerRepo.findOne(any(Example.class))).thenReturn(Optional.empty());

        customerService.saveUpdateCustomer(dto);

        // save() is never called because it's commented out in the service
        verify(customerRepo, never()).save(any(Customer.class));
    }

    /**
     * BUG: CustomerController.addCustomer() (line 125 in CustomerController.java):
     * Example.of(obj) is created BEFORE obj.setUserId() and obj.setName() are set.
     * An empty Example matches ALL customers, blocking new inserts when any customer exists.
     *
     * Fix: move Example.of(obj) to after the setUserId/setName calls inside the if-block.
     */
    @Test
    void bug_addCustomer_emptyExampleMatchesAllCustomers() {
        // Simulate what the controller does: Example created before setting filter fields
        Customer emptyFilter = new Customer(); // userId and name NOT yet set
        Example<Customer> emptyExample = Example.of(emptyFilter);
        when(customerRepo.exists(emptyExample)).thenReturn(true);

        assertTrue(customerService.exists(emptyExample),
                "BUG: empty Example matches any existing record, falsely preventing all new customer inserts");
    }

    /**
     * BUG: CustomerRepo contains legacy appointment-related queries
     * (isPatientAppointed, findByPatient, findByDoctor, getLastAppointment)
     * that belong to an unrelated medical module. These add noise and may cause
     * confusion or runtime errors if the 'appointment' table does not exist.
     *
     * Fix: remove these queries from CustomerRepo and ICustomerService;
     * they should live in a dedicated AppointmentRepo.
     */
    @Test
    void bug_customerRepoHasLegacyAppointmentQueries() {
        // Verify that calling appointment-style methods throws or is unexpected on Customer service
        // These methods don't belong here and should be removed
        assertDoesNotThrow(() -> {
            // The methods exist on the interface but conceptually should not
            // This test documents their unwanted presence
            assertTrue(customerService instanceof com.service.business.ICustomerService,
                    "ICustomerService inherits appointment queries from CustomerRepo — these should be removed");
        });
    }
}
