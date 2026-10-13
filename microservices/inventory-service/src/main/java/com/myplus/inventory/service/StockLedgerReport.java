package com.myplus.inventory.service;

import com.myplus.inventory.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * SSR-1 (doc slices/110-stock-ledger-ssr.md) — one business's stock ledger summed for a period, per product:
 * {@code opening} (everything before the period), each movement type within it, and {@code closing}. The Stock & Sales
 * Report is built on this; quantities only (packs) — money comes from the sale documents in business-service.
 */
@Service
@RequiredArgsConstructor
public class StockLedgerReport {

    private final StockMovementRepository movements;

    @Transactional(readOnly = true)
    public Map<String, Object> periodSummary(Long orgId, LocalDate from, LocalDate to) {
        Map<Long, Map<String, Object>> byProduct = new TreeMap<>();
        for (Object[] r : movements.periodSummary(orgId, from, to)) {
            Long pid = ((Number) r[0]).longValue();
            String type = (String) r[1];
            BigDecimal before = r[2] == null ? BigDecimal.ZERO : new BigDecimal(r[2].toString());
            BigDecimal within = r[3] == null ? BigDecimal.ZERO : new BigDecimal(r[3].toString());
            Map<String, Object> row = byProduct.computeIfAbsent(pid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", k);
                m.put("opening", BigDecimal.ZERO);
                m.put("movements", new LinkedHashMap<String, BigDecimal>());
                m.put("closing", BigDecimal.ZERO);
                return m;
            });
            row.put("opening", ((BigDecimal) row.get("opening")).add(before));
            if (within.signum() != 0) {
                @SuppressWarnings("unchecked")
                Map<String, BigDecimal> mv = (Map<String, BigDecimal>) row.get("movements");
                mv.merge(type, within, BigDecimal::add);
            }
            row.put("closing", ((BigDecimal) row.get("closing")).add(before).add(within));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        out.put("ledgerStart", movements.ledgerStart(orgId));   // the report says so when the period starts earlier
        out.put("products", byProduct.values());
        return out;
    }
}
