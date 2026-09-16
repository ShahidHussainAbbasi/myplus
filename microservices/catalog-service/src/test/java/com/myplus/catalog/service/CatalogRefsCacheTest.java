package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.myplus.catalog.dto.CategoryDTO;
import com.myplus.catalog.dto.TaxCodeDTO;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CACHE-2 — the three reference caches, and the rule that each is evicted only AFTER a successful commit of its OWN
 * data's write.
 *
 * <p>Pure JVM, the same real Spring event pipeline as {@link ProductPickerCacheTest} (listeners really registered,
 * really deferred, over its no-op transaction manager) — so a listener that evicted at once, or evicted the wrong
 * cache, fails here on every {@code mvn test}.
 */
class CatalogRefsCacheTest {

    private AnnotationConfigApplicationContext ctx;
    private CatalogRefsCache cache;
    private TransactionTemplate tx;
    private final AtomicInteger categoryLoads = new AtomicInteger();
    private final AtomicInteger taxCodeLoads = new AtomicInteger();
    private final AtomicInteger manufacturerLoads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(Wiring.class);
        cache = ctx.getBean(CatalogRefsCache.class);
        tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    // ── cache-aside ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a repeat read of each list is served from the cache — and each hit is COUNTED under its own name")
    void repeat_reads_are_hits_and_are_measured() {
        readAll(1L, 10L);
        readAll(1L, 10L);

        assertThat(categoryLoads.get()).isEqualTo(1);
        assertThat(taxCodeLoads.get()).isEqualTo(1);
        assertThat(manufacturerLoads.get()).isEqualTo(1);
        for (String name : List.of(CatalogRefsCache.CATEGORIES, CatalogRefsCache.TAX_CODES, CatalogRefsCache.MANUFACTURERS)) {
            double hits = ctx.getBean(MeterRegistry.class).get("cache.gets")
                    .tags("cache", name, "result", "hit").functionCounter().count();
            assertThat(hits).as(name).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("two users of one org do not share an entry — the scope's NULL-org leg depends on the user")
    void user_is_part_of_the_key() {
        readCategories(1L, 10L);
        readCategories(1L, 11L);
        assertThat(categoryLoads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a caller with no tenant is never cached — the database is read every time")
    void null_org_is_not_cached() {
        readCategories(null, 10L);
        readCategories(null, 10L);
        assertThat(categoryLoads.get()).isEqualTo(2);
    }

    // ── eviction: only after a successful commit, and only the list that changed ───────────────────

    @Test
    @DisplayName("⭐ a committed category write evicts the categories — only AFTER the commit")
    void category_commit_evicts_after_commit() {
        readCategories(1L, 10L);

        tx.executeWithoutResult(status -> {
            ctx.publishEvent(CatalogCategoriesChanged.of(1L));
            readCategories(1L, 10L);   // still inside the transaction: MySQL has not committed, the old list stands
            assertThat(categoryLoads.get()).as("not evicted before the commit").isEqualTo(1);
        });

        readCategories(1L, 10L);
        assertThat(categoryLoads.get()).as("evicted after the commit").isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ a rolled-back write evicts NOTHING — MySQL did not change")
    void rollback_does_not_evict() {
        readAll(1L, 10L);

        tx.executeWithoutResult(status -> {
            ctx.publishEvent(CatalogCategoriesChanged.of(1L));
            ctx.publishEvent(CatalogTaxCodesChanged.of(1L));
            ctx.publishEvent(CatalogProductsChanged.of(1L));
            status.setRollbackOnly();
        });

        readAll(1L, 10L);
        assertThat(categoryLoads.get()).isEqualTo(1);
        assertThat(taxCodeLoads.get()).isEqualTo(1);
        assertThat(manufacturerLoads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a write with NO transaction (CSV import's category auto-create) evicts at once — it has committed")
    void no_transaction_evicts_immediately() {
        readCategories(1L, 10L);
        ctx.publishEvent(CatalogCategoriesChanged.of(1L));
        readCategories(1L, 10L);
        assertThat(categoryLoads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ a tax-code write evicts ONLY the tax codes")
    void tax_code_event_evicts_only_tax_codes() {
        readAll(1L, 10L);
        tx.executeWithoutResult(status -> ctx.publishEvent(CatalogTaxCodesChanged.of(1L)));
        readAll(1L, 10L);

        assertThat(taxCodeLoads.get()).isEqualTo(2);
        assertThat(categoryLoads.get()).isEqualTo(1);
        assertThat(manufacturerLoads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ a product write (every purchase stamps a price) evicts ONLY the manufacturers")
    void product_event_evicts_only_manufacturers() {
        readAll(1L, 10L);
        tx.executeWithoutResult(status -> ctx.publishEvent(CatalogProductsChanged.of(1L)));
        readAll(1L, 10L);

        assertThat(manufacturerLoads.get()).isEqualTo(2);
        assertThat(categoryLoads.get()).isEqualTo(1);
        assertThat(taxCodeLoads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a category write evicts neither the tax codes nor the manufacturers")
    void category_event_evicts_only_categories() {
        readAll(1L, 10L);
        tx.executeWithoutResult(status -> ctx.publishEvent(CatalogCategoriesChanged.of(1L)));
        readAll(1L, 10L);

        assertThat(categoryLoads.get()).isEqualTo(2);
        assertThat(taxCodeLoads.get()).isEqualTo(1);
        assertThat(manufacturerLoads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an eviction for one tenant leaves every other tenant's lists cached")
    void eviction_is_per_tenant() {
        readAll(1L, 10L);
        readAll(2L, 20L);

        ctx.publishEvent(CatalogCategoriesChanged.of(2L));

        readCategories(1L, 10L);
        assertThat(categoryLoads.get()).as("org 1 untouched").isEqualTo(2);
        readCategories(2L, 20L);
        assertThat(categoryLoads.get()).as("org 2 reloaded").isEqualTo(3);
    }

    @Test
    @DisplayName("TTL 0 switches all three off without a redeploy")
    void zero_ttl_is_off() {
        ObjectProvider<MeterRegistry> none = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
        CatalogRefsCache off = new CatalogRefsCache(0, none);
        for (int i = 0; i < 2; i++) {
            off.categories(1L, 10L, this::categories);
            off.taxCodes(1L, 10L, this::taxCodes);
            off.manufacturers(1L, 10L, this::manufacturers);
        }
        assertThat(categoryLoads.get()).isEqualTo(2);
        assertThat(taxCodeLoads.get()).isEqualTo(2);
        assertThat(manufacturerLoads.get()).isEqualTo(2);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private void readAll(Long org, Long user) {
        cache.categories(org, user, this::categories);
        cache.taxCodes(org, user, this::taxCodes);
        cache.manufacturers(org, user, this::manufacturers);
    }

    private void readCategories(Long org, Long user) {
        cache.categories(org, user, this::categories);
    }

    private List<CategoryDTO> categories() {
        categoryLoads.incrementAndGet();
        return List.of(CategoryDTO.builder().id(1L).name("Tonics").build());
    }

    private List<TaxCodeDTO> taxCodes() {
        taxCodeLoads.incrementAndGet();
        return List.of(TaxCodeDTO.builder().id(1L).name("GST").rate(new BigDecimal("17")).build());
    }

    private List<String> manufacturers() {
        manufacturerLoads.incrementAndGet();
        return List.of("GSK");
    }

    @Configuration
    @EnableTransactionManagement   // registers the TransactionalEventListenerFactory, as in the running service
    static class Wiring {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new ProductPickerCacheTest.NoopTransactionManager();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CatalogRefsCache catalogRefsCache(ObjectProvider<MeterRegistry> registry) {
            return new CatalogRefsCache(300, registry);
        }
    }
}
