package com.myplus.inventory.repository;

import com.myplus.inventory.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** SSR-0a — the stock ledger. Insert-only by convention: nothing here updates or deletes a movement. */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** SSR-0b — has this product been cut over (an OPENING row exists)? */
    @Query("SELECT (COUNT(m) > 0) FROM StockMovement m WHERE m.organizationId = :org AND m.productId = :pid "
         + "AND m.movementType = 'OPENING'")
    boolean hasOpening(@Param("org") Long org, @Param("pid") Long pid);

    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m WHERE m.organizationId = :org AND m.productId = :pid")
    BigDecimal sumFor(@Param("org") Long org, @Param("pid") Long pid);

    /**
     * SSR-0b — cut-over products whose ledger does not add up to on-hand: [org, product, on_hand, ledger_sum].
     * Only products WITH an OPENING row (not yet cut over = not yet comparable). {@code :org} null = every business.
     */
    @Query(value = "SELECT sl.organization_id, sl.product_id, sl.current_stock, COALESCE(SUM(m.quantity), 0) AS ledger "
         + "FROM stock_levels sl JOIN stock_movement m ON m.organization_id = sl.organization_id AND m.product_id = sl.product_id "
         + "WHERE sl.organization_id IS NOT NULL AND (:org IS NULL OR sl.organization_id = :org) "
         + "AND EXISTS (SELECT 1 FROM stock_movement o WHERE o.organization_id = sl.organization_id "
         + "            AND o.product_id = sl.product_id AND o.movement_type = 'OPENING') "
         + "GROUP BY sl.id, sl.organization_id, sl.product_id, sl.current_stock "
         + "HAVING sl.current_stock <> COALESCE(SUM(m.quantity), 0)", nativeQuery = true)
    List<Object[]> differences(@Param("org") Long org);

    /**
     * SSR-1 — one business's ledger for a period, in ONE grouped query: [product_id, movement_type, qty before :from,
     * qty within :from..:to]. Opening = Σ "before" over all types; a type's movement = its "within"; closing =
     * opening + Σ within. Served by idx_sm_org_date.
     */
    @Query(value = "SELECT m.product_id, m.movement_type, "
         + "SUM(CASE WHEN m.business_date < :from THEN m.quantity ELSE 0 END), "
         + "SUM(CASE WHEN m.business_date >= :from THEN m.quantity ELSE 0 END) "
         + "FROM stock_movement m WHERE m.organization_id = :org AND m.business_date <= :to "
         + "GROUP BY m.product_id, m.movement_type", nativeQuery = true)
    List<Object[]> periodSummary(@Param("org") Long org, @Param("from") java.time.LocalDate from,
                                 @Param("to") java.time.LocalDate to);

    /** SSR-1 — the day this business's ledger starts (its first OPENING), or null before the cut-over. */
    @Query("SELECT MIN(m.businessDate) FROM StockMovement m WHERE m.organizationId = :org AND m.movementType = 'OPENING'")
    java.time.LocalDate ledgerStart(@Param("org") Long org);
}
