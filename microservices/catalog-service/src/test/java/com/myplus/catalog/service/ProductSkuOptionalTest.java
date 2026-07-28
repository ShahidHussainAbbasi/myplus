package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SKU is OPTIONAL — a shop may enter products by name alone.
 *
 * The reported failure: saving a second product with the SKU field left blank returned
 * 409 "Product SKU already exists: " (with an empty value). The column was NOT NULL, so the UI
 * sent '', and the duplicate check matched that '' against the first uncoded product. Blank is
 * now stored as NULL, and only a real code is checked for duplicates.
 *
 * Skips without Docker; runs on {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductSkuOptionalTest {

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

    private static final Long ORG = 1L, USER = 1L;

    @Autowired private ProductService service;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() throws Exception {
        productRepository.deleteAll();
        categoryRepository.deleteAll();

        // ProductService reads the tenant from the security context (CurrentUser).
        // Construct an AuthenticatedUser reflectively to avoid depending on a specific ctor signature.
        java.lang.reflect.Constructor<?> ctor = AuthenticatedUser.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?>[] pts = ctor.getParameterTypes();
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            if (pts[i].isPrimitive()) {
                if (pts[i] == boolean.class) args[i] = false;
                else args[i] = 0;
            } else if (pts[i] == String.class) {
                args[i] = "test";
            } else {
                args[i] = null;
            }
        }
        Object u = ctor.newInstance(args);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ProductDTO product(String name, String sku) {
        return ProductDTO.builder().name(name).sku(sku).build();
    }

    @Test
    void twoProductsMayBothBeSavedWithNoSku() {
        // The exact reported case: the second save used to 409 with "SKU already exists: ".
        service.create(product("Aspirin", ""));

        assertThatCode(() -> service.create(product("Bandage", "")))
                .as("a blank SKU is 'not set', not a value that can collide")
                .doesNotThrowAnyException();

        assertThat(productRepository.count()).isEqualTo(2);
    }

    @Test
    void blankAndWhitespaceOnlySkuAreStoredAsNull() {
        Long id1 = service.create(product("Aspirin", "")).getId();
        Long id2 = service.create(product("Bandage", "   ")).getId();
        Long id3 = service.create(product("Syrup", null)).getId();

        // NULL, never '' — '' is what made two uncoded products look identical.
        assertThat(productRepository.findById(id1)).get().extracting("sku").isNull();
        assertThat(productRepository.findById(id2)).get().extracting("sku").isNull();
        assertThat(productRepository.findById(id3)).get().extracting("sku").isNull();
    }

    @Test
    void aRealSkuIsStillUnique() {
        service.create(product("Aspirin", "A1"));

        assertThatThrownBy(() -> service.create(product("Other", "A1")))
                .as("relaxing blank must not relax real codes")
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void aRealSkuIsTrimmed() {
        service.create(product("Aspirin", "  A1  "));
        assertThat(productRepository.findBySkuScoped("A1", ORG, USER))
                .as("surrounding whitespace must not create a second, near-identical code")
                .isPresent();
    }

    @Test
    void anExistingSkuCanBeClearedOnEdit() {
        Long id = service.create(product("Aspirin", "A1")).getId();

        assertThatCode(() -> service.update(id, product("Aspirin", "")))
                .as("removing a code must be allowed, not read as a duplicate")
                .doesNotThrowAnyException();

        assertThat(productRepository.findById(id)).get().extracting("sku").isNull();
    }

    @Test
    void updatingAProductWithoutChangingItsSkuIsNotADuplicate() {
        Long id = service.create(product("Aspirin", "A1")).getId();

        assertThatCode(() -> service.update(id, product("Aspirin 500mg", "A1")))
                .as("a product must not collide with itself")
                .doesNotThrowAnyException();
    }

    @Test
    void editingCannotStealAnotherProductsSku() {
        service.create(product("Aspirin", "A1"));
        Long id2 = service.create(product("Bandage", "B1")).getId();

        assertThatThrownBy(() -> service.update(id2, product("Bandage", "A1")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void blankBarcodeIsAlsoStoredAsNull() {
        // Same class of bug: a blank barcode must never be matched by a scan.
        Long id = service.create(
                ProductDTO.builder().name("Aspirin").sku(null).barcode("").build()).getId();

        assertThat(productRepository.findById(id)).get().extracting("barcode").isNull();
    }
}
