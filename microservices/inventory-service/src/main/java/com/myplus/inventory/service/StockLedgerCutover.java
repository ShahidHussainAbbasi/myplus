package com.myplus.inventory.service;

import com.myplus.commerce.contracts.dto.StockMovementType;
import com.myplus.common.security.time.TenantClock;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.entity.StockMovement;
import com.myplus.inventory.repository.StockLevelRepository;
import com.myplus.inventory.repository.StockMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSR-0b (doc slices/110-stock-ledger-ssr.md) — the ledger's starting balance, and the check that it still agrees.
 *
 * <h3>Cut-over: one OPENING row per product, once</h3>
 * For every stock level that has no OPENING row yet: OPENING = on-hand − whatever the ledger already holds for it
 * (movements recorded between the SSR-0a deploy and this run), dated the day it runs. Written with the level row
 * LOCKED ({@code SELECT … FOR UPDATE}) — every stock change updates that row, so a sale at the same moment waits and
 * cannot be counted twice. The OPENING row is written even when it is 0: it is the marker that the product is cut
 * over, so a later run never re-computes it (which would quietly book away a real difference as "opening").
 * Idempotent: products with an OPENING row are skipped. Legacy levels with no organisation are counted, not cut over.
 *
 * <h3>Reconciler: SUM(ledger) = on-hand, reported — never fixed</h3>
 * Daily and after the cut-over. A difference means a stock change that did not pass through {@link StockLedger}; it is
 * LOGGED and listed (the SSR screen shows it), never silently booked (STANDARDS §0b, D5).
 */
@Component
public class StockLedgerCutover {

    private static final Logger LOG = LoggerFactory.getLogger(StockLedgerCutover.class);
    static final int PAGE = 200;

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final TransactionTemplate tx;

    public StockLedgerCutover(StockLevelRepository levels, StockMovementRepository movements,
                              PlatformTransactionManager txManager) {
        this.levels = levels;
        this.movements = movements;
        this.tx = new TransactionTemplate(txManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            int opened = cutOver();
            if (opened > 0) LOG.info("SSR-0b: stock ledger opened for {} product(s)", opened);
            reportDifferences();
        } catch (RuntimeException e) {
            // Never stop the service over this: stock keeps working; the next start retries.
            LOG.error("SSR-0b: ledger cut-over failed - will retry on the next start", e);
        }
    }

    /** Daily, after the night's quiet hours. */
    @Scheduled(cron = "${inventory.ledger.reconcileCron:0 30 3 * * *}")
    public void daily() {
        try {
            reportDifferences();
        } catch (RuntimeException e) {
            LOG.error("SSR-0b: ledger reconciliation failed", e);
        }
    }

    /** Writes the missing OPENING rows; returns how many products were opened. */
    public int cutOver() {
        int opened = 0;
        while (true) {
            List<Long> page = levels.findIdsWithoutOpening(PageRequest.of(0, PAGE));
            if (page.isEmpty()) break;
            Integer n = tx.execute(status -> {
                int done = 0;
                for (Long id : page) {
                    StockLevel l = levels.lockById(id).orElse(null);
                    if (l == null || movements.hasOpening(l.getOrganizationId(), l.getProductId())) continue;
                    BigDecimal onHand = l.getCurrentStock() == null ? BigDecimal.ZERO : l.getCurrentStock();
                    BigDecimal already = movements.sumFor(l.getOrganizationId(), l.getProductId());
                    movements.save(StockMovement.builder()
                            .organizationId(l.getOrganizationId()).userId(l.getUserId()).productId(l.getProductId())
                            .movementType(StockMovementType.OPENING)
                            .quantity(onHand.subtract(already == null ? BigDecimal.ZERO : already))
                            .unitCost(l.getCostPrice()).sourceRef("GO-LIVE")
                            .businessDate(TenantClock.today()).createdAt(LocalDateTime.now())
                            .build());
                    done++;
                }
                return done;
            });
            opened += n == null ? 0 : n;
            if (n == null || n == 0) break;   // nothing changed: another instance is doing it - stop, never spin
        }
        long legacy = levels.countWithoutOrganization();
        if (legacy > 0) LOG.warn("SSR-0b: {} legacy stock level(s) with no organisation are not in the ledger", legacy);
        return opened;
    }

    /** Products whose ledger no longer adds up to on-hand, for one business (or all when null). Read-only. */
    public List<Map<String, Object>> differences(Long orgId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : movements.differences(orgId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organizationId", ((Number) r[0]).longValue());
            m.put("productId", ((Number) r[1]).longValue());
            m.put("onHand", r[2]);
            m.put("ledger", r[3]);
            out.add(m);
        }
        return out;
    }

    public int reportDifferences() {
        List<Map<String, Object>> diffs = differences(null);
        if (!diffs.isEmpty()) {
            LOG.warn("SSR-0b: {} product(s) whose stock ledger does not add up to on-hand (first: {}) - a stock change "
                    + "bypassed the ledger; investigate, do not adjust", diffs.size(), diffs.get(0));
        }
        return diffs.size();
    }
}
