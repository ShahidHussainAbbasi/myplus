package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.myplus.catalog.config.ProductUsageClients;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.BonusSchemeRepository;
import com.myplus.catalog.repository.PriceRuleRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.commerce.contracts.client.ProductUsageClient;
import com.myplus.commerce.contracts.dto.ProductUsage;
import com.myplus.common.web.exception.ValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PROD-DEL — a deactivated product is deleted permanently only when nothing references it.
 *
 * <p>Every refusal here is a case where a plain row delete would have orphaned data in another service with no
 * error anywhere (no database constraint links a product id to its product). The usage clients are mocked: what is
 * pinned is catalog's DECISION: which answers refuse, that a required service's silence refuses (fail closed),
 * that an unregistered optional service is not asked, and that a repeat is idempotent. No Spring context, no DB,
 * so it runs on every {@code mvn test}.
 */
class ProductDeletionServiceTest {

    private static final Long ID = 4242L;

    private final ProductRepository products = mock(ProductRepository.class);
    private final PriceRuleRepository priceRules = mock(PriceRuleRepository.class);
    private final BonusSchemeRepository bonusSchemes = mock(BonusSchemeRepository.class);
    private final ProductUsageClients clients = mock(ProductUsageClients.class);
    private final ProductDeletionWriter writer = mock(ProductDeletionWriter.class);
    private final ProductUsageClient business = mock(ProductUsageClient.class);
    private final ProductUsageClient inventory = mock(ProductUsageClient.class);
    private final ProductUsageClient marketplace = mock(ProductUsageClient.class);

    private ProductDeletionService service;

    @BeforeEach
    void setUp() {
        service = new ProductDeletionService(products, priceRules, bonusSchemes, clients, writer);
        when(clients.required()).thenReturn(List.of("business-service", "inventory-service"));
        when(clients.optional()).thenReturn(List.of("marketplace-service"));
        when(clients.clientFor("business-service")).thenReturn(business);
        when(clients.clientFor("inventory-service")).thenReturn(inventory);
        when(clients.clientFor("marketplace-service")).thenReturn(marketplace);
        when(clients.registered("marketplace-service")).thenReturn(false);
        when(business.usage(ID)).thenReturn(unused());
        when(inventory.usage(ID)).thenReturn(unused());
        when(marketplace.usage(ID)).thenReturn(unused());
        when(priceRules.countByProductId(ID)).thenReturn(0L);
        when(bonusSchemes.countReferencing(ID)).thenReturn(0L);
        when(writer.delete(ID)).thenReturn(true);
    }

    private void found(boolean active) {
        Product p = Product.builder().id(ID).name("Panadol").isActive(active).build();
        when(products.findByIdScoped(eq(ID), any(), any())).thenReturn(Optional.of(p));
    }

    private static ProductUsage unused() {
        return ProductUsage.builder().counts(new LinkedHashMap<>()).build();
    }

    private static ProductUsage used(String label, long n) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(label, n);
        return ProductUsage.builder().counts(counts).build();
    }

    @Test
    void anUnusedDeactivatedProduct_isDeleted() {
        found(false);
        assertThat(service.deletePermanently(ID)).isEqualTo(ProductDeletionService.Outcome.DELETED);
        verify(writer).delete(ID);
    }

    @Test
    void aProductNotInThisTenant_orAlreadyGone_answersAlreadyRemoved_andDeletesNothing() {
        when(products.findByIdScoped(eq(ID), any(), any())).thenReturn(Optional.empty());
        assertThat(service.deletePermanently(ID)).isEqualTo(ProductDeletionService.Outcome.ALREADY_REMOVED);
        verify(writer, never()).delete(anyLong());
    }

    @Test
    void aConcurrentDeleteThatWonTheRace_isStillAlreadyRemoved() {
        found(false);
        when(writer.delete(ID)).thenReturn(false);
        assertThat(service.deletePermanently(ID)).isEqualTo(ProductDeletionService.Outcome.ALREADY_REMOVED);
    }

    @Test
    void anActiveProduct_isRefused_itMustBeDeactivatedFirst() {
        found(true);
        assertThatThrownBy(() -> service.deletePermanently(ID))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Deactivate");
        verify(writer, never()).delete(anyLong());
    }

    @Test
    void aPriceRuleOrBonusScheme_keepsTheProduct() {
        found(false);
        when(priceRules.countByProductId(ID)).thenReturn(1L);
        when(bonusSchemes.countReferencing(ID)).thenReturn(2L);
        assertThatThrownBy(() -> service.deletePermanently(ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("1 price rule").hasMessageContaining("2 bonus schemes");
        verify(writer, never()).delete(anyLong());
    }

    @Test
    void stockInAnotherService_keepsTheProduct_andSaysWhat() {
        found(false);
        when(inventory.usage(ID)).thenReturn(used("stock level records", 3));
        assertThatThrownBy(() -> service.deletePermanently(ID))
                .isInstanceOf(ValidationException.class).hasMessageContaining("3 stock level records");
        verify(writer, never()).delete(anyLong());
    }

    @Test
    void aRequiredServiceThatDoesNotAnswer_refuses_failClosed() {
        found(false);
        when(business.usage(ID)).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> service.deletePermanently(ID))
                .isInstanceOf(ValidationException.class).hasMessageContaining("did not answer");
        verify(writer, never()).delete(anyLong());
    }

    @Test
    void anOptionalServiceNotDeployed_isNotAsked() {
        found(false);
        when(marketplace.usage(ID)).thenReturn(used("order item records", 9));   // would refuse, if it were asked
        assertThat(service.deletePermanently(ID)).isEqualTo(ProductDeletionService.Outcome.DELETED);
        verify(clients, never()).clientFor("marketplace-service");
    }

    @Test
    void anOptionalServiceThatIsDeployed_isAsked_andItsUsageKeepsTheProduct() {
        found(false);
        when(clients.registered("marketplace-service")).thenReturn(true);
        when(marketplace.usage(ID)).thenReturn(used("order item records", 9));
        assertThatThrownBy(() -> service.deletePermanently(ID))
                .isInstanceOf(ValidationException.class).hasMessageContaining("9 order item records");
    }

    @Test
    void usageIsDescribed_largestFirst() {
        Map<String, Long> usage = new LinkedHashMap<>();
        usage.put("sell records", 1L);
        usage.put("stock level records", 7L);
        assertThat(ProductDeletionService.describe(usage)).isEqualTo("7 stock level records, 1 sell records");
    }
}
