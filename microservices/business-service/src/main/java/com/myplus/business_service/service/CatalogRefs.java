package com.myplus.business_service.service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;

/**
 * CACHE-3 — resolve many catalog {@link ProductRef}s in as few calls as the URL will bear. ONE copy.
 *
 * <h3>Why this exists as a class</h3>
 * The same chunked loop had been written twice — {@code SellController.productRefs} and
 * {@code PurchaseController.productRefs} — and had already drifted: one uses its named constant, the other
 * hardcodes {@code 100}. A third copy was about to go into the sell saga. Two copies of a rule about a URL length
 * limit is how one of them quietly stops honouring it.
 *
 * <h3>⚠ PERF-12 — why chunked at all (the defect that pays for this)</h3>
 * {@code CatalogClient.getProducts} is a {@code @GetExchange}, so every id rides in the QUERY STRING. Org 13 has 730
 * distinct products on its sale rows, and the Sale grid asked for all 730 at once. Tomcat rejected it before Spring
 * saw it — a bare {@code HTTP Status 400}, which is what a request line plus a bearer JWT over the 8 KB
 * {@code maxHttpHeaderSize} looks like. It failed on EVERY call, was caught, logged as a warning, and rendered a grid
 * with no product name on any row. It also gets worse with tenant size, i.e. exactly the shops that reported
 * slowness. {@value #BATCH} keeps a chunk near 500 bytes of ids.
 *
 * <h3>Degrading, deliberately, in two steps</h3>
 * A chunk that fails is logged and skipped, so the chunks that answered still name their rows — a partial answer
 * beats a blank screen. And the method never throws: every caller today treats missing refs as "no name", and a
 * read screen must not 500 because catalog hiccuped.
 *
 * <p>⚠ That tolerance is right for a READ SCREEN and wrong for a SALE. {@code SagaSellService} uses this to avoid N
 * round trips, then falls back to a single {@code getProduct(id)} for any id the batch did not return — which
 * preserves that path's existing failure behaviour exactly, instead of letting an unresolved product reach the line
 * loop as a null and be priced at zero.
 */
public final class CatalogRefs {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogRefs.class);

    /** Ids per request. 100 ≈ 500 bytes of query string — far under any header limit, 730 ids in 8 calls. */
    public static final int BATCH = 100;

    private CatalogRefs() {
    }

    /**
     * Resolve these ids, chunked. Nulls and duplicates are dropped; order does not matter to any caller, all of
     * which index the result by id.
     *
     * @return what catalog answered, by product id — never null, never throwing, possibly incomplete
     */
    public static Map<Long, ProductRef> byId(CatalogClient catalogClient, Collection<Long> productIds) {
        return chunked(catalogClient, productIds, false);
    }

    /**
     * CACHE-3 — the same, read LIVE from the database rather than catalog's per-product cache.
     *
     * <p>For the callers that decide MONEY or SAFETY from the answer: {@code SagaSellService} prices each line from
     * {@code sellingPrice} and refuses a prescription-only medicine on {@code rxRequired}. Those must never come from
     * a remembered row — the user's standing rule — so batching the saga's lookups does not put them behind a cache.
     */
    public static Map<Long, ProductRef> byIdFresh(CatalogClient catalogClient, Collection<Long> productIds) {
        return chunked(catalogClient, productIds, true);
    }

    private static Map<Long, ProductRef> chunked(CatalogClient catalogClient, Collection<Long> productIds,
            boolean fresh) {
        if (catalogClient == null || productIds == null || productIds.isEmpty()) return Collections.emptyMap();

        List<Long> ids = new java.util.ArrayList<>(new LinkedHashSet<>(productIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) return Collections.emptyMap();

        Map<Long, ProductRef> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i += BATCH) {
            List<Long> chunk = ids.subList(i, Math.min(i + BATCH, ids.size()));
            try {
                List<ProductRef> answered = fresh
                        ? catalogClient.getProductsFresh(chunk, true)
                        : catalogClient.getProducts(chunk);
                if (answered == null) continue;
                for (ProductRef r : answered) {
                    if (r != null && r.getId() != null) out.putIfAbsent(r.getId(), r);
                }
            } catch (Exception chunkFailed) {
                LOG.warn("catalog getProducts failed for a chunk of {} id(s); those rows resolve to nothing",
                        chunk.size(), chunkFailed);
            }
        }
        return out;
    }
}
