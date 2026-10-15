package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.OrgDocumentSeqRepo;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.support.TestTenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** SSR-0c — the product's main supplier: the FIRST purchase names it, a later one moves nothing, the owner may change it. */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductMainSupplierTest {

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

    @Autowired private ProductService service;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private OrgDocumentSeqRepo counters;

    @BeforeEach
    void setUp() {
        products.deleteAll();
        categories.deleteAll();
        counters.deleteAll();
        TestTenant.authenticate();
    }

    @AfterEach
    void tearDown() { TestTenant.clear(); }

    private Long product() {
        return service.create(ProductDTO.builder().name("Panadol").build()).getId();
    }

    @Test
    void the_first_purchase_names_the_supplier_and_a_later_one_moves_nothing() {
        Long id = product();
        assertThat(service.stampMainSupplier(id, 11L)).isTrue();
        assertThat(service.stampMainSupplier(id, 22L)).as("a buy from another supplier").isFalse();
        assertThat(products.findById(id).orElseThrow().getMainSupplierId()).isEqualTo(11L);
    }

    @Test
    void the_owner_can_change_it_and_clear_it_and_the_product_carries_it() {
        Long id = product();
        service.stampMainSupplier(id, 11L);
        assertThat(service.setMainSupplier(id, 33L).getMainSupplierId()).isEqualTo(33L);
        assertThat(service.getById(id).getMainSupplierId()).as("the product form reads it").isEqualTo(33L);
        service.setMainSupplier(id, null);
        assertThat(products.findById(id).orElseThrow().getMainSupplierId()).isNull();
        assertThat(service.stampMainSupplier(id, 44L)).as("blank again: the next purchase names it").isTrue();
    }

    @Test
    void an_ordinary_product_save_does_not_clear_it() {
        Long id = product();
        service.stampMainSupplier(id, 11L);
        service.update(id, ProductDTO.builder().name("Panadol 500").build());
        assertThat(products.findById(id).orElseThrow().getMainSupplierId()).isEqualTo(11L);
    }
}
