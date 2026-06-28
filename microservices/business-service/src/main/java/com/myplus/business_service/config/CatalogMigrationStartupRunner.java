package com.myplus.business_service.config;

import com.myplus.business_service.service.CatalogMigrationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M3c (slice 82): deploy-time convergence safety net. The V5 Flyway script backfills product_id for items already
 * catalog-mapped; this runner handles the cross-service piece Flyway can't — mapping any **not-yet-mapped** legacy
 * items to catalog Products (via catalog-service) when a pre-convergence DB is deployed, then re-backfilling the sells
 * those newly-mapped items belong to. So a fresh/prod deploy of an existing DB is reproducible with NO manual step.
 *
 * Best-effort + non-blocking: runs in a daemon thread, retries until catalog-service is reachable, never fails startup;
 * idempotent (no-op when everything is already mapped — the common case). Disable with
 * {@code convergence.auto-migrate-catalog=false}. The admin {@code /migrate-catalog} endpoint remains as a manual fallback.
 */
@Component
@ConditionalOnProperty(name = "convergence.auto-migrate-catalog", matchIfMissing = true)
@RequiredArgsConstructor
public class CatalogMigrationStartupRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogMigrationStartupRunner.class);
    private static final int MAX_ATTEMPTS = 12;
    private static final long RETRY_MS = 10_000L;

    private final CatalogMigrationService catalogMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::migrateWithRetry, "catalog-auto-migrate");
        t.setDaemon(true);
        t.start();
    }

    private void migrateWithRetry() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int groups = catalogMigrationService.migrateAllUnmapped();
                if (groups == 0) {
                    LOG.info("Catalog auto-migrate: all items already mapped — nothing to do.");
                } else {
                    // M3c.4f (slice 88): the local-Stock product_id backfill was retired with the Stock table; the
                    // historical backfill runs at Flyway time (V5/V6). New items mapped here are sold via the saga
                    // (productId written directly), so there are no Stock-linked rows left to backfill.
                    LOG.info("Catalog auto-migrate: mapped {} tenant group(s).", groups);
                }
                return;
            } catch (Exception e) {
                LOG.warn("Catalog auto-migrate attempt {}/{} failed (catalog-service not reachable yet?): {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
                try { Thread.sleep(RETRY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        LOG.error("Catalog auto-migrate gave up after {} attempts — run POST /api/business/admin/migrate-catalog "
                + "then /backfill-product-ids manually for any pre-convergence tenant.", MAX_ATTEMPTS);
    }
}
