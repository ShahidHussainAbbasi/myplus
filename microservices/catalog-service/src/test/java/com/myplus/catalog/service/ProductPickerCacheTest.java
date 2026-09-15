package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.myplus.catalog.dto.ProductPickerDTO;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CACHE-1 — the picker cache, and the user's rule that eviction happens only AFTER a successful commit.
 *
 * <p>Pure JVM: a real Spring event pipeline ({@code @TransactionalEventListener} really registered, really deferred)
 * over a no-op transaction manager — no database, no Docker, so it runs on every {@code mvn test}. Asserting the
 * timing through Spring's own machinery, rather than calling {@code onProductsChanged} directly, is the point: a
 * listener that evicted immediately would pass a direct call and break the rule.
 */
class ProductPickerCacheTest {

    private static final Pageable PAGE = PageRequest.of(0, 2000);

    private AnnotationConfigApplicationContext ctx;
    private ProductPickerCache cache;
    private TransactionTemplate tx;
    private final AtomicInteger loads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(Wiring.class);
        cache = ctx.getBean(ProductPickerCache.class);
        tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    // ── cache-aside ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a repeat read is served from the cache — and the hit is COUNTED, so the gate can prove it fires")
    void repeat_read_is_a_hit_and_is_measured() {
        read(1L, 10L);
        read(1L, 10L);

        assertThat(loads.get()).as("the database is read once").isEqualTo(1);
        double hits = ctx.getBean(MeterRegistry.class).get("cache.gets")
                .tags("cache", ProductPickerCache.CACHE_NAME, "result", "hit").functionCounter().count();
        assertThat(hits).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the same page sorted differently is a different entry")
    void sort_is_part_of_the_key() {
        assertThat(ProductPickerCache.keyOf(PageRequest.of(0, 50, Sort.by("name"))))
                .isNotEqualTo(ProductPickerCache.keyOf(PageRequest.of(0, 50, Sort.by("id"))));
    }

    // ── eviction: only after a successful commit ──────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a committed write evicts — but only AFTER the commit, never inside the transaction")
    void commit_evicts_after_commit() {
        read(1L, 10L);

        tx.executeWithoutResult(status -> {
            ctx.publishEvent(CatalogProductsChanged.of(1L));
            read(1L, 10L);   // still inside the transaction: MySQL has not committed, the old page stands
            assertThat(loads.get()).as("not evicted before the commit").isEqualTo(1);
        });

        read(1L, 10L);
        assertThat(loads.get()).as("evicted after the commit — the next read goes to the database").isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ a rolled-back write evicts NOTHING — MySQL did not change")
    void rollback_does_not_evict() {
        read(1L, 10L);

        tx.executeWithoutResult(status -> {
            ctx.publishEvent(CatalogProductsChanged.of(1L));
            status.setRollbackOnly();
        });

        read(1L, 10L);
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a write with NO transaction (CSV import's saveAll) evicts at once — it has already committed")
    void no_transaction_evicts_immediately() {
        read(1L, 10L);
        ctx.publishEvent(CatalogProductsChanged.of(1L));
        read(1L, 10L);
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("an eviction for one tenant leaves every other tenant's pages cached")
    void eviction_is_per_tenant() {
        read(1L, 10L);
        read(2L, 20L);

        ctx.publishEvent(CatalogProductsChanged.of(2L));

        read(1L, 10L);
        assertThat(loads.get()).as("org 1 untouched").isEqualTo(2);
        read(2L, 20L);
        assertThat(loads.get()).as("org 2 reloaded").isEqualTo(3);
    }

    @Test
    @DisplayName("TTL 0 switches the cache off without a redeploy")
    void zero_ttl_is_off() {
        ObjectProvider<MeterRegistry> none = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
        ProductPickerCache off = new ProductPickerCache(0, none);
        off.page(1L, 10L, PAGE, loader());
        off.page(1L, 10L, PAGE, loader());
        assertThat(loads.get()).isEqualTo(2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void read(Long org, Long user) {
        cache.page(org, user, PAGE, loader());
    }

    private Supplier<Page<ProductPickerDTO>> loader() {
        return () -> {
            loads.incrementAndGet();
            return new PageImpl<>(List.of(new ProductPickerDTO(1L, "Panadol", new BigDecimal("12.50"), false)));
        };
    }

    @Configuration
    @EnableTransactionManagement   // registers the TransactionalEventListenerFactory, as in the running service
    static class Wiring {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoopTransactionManager();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ProductPickerCache productPickerCache(ObjectProvider<MeterRegistry> registry) {
            return new ProductPickerCache(300, registry);
        }
    }

    /** Real transaction SYNCHRONIZATION (what the listener hooks into), no resource behind it. */
    static class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
