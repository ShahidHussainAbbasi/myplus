package com.myplus.inventory.service;

import com.myplus.common.security.time.TenantClock;
import com.myplus.inventory.entity.StockMovement;
import com.myplus.inventory.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SSR-0a (doc slices/110-stock-ledger-ssr.md) — the ONE way a stock movement is recorded.
 *
 * <p>Called at each of the 5 places that change {@code stock_levels.current_stock} (sale confirm, sale return,
 * import, direct entry, {@code applyStockDelta}), with the quantity that place ACTUALLY applied to on-hand — never the
 * requested one (applyStockDelta clamps at zero; a quarantined return does not raise sellable stock).
 *
 * <p>{@code MANDATORY}: it joins the caller's transaction and refuses to run without one, so a movement and its ledger
 * row commit or roll back together — the ledger cannot drift from the shelf by a lost write.
 */
@Component
@RequiredArgsConstructor
public class StockLedger {

    private final StockMovementRepository movements;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long orgId, Long userId, Long productId, Long stockEntryId, String type,
                       BigDecimal quantity, BigDecimal unitCost, String sourceRef) {
        if (productId == null || quantity == null || quantity.signum() == 0) return;   // nothing moved, nothing to say
        movements.save(StockMovement.builder()
                .organizationId(orgId).userId(userId).productId(productId).stockEntryId(stockEntryId)
                .movementType(type == null || type.isBlank() ? com.myplus.commerce.contracts.dto.StockMovementType.RECEIPT : type)
                .quantity(quantity).unitCost(unitCost)
                .sourceRef(sourceRef == null ? null : (sourceRef.length() > 64 ? sourceRef.substring(0, 64) : sourceRef))
                .businessDate(TenantClock.today())
                .createdAt(LocalDateTime.now())
                .build());
    }

    /** The caller's type, or the endpoint's default when an older caller sent none. */
    static String typeOr(String type, String fallback) {
        return type == null || type.isBlank() ? fallback : type.trim();
    }
}
