package com.myplus.catalog.service;

import com.myplus.catalog.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SKU-AUTO-2 (doc slices/109-sku-auto-code.md, owner decision D2: "automatic fill on deploy") — every product that has
 * no SKU gets the next 12-digit code of its business, at startup, with no manual step.
 *
 * <ul>
 *   <li><b>Idempotent:</b> it only ever touches {@code sku IS NULL}; a second run finds nothing. Each write is
 *       {@code UPDATE … WHERE id = ? AND sku IS NULL}, so a second instance starting at the same moment can never
 *       overwrite a code the first one gave (its number is simply unused — a gap, not a wrong code).</li>
 *   <li><b>Bulk JPQL, not entity saves:</b> no entity listener or @Version bump runs against a startup thread that has
 *       no user.</li>
 *   <li><b>Pages of {@value #PAGE}, one transaction each:</b> the per-business counter row is locked only for one page,
 *       so tills creating products during startup wait at most that long.</li>
 *   <li>A legacy row with no organisation has no counter to draw from — counted and logged, never guessed.</li>
 *   <li>Reports duplicate SKUs still present (V26 then left the SKU index non-unique — STANDARDS D5: report, never
 *       pick which row to rename).</li>
 * </ul>
 */
@Component
public class SkuBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(SkuBackfill.class);
    static final int PAGE = 200;

    private final ProductRepository products;
    private final SkuAllocator allocator;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public SkuBackfill(ProductRepository products, SkuAllocator allocator, PlatformTransactionManager txManager,
                       ApplicationEventPublisher events) {
        this.products = products;
        this.allocator = allocator;
        this.tx = new TransactionTemplate(txManager);
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            run();
        } catch (RuntimeException e) {
            // Never stop the service over this: products keep working without a SKU; the next start retries.
            LOG.error("SKU-AUTO-2: filling blank SKUs failed - will retry on the next start", e);
        }
    }

    /** Fills every blank SKU; returns how many products got a code. */
    public int run() {
        int filled = 0;
        Set<Long> orgs = new HashSet<>();
        while (true) {
            List<Object[]> page = products.findBlankSkuRows(PageRequest.of(0, PAGE));
            if (page.isEmpty()) break;
            Integer n = tx.execute(status -> {
                int done = 0;
                for (Object[] r : page) {
                    Long id = (Long) r[0], org = (Long) r[1], user = (Long) r[2];
                    done += products.fillBlankSku(id, allocator.next(org, user));
                    orgs.add(org);
                }
                return done;
            });
            filled += n == null ? 0 : n;
            if (n == null || n == 0) break;   // nothing changed: another instance owns these rows — stop, never spin
        }
        long legacy = products.countBlankSkuWithoutOrg();
        long dupGroups = products.countDuplicateSkuGroups();
        if (filled > 0) {
            LOG.info("SKU-AUTO-2: gave {} product(s) an automatic SKU across {} business(es)", filled, orgs.size());
            events.publishEvent(CatalogProductsChanged.of(orgs.toArray(new Long[0])));   // CACHE-1: pickers show it
        }
        if (legacy > 0) LOG.warn("SKU-AUTO-2: {} legacy product(s) with no organisation keep a blank SKU", legacy);
        if (dupGroups > 0) {
            LOG.warn("SKU-AUTO-2: {} SKU(s) are held by more than one product in the same business - the unique index "
                    + "(V26) was not added; fix these SKUs and restart", dupGroups);
        }
        return filled;
    }
}
