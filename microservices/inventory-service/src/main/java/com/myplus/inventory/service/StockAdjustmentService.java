package com.myplus.inventory.service;

import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.inventory.dto.StockDTOs.StockAdjustmentDTO;
import com.myplus.inventory.dto.StockDTOs.StockAdjustmentView;
import com.myplus.inventory.entity.StockAdjustment;
import com.myplus.inventory.entity.StockAdjustment.AdjustmentType;
import com.myplus.inventory.repository.StockAdjustmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * BLK-5 — a manual stock correction, recorded ONCE per intent and attributable to a person and a shop.
 *
 * <p>Design: microservices/docs/slices/blk-5-stock-adjust-guard.md. Before this, a correction carried no key (a
 * retried save removed the stock twice), its reason was a default nobody chose, and the record named neither the
 * person (adjusted_by NULL on 34 of 34 rows) nor the shop (the table had no organization_id).
 *
 * <p><b>Deliberately NOT {@code @Transactional}.</b> When two requests race with one key, the loser's INSERT fails on
 * {@code uq_adj_org_idem}; that failure marks its transaction rollback-only, so no read inside it could fetch the
 * winner. The catch therefore lives here, outside the transaction, and the write lives in
 * {@link StockService#recordAdjustment} — the Facade-over-Unit-of-Work shape DUP-1 (ProductController) and the sale
 * (SF-3) already use. The catalog check is here for the same reason: a remote call must not hold a DB connection.
 *
 * <p>org/user are passed in (the controller reads CurrentUser), as {@link ReservationService} does, so this is
 * testable without a web or security context.
 */
@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    /** The reason column is VARCHAR(255) (V1). Refused past it rather than silently cut: a reason is testimony. */
    static final int MAX_REASON = 255;
    /** idempotency_key is VARCHAR(191) (V11). */
    static final int MAX_KEY = 191;
    /** How many corrections the history read returns — a screen's worth, never the whole table. */
    static final int HISTORY_LIMIT = 50;

    private final StockService stockService;
    private final StockAdjustmentRepository adjustments;

    /**
     * Record one correction and move the stock — or replay the correction this key already recorded.
     *
     * @return the recorded (or replayed) adjustment with the product's on-hand; {@code replayed} says which
     * @throws ValidationException        a refusal (no reason, bad quantity/type, unknown product, not enough stock) —
     *                                    nothing is written
     * @throws DuplicateResourceException the key was already used for a DIFFERENT correction — nothing is written
     */
    public StockAdjustmentView adjust(StockAdjustmentDTO dto, Long orgId, Long userId) {
        // ── 1. Guard clauses. A refusal writes nothing, so it leaves nothing under the key either. ──────────────
        if (dto == null || dto.getProductId() == null) {
            throw new ValidationException("Choose a product to correct.");
        }
        AdjustmentType type = dto.getAdjustmentType();
        if (type == null) {
            throw new ValidationException("Say whether the stock goes up or down.");
        }
        if (type == AdjustmentType.TRANSFER) {
            // It used to record a TRANSFER row that moved nothing — a record of an event that never happened.
            throw new ValidationException("Use a stock transfer to move stock between warehouses.");
        }
        BigDecimal qty = StockService.in(dto.getQuantity());
        if (qty.signum() <= 0) {
            // A negative quantity with DECREASE used to ADD stock; zero recorded a correction of nothing.
            throw new ValidationException("Enter a quantity greater than 0.");
        }
        String reason = dto.getReason() == null ? "" : dto.getReason().trim();
        if (reason.isEmpty()) {
            throw new ValidationException("Give a reason for the stock correction.");
        }
        if (reason.length() > MAX_REASON) {
            throw new ValidationException("Keep the reason to " + MAX_REASON + " characters.");
        }
        String key = normaliseKey(dto.getIdempotencyKey());

        // ── 2. A key already used: replay the same correction, refuse a different one. ─────────────────────────
        if (key != null) {
            Optional<StockAdjustment> prior = firstFor(key, orgId, userId);
            if (prior.isPresent()) return replay(prior.get(), dto.getProductId(), type, qty, orgId, userId);
        }

        // ── 3. The product is the caller's. addStock always checked this; adjust never did, so any product id —
        //       another shop's, or none at all — got a stock level in the caller's tenant. ──────────────────────
        stockService.assertProductExists(dto.getProductId());

        // ── 4. Write. 5. A racer that lost at the unique index replays the winner. ─────────────────────────────
        try {
            return stockService.recordAdjustment(dto, qty, reason, key, orgId, userId);
        } catch (DataIntegrityViolationException dup) {
            // Only a duplicate KEY is replayable. Any other constraint is a real fault and must surface as itself,
            // not as a successful correction — so with no key, or no row carrying it, rethrow.
            if (key == null) throw dup;
            return firstFor(key, orgId, userId)
                    .map(p -> replay(p, dto.getProductId(), type, qty, orgId, userId))
                    .orElseThrow(() -> dup);
        }
    }

    /** A product's corrections within the caller's tenant, newest first, at most {@link #HISTORY_LIMIT}. */
    public List<StockAdjustmentView> history(Long productId, Long orgId, Long userId) {
        if (productId == null) throw new ValidationException("Choose a product.");
        return adjustments.findByProductScoped(productId, orgId, userId, PageRequest.of(0, HISTORY_LIMIT)).stream()
                .map(a -> StockAdjustmentView.of(a, null, false))
                .toList();
    }

    /**
     * Answer a repeat with the correction the key already recorded — or refuse it when it asks for something else.
     *
     * <p>A key names ONE intended correction. Replaying it for a different product, direction or quantity would tell
     * the operator "done" for a change that was never made, so that is a 409 with a sentence, not a silent replay.
     *
     * <p>Quantities are compared at TWO decimal places. The request arrives as a Float ({@code BigDecimal.valueOf}
     * turns 0.3333f into 0.33329999…) and the stored value is whatever the column holds — decimal(38,2) on the
     * drifted dev database, (19,4) elsewhere. An exact comparison would turn a genuine retry of a third of a pack into
     * a false refusal; a real "different quantity" differs by far more than a cent.
     */
    private StockAdjustmentView replay(StockAdjustment prior, Long productId, AdjustmentType type, BigDecimal qty,
                                       Long orgId, Long userId) {
        boolean same = prior.getProductId().equals(productId)
                && prior.getAdjustmentType() == type
                && cents(prior.getQuantity()).compareTo(cents(qty)) == 0;
        if (!same) {
            throw new DuplicateResourceException("This correction was already recorded with different values. "
                    + "Reload the product to see its stock before trying again.");
        }
        return StockAdjustmentView.of(prior, stockService.currentStockFor(prior.getProductId(), orgId, userId), true);
    }

    private Optional<StockAdjustment> firstFor(String key, Long orgId, Long userId) {
        return adjustments.findByIdempotencyKeyScoped(key, orgId, userId).stream().findFirst();
    }

    /** Blank means "no key" — an older client — rather than a key made of nothing that every such client would share. */
    private static String normaliseKey(String raw) {
        if (raw == null) return null;
        String k = raw.trim();
        if (k.isEmpty()) return null;
        if (k.length() > MAX_KEY) throw new ValidationException("The request key is too long.");
        return k;
    }

    private static BigDecimal cents(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
