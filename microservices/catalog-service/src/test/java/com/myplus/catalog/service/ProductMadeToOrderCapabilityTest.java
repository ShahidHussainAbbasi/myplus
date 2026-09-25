package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;

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
 * RST — "made to order" is a per-product POLICY, so the tenant must be entitled to set it.
 *
 * <h3>The gap this closes, and how it was found</h3>
 * catalog-service has four per-product policy flags. Three of them — {@code rxRequired},
 * {@code requiresSerial}, {@code tracksBatch} — pass through {@code requireCapability} before they are
 * written. {@code madeToOrder} shipped without it, carrying only {@code data-capability} on the product
 * form, which hides a checkbox and nothing more. A POST to {@code /addProduct} or {@code /updateProduct}
 * set it regardless.
 *
 * <p>That mattered more here than for the other three, because this is the only one of the four that makes
 * a sale <b>skip the stock check</b>: {@code SagaSellService} exempts a made-to-order line from stock
 * reservation reading the product flag alone. So a tenant the platform had deliberately withheld the
 * capability from could switch the stock check off, one product at a time. The codebase's own rule is
 * <em>"Enforcement is the point, not hiding"</em>, recorded next to a note that client-side gating is
 * editable in devtools.
 *
 * <p>No end-to-end gate would have caught it: the RST-R2a Cypress suite runs as a fully entitled tenant, so
 * every one of its seven cases passes whether this guard exists or not. That is the reason these cases are
 * here rather than there.
 *
 * <h3>⚠ The asymmetry these cases pin</h3>
 * The WRITE is refused, and the SALE is not. A configuration write fails closed so an unentitled tenant
 * cannot newly enable this; a sale keeps honouring a flag already set, so withdrawing the capability — a
 * plan change, an operator action — never stops a working kitchen mid-service. Making the sale path check
 * the capability too would reintroduce exactly the harm {@code Plan.FREE} was corrected for, where a
 * restaurant converting off trial lost the ability to ring up any line at all.
 *
 * <p>Skips without Docker; runs on {@code mvn test}.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ProductMadeToOrderCapabilityTest {

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

    private static final String CAP = "madeToOrder";

    @Autowired private ProductService service;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    /** The tenant's menu item. A burger a kitchen assembles; it holds no finished ones. */
    private ProductDTO.ProductDTOBuilder aBurger() {
        return ProductDTO.builder().name("Zinger Burger").sellingPrice(new BigDecimal("350.00")).unit("plate");
    }

    /** Entitled, with the capability resolved and present. */
    private void asEntitledTenant() {
        TestTenant.authenticateWithCapabilities(Set.of(CAP));
    }

    /**
     * Unentitled: capabilities RESOLVED and this one absent.
     *
     * <p>Deliberately not the default {@link TestTenant#authenticate()}, which leaves capabilities null —
     * "unresolved", which {@code capabilityAllowed} treats as permissive. A refusal case written against
     * that would pass without the guard existing at all.
     */
    private void asUnentitledTenant() {
        TestTenant.authenticateWithCapabilities(Set.of("someOtherCapability"));
    }

    @Test
    @DisplayName("⭐⭐ THE GAP: a tenant without the capability cannot mark a product made to order")
    void anUnentitledTenantCannotSetTheFlag() {
        asUnentitledTenant();

        assertThatThrownBy(() -> service.create(aBurger().madeToOrder(true).build()))
                .as("the write is refused, not silently ignored")
                .hasMessageContaining("made to order");

        // ⚠ The after-state matters as much as the throw: a guard that raises AFTER the write would leave a
        // product that skips the stock check and an exception nobody acted on. Nothing was created.
        assertThat(productRepository.findAll())
                .as("no product was written at all")
                .isEmpty();
    }

    @Test
    @DisplayName("⭐⭐ THE CONTROL: an entitled tenant sets it, and it round-trips")
    void anEntitledTenantCanSetTheFlag() {
        /*
         * Without this case the one above passes just as well if the guard refuses EVERYONE — which would
         * break the restaurant vertical completely while looking like a working control.
         */
        asEntitledTenant();

        ProductDTO saved = service.create(aBurger().madeToOrder(true).build());

        assertThat(service.getById(saved.getId()).getMadeToOrder())
                .as("the entitled tenant's flag is stored and read back")
                .isTrue();
    }

    @Test
    @DisplayName("⭐ clearing it stays allowed after the capability is withdrawn")
    void clearingIsAlwaysAllowed() {
        /*
         * The rule requireIfSetting exists for. If turning a policy OFF needed the capability, a product
         * would be stuck made-to-order after a downgrade with no way back except a DBA — the tenant punished
         * for tidying up. Same reasoning as serial/IMEI and batch tracking beside it.
         */
        asEntitledTenant();
        ProductDTO saved = service.create(aBurger().madeToOrder(true).build());

        TestTenant.clear();
        asUnentitledTenant();

        assertThatCode(() -> service.update(saved.getId(), aBurger().madeToOrder(false).build()))
                .as("switching it OFF needs no entitlement")
                .doesNotThrowAnyException();

        TestTenant.clear();
        asEntitledTenant();
        assertThat(service.getById(saved.getId()).getMadeToOrder()).isFalse();
    }

    @Test
    @DisplayName("⭐ null is 'not supplied' — an unentitled tenant can still edit a made-to-order product")
    void omittingTheFlagIsNeverRefused() {
        /*
         * The case that stops this guard becoming a trap. An integration, a CSV import or a price change
         * posts no madeToOrder at all. If a null were refused, every such write against an existing
         * made-to-order product would start failing the moment the capability lapsed — the product
         * uneditable, for a reason its owner could neither see nor fix.
         */
        asEntitledTenant();
        ProductDTO saved = service.create(aBurger().madeToOrder(true).build());

        TestTenant.clear();
        asUnentitledTenant();

        assertThatCode(() -> service.update(saved.getId(),
                aBurger().sellingPrice(new BigDecimal("400.00")).build()))
                .as("a payload that never mentions the flag is not a change worth refusing")
                .doesNotThrowAnyException();

        TestTenant.clear();
        asEntitledTenant();
        ProductDTO read = service.getById(saved.getId());
        assertThat(read.getSellingPrice()).isEqualByComparingTo("400.00");
        assertThat(read.getMadeToOrder())
                .as("and the flag it did not mention was left exactly as it was")
                .isTrue();
    }
}
