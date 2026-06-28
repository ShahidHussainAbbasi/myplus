package com.myplus.business_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.myplus.business_service.service.SagaSellService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

/**
 * Tech-debt #12 — addSell atomicity, after M3c.4d (slice 86). The legacy local-Stock write path was retired:
 * {@code SellController.addSell} now delegates entirely to the inventory reservation saga
 * ({@link SagaSellService#addSell}), which persists Customer + CustomerHistory + Sell rows in its own committed
 * transactions and compensates (releases the stock hold) on failure — that saga-level atomicity is covered by
 * {@code SagaSellServiceTest}. This test pins the controller boundary: a saga failure must propagate as an error
 * past the {@code @Transactional}/exception-handler boundary, and nothing is persisted. Runs against real MySQL;
 * skips without Docker.
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
    private RequestUtil requestUtil;        // supplies the authenticated tenant user
    @MockitoBean
    private SagaSellService sagaSellService; // force the sale (saga) write to fail

    @Autowired
    private SellController sellController;   // injected as the @Transactional proxy
    @Autowired
    private CustomerRepo customerRepo;

    @BeforeEach
    void setup() {
        when(requestUtil.getCurrentUser()).thenReturn(
                new AuthenticatedUser(1L, "atom@test.com",
                        List.of(new SimpleGrantedAuthority("LOGIN_PRIVILEGE")), 1L));
        doThrow(new RuntimeException("Insufficient stock to complete the sale"))
                .when(sagaSellService).addSell(any());
    }

    @Test
    void addSell_surfaces_a_saga_failure_and_persists_nothing() {
        CustomerDTO customer = new CustomerDTO();
        customer.setName("AtomicCust");
        customer.setContact("0300ATOMIC");

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setCustomer(customer);
        dto.setSales(List.of(new SellDTO())); // non-empty so addSell proceeds to the saga write

        // The saga write throws → the controller propagates a RuntimeException past the @Transactional boundary.
        assertThatThrownBy(() -> sellController.addSell(dto, null)).isInstanceOf(RuntimeException.class);

        // The controller no longer writes the customer itself (the saga owns persistence and rolled back),
        // so no customer row exists.
        assertThat(customerRepo.findAll())
                .as("No customer persists when the saga write fails")
                .noneMatch(c -> "0300ATOMIC".equals(c.getContact()));
    }
}
