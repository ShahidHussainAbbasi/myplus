package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.repository.CategoryRepository;
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

/**
 * Last rates are STAMPED ONTO THE PRODUCT by the purchase flow (Option B, extended) — never derived from purchase
 * history when the Product screen is opened. {@code updatePrice} is that stamp: business-service calls it on both
 * addPurchase and updatePurchase with the bill's sell and purchase rates.
 *
 * <p>What these tests pin down is the GUARD. A purchase may legitimately carry only one of the two rates (or a
 * blank/zero), and in every such case the master must keep what it already had rather than be wiped — that is the
 * behaviour a mistyped or partly-filled bill depends on.
 *
 * <p>Skips without Docker; runs on {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductLastRatesTest {

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

        // ProductService reads the tenant from the security context (CurrentUser), and every read it makes
        // is org-scoped — so the fixture must be a REAL caller, not a placeholder. See TestTenant.
        TestTenant.authenticate();
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    private Long newProduct() {
        return service.create(ProductDTO.builder().name("Aspirin").sellingPrice(new BigDecimal("10.00")).build()).getId();
    }

    @Test
    void aPurchaseStampsBothRatesAndThePriceOntoTheProduct() {
        Long id = newProduct();

        ProductDTO out = service.updatePrice(id, new BigDecimal("65.00"), new BigDecimal("40.00"));

        assertThat(out.getLastPurchaseRate()).as("what the bill paid").isEqualByComparingTo("40.00");
        assertThat(out.getLastSaleRate()).as("what the bill set the price to").isEqualByComparingTo("65.00");
        assertThat(out.getSellingPrice()).as("the live master price moves with the sell rate").isEqualByComparingTo("65.00");
        assertThat(out.getLastRateAt()).as("when it was stamped").isNotNull();
    }

    @Test
    void aLaterPurchaseReplacesBothRates() {
        Long id = newProduct();
        service.updatePrice(id, new BigDecimal("65.00"), new BigDecimal("40.00"));

        // The point of stamping at purchase time: an edit or a later bill simply overwrites.
        ProductDTO out = service.updatePrice(id, new BigDecimal("70.00"), new BigDecimal("45.00"));

        assertThat(out.getLastPurchaseRate()).isEqualByComparingTo("45.00");
        assertThat(out.getLastSaleRate()).isEqualByComparingTo("70.00");
    }

    @Test
    void aPurchaseCarryingOnlyACostLeavesThePriceAlone() {
        Long id = newProduct();
        service.updatePrice(id, new BigDecimal("65.00"), new BigDecimal("40.00"));

        // Bought again with no sell rate entered on the bill — the cost updates, the shop's price must NOT move.
        ProductDTO out = service.updatePrice(id, null, new BigDecimal("42.00"));

        assertThat(out.getLastPurchaseRate()).isEqualByComparingTo("42.00");
        assertThat(out.getSellingPrice()).as("a cost-only bill must never silently re-price the shop")
                .isEqualByComparingTo("65.00");
        assertThat(out.getLastSaleRate()).isEqualByComparingTo("65.00");
    }

    @Test
    void aPurchaseCarryingOnlyASellRateLeavesTheCostAlone() {
        Long id = newProduct();
        service.updatePrice(id, new BigDecimal("65.00"), new BigDecimal("40.00"));

        ProductDTO out = service.updatePrice(id, new BigDecimal("72.00"), null);

        assertThat(out.getLastSaleRate()).isEqualByComparingTo("72.00");
        assertThat(out.getLastPurchaseRate()).as("no cost on this bill → keep the last known cost")
                .isEqualByComparingTo("40.00");
    }

    @Test
    void blankAndZeroRatesNeverWipeWhatIsAlreadyStamped() {
        Long id = newProduct();
        service.updatePrice(id, new BigDecimal("65.00"), new BigDecimal("40.00"));

        // A bill with both fields left empty, and one with explicit zeros — neither is a real rate.
        service.updatePrice(id, null, null);
        ProductDTO out = service.updatePrice(id, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(out.getLastPurchaseRate()).isEqualByComparingTo("40.00");
        assertThat(out.getLastSaleRate()).isEqualByComparingTo("65.00");
        assertThat(out.getSellingPrice()).isEqualByComparingTo("65.00");
    }

    @Test
    void aNeverPurchasedProductHasNoRatesAtAll() {
        Long id = newProduct();

        ProductDTO out = service.getById(id);

        // Null, not zero — the screen shows a dash, because "bought for 0.00" would be a lie.
        assertThat(out.getLastPurchaseRate()).isNull();
        assertThat(out.getLastSaleRate()).isNull();
        assertThat(out.getLastRateAt()).isNull();
    }
}
