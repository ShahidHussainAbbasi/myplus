package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.support.TestTenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * U15-C — the shop names the multiple it BUYS in ("peti", "carton", "case").
 *
 * <p>Design: {@code microservices/docs/slices/u15-pack-loose-ux.md} §5. The purchase screen offered a
 * Pack | Box toggle in which "Pack" meant the shop's own box and "Box" meant a carton of N of them — one
 * word, two meanings, on one form.
 *
 * <p><b>What these cases pin down is the WRITE CONTRACT, because it deliberately differs from the pack
 * rules beside it.</b> {@code packSize}, {@code looseUnit} and friends use "null means not supplied", so a
 * partial payload cannot silently clear them. {@code purchaseUnitName} is assigned unconditionally, because
 * for this field a blank IS the answer — "this shop does not buy in multiples" — and it is the common case.
 * Under the other idiom a shop could set a purchase unit and then never remove it.
 *
 * <p>Skips without Docker; runs on {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductPurchaseUnitTest {

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

    /** A pharmacy's box of 40 tablets, bought from the supplier twelve boxes to a "peti". */
    private ProductDTO.ProductDTOBuilder aBox() {
        return ProductDTO.builder().name("Panadol").sellingPrice(new BigDecimal("311.60"))
                .unit("box").packSize(40).looseUnit("tablet").looseUnitPlural("tablets").allowLoose(true);
    }

    @Test
    @DisplayName("⭐ the shop's own word for the multiple it buys in survives a round trip")
    void thePurchaseUnitRoundTrips() {
        ProductDTO saved = service.create(aBox().purchaseUnitName("peti").purchasePackCount(12).build());

        ProductDTO read = service.getById(saved.getId());

        assertThat(read.getPurchaseUnitName()).isEqualTo("peti");
        assertThat(read.getPurchasePackCount()).isEqualTo(12);
        // The shelf unit is untouched — three levels, three separate answers.
        assertThat(read.getUnit()).isEqualTo("box");
        assertThat(read.getLooseUnit()).isEqualTo("tablet");
    }

    @Test
    @DisplayName("⭐⭐ a shop that stops buying in multiples can CLEAR it — the pack rules' idiom could not")
    void thePurchaseUnitCanBeCleared() {
        ProductDTO saved = service.create(aBox().purchaseUnitName("peti").purchasePackCount(12).build());

        // The operator empties both boxes on the form, which posts "" and null.
        service.update(saved.getId(), aBox().purchaseUnitName("").purchasePackCount(null).build());

        ProductDTO read = service.getById(saved.getId());
        assertThat(read.getPurchaseUnitName())
                .as("blank is a real answer here: this shop no longer buys in multiples")
                .isNull();
        assertThat(read.getPurchasePackCount()).isNull();
    }

    @Test
    @DisplayName("⭐ a blank is stored as NULL, never as the empty string — the optional-code contract")
    void aBlankIsNullNotEmptyString() {
        ProductDTO saved = service.create(aBox().purchaseUnitName("   ").build());

        assertThat(service.getById(saved.getId()).getPurchaseUnitName())
                .as("'' would make \"has the shop named one?\" two checks instead of one, everywhere")
                .isNull();
    }

    @Test
    @DisplayName("⭐ the name is TRIMMED, so a stray space cannot become part of the shop's word")
    void theNameIsTrimmed() {
        ProductDTO saved = service.create(aBox().purchaseUnitName("  peti  ").build());

        assertThat(service.getById(saved.getId()).getPurchaseUnitName()).isEqualTo("peti");
    }

    @Test
    @DisplayName("⭐⭐ the pack rules KEEP their own idiom — this change must not make them clearable")
    void thePackRulesAreStillNotClearable() {
        /*
         * The control that stops U15-C from widening a contract it was not meant to touch. packSize and
         * allowLoose decide what a customer is charged and whether a sealed course may be split, so a
         * partial payload must leave them alone — "null means not supplied". If a later refactor made the
         * whole apply block unconditional for tidiness, this case fails and says why.
         */
        ProductDTO saved = service.create(aBox().purchaseUnitName("peti").build());

        // A payload that mentions neither pack size nor the split permission.
        service.update(saved.getId(), ProductDTO.builder().name("Panadol")
                .sellingPrice(new BigDecimal("311.60")).unit("box").build());

        ProductDTO read = service.getById(saved.getId());
        assertThat(read.getPackSize()).as("a pack size is not cleared by a payload that omits it").isEqualTo(40);
        assertThat(read.getAllowLoose()).as("nor is permission to split").isTrue();
    }

    @Test
    @DisplayName("most shops name nothing, and that is a real answer rather than missing data")
    void namingNothingIsTheCommonCase() {
        ProductDTO saved = service.create(aBox().build());

        ProductDTO read = service.getById(saved.getId());
        assertThat(read.getPurchaseUnitName()).isNull();
        assertThat(read.getPurchasePackCount()).isNull();
    }
}
