package com.myplus.business_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.myplus.business_service.controller.SellController;
import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.service.ISellService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Tech-debt #12 — addSell atomicity (slices 1182dca / 4c4d428). {@code addSell} is {@code @Transactional}
 * and writes Customer + CustomerHistory + Sell rows; if the sale write fails, ALL of them must roll back.
 * Here the Sell write is forced to throw (mocked {@link ISellService}); the test asserts the Customer
 * persisted earlier in the same transaction is rolled back. Runs against real MySQL; skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AddSellAtomicityTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    @MockitoBean
    private RequestUtil requestUtil;     // supplies the authenticated tenant user
    @MockitoBean
    private ISellService sellService;    // force the sale write to fail

    @Autowired
    private SellController sellController;   // injected as the @Transactional proxy
    @Autowired
    private CustomerRepo customerRepo;

    @BeforeEach
    void setup() throws Exception {
        when(requestUtil.getCurrentUser()).thenReturn(
                new AuthenticatedUser(1L, "atom@test.com",
                        List.of(new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), 1L));
        doThrow(new RuntimeException("stock unavailable")).when(sellService).addSell(anyList());
    }

    @Test
    void addSell_rolls_back_customer_when_the_sale_write_fails() {
        CustomerDTO customer = new CustomerDTO();
        customer.setName("AtomicCust");
        customer.setContact("0300ATOMIC");

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customer);
        dto.setSales(List.of(new SellDTO())); // non-empty so addSell proceeds to the sale write

        // addSell is @Transactional; the (mocked) sale write throws → the whole transaction rolls back.
        assertThatThrownBy(() -> sellController.addSell(dto, null)).isInstanceOf(RuntimeException.class);

        // The customer written earlier in the same transaction must NOT have persisted.
        assertThat(customerRepo.findAll())
                .as("Customer must roll back when the sale write fails")
                .noneMatch(c -> "0300ATOMIC".equals(c.getContact()));
    }
}
