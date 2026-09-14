package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.support.TestTenant;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BLK-4 — a product edit made against a stale copy is refused, never silently written.
 *
 * <p>The defect: an operator opens a product, a purchase re-prices it meanwhile ({@code updatePrice}), and the
 * operator's Save writes the OLD selling price back over the purchase's. These cases pin the server contract
 * the product form relies on, against real MySQL, because the lock is Hibernate's UPDATE ... WHERE version = ?
 * and a mock would test nothing of it.
 *
 * <p>Same fixture shape as {@link ProductSkuOptionalTest}. Skips without Docker; runs on {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductOptimisticLockTest {

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
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        TestTenant.authenticate();
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    private ProductDTO created(String name) {
        return service.create(ProductDTO.builder().name(name).sellingPrice(new BigDecimal("100")).build());
    }

    /** The form's update body: what it read, with its edits, carrying the version it loaded. */
    private ProductDTO edit(ProductDTO loaded, String name, String price, Long version) {
        return ProductDTO.builder().id(loaded.getId()).name(name).sellingPrice(new BigDecimal(price))
                .version(version).build();
    }

    @Test
    void aNewProductCarriesAVersion_andAnEditReturnsTheMOVEDOne() {
        ProductDTO p = created("BLK4 version");
        assertThat(p.getVersion()).as("a created product carries a version").isNotNull();

        ProductDTO saved = service.update(p.getId(), edit(p, "BLK4 version edited", "110", p.getVersion()));

        // ⭐ Pins the saveAndFlush fix: with a plain save() the flush runs at commit, AFTER toDto, and this
        // response would still say the old version, so the caller's very next save would conflict with itself.
        assertThat(saved.getVersion()).as("the update's own response carries the new version")
                .isGreaterThan(p.getVersion());
    }

    @Test
    void theSecondEditorIsRefused_andTheFirstEditSurvives() {
        ProductDTO p = created("BLK4 race");
        Long loadedByBoth = p.getVersion();

        service.update(p.getId(), edit(p, "BLK4 race WINNER", "120", loadedByBoth));

        assertThatThrownBy(() -> service.update(p.getId(), edit(p, "BLK4 race LOSER", "130", loadedByBoth)))
                .as("a save against the version both editors loaded is refused")
                .isInstanceOf(OptimisticLockingFailureException.class);

        ProductDTO now = service.getById(p.getId());
        assertThat(now.getName()).as("the winner's edit survived").isEqualTo("BLK4 race WINNER");
        assertThat(now.getSellingPrice()).as("and the loser wrote nothing").isEqualByComparingTo("120");
    }

    @Test
    void aPurchaseRepriceBetweenLoadAndSave_refusesTheStaleForm_andKeepsThePurchasesPrice() {
        ProductDTO loaded = created("BLK4 reprice");

        // A purchase is received while the form is open: the real writer, PurchaseService → updatePrice.
        service.updatePrice(loaded.getId(), new BigDecimal("150"), new BigDecimal("100"));
        assertThat(service.getById(loaded.getId()).getVersion())
                .as("the re-price moved the version, otherwise the next assertion would pass for no reason")
                .isGreaterThan(loaded.getVersion());

        assertThatThrownBy(() -> service.update(loaded.getId(),
                edit(loaded, "BLK4 reprice", "100", loaded.getVersion())))
                .as("the form loaded before the purchase must not write its old price back")
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(service.getById(loaded.getId()).getSellingPrice())
                .as("⭐ the purchase's price survived").isEqualByComparingTo("150");
    }

    @Test
    void aSaveWithNoVersion_stillSaves_lastWriteWinsForOlderClients() {
        ProductDTO p = created("BLK4 legacy");
        service.updatePrice(p.getId(), new BigDecimal("150"), null);   // move the version on

        ProductDTO saved = service.update(p.getId(), edit(p, "BLK4 legacy edited", "140", null));

        assertThat(saved.getName()).as("a client that sends no version is not bricked").isEqualTo("BLK4 legacy edited");
    }

    @Test
    void activateAndDeactivate_moveTheVersionInTheirOwnResponse() {
        ProductDTO p = created("BLK4 active");
        ProductDTO off = service.setActive(p.getId(), false);
        assertThat(off.getVersion()).as("setActive's response carries the moved version, like update's")
                .isGreaterThan(p.getVersion());
    }
}
