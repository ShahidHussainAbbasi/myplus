package com.myplus.marketplace.multiseller.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;

/** MKT-5b — the consigned stock ledger. Balances are sums of movements; nothing here edits a row. */
public interface MarketplaceConsignedStockRepository extends JpaRepository<MarketplaceConsignedStock, Long> {

    /** One movement per transfer and kind: a Receive retried after it was written does not count the stock twice. */
    boolean existsByTransferIdAndMovement(Long transferId, String movement);

    /** Units the consignee holds for this consignor, all products: what ending the agreement must wait on. */
    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM MarketplaceConsignedStock c "
            + "WHERE c.consignorOrgId = :consignor AND c.consigneeOrgId = :consignee")
    long heldUnits(@Param("consignor") Long consignor, @Param("consignee") Long consignee);

    /** Units the consignee holds for every consignor: what changing the warehouse must wait on. */
    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM MarketplaceConsignedStock c WHERE c.consigneeOrgId = :consignee")
    long heldAt(@Param("consignee") Long consignee);

    /**
     * What is held, per consignor and product, for one side of the pair: the consignor reads its own rows, the
     * consignee every consignor's. Columns: consignorOrgId, mktProductId, productName, units, value, units sold (5c),
     * consigneeOrgId. Rows that net to nothing are left out.
     */
    @Query("SELECT c.consignorOrgId, c.mktProductId, MAX(c.productName), SUM(c.quantity), SUM(c.quantity * c.unitValue), "
            + "SUM(CASE WHEN c.movement = 'SOLD' THEN -c.quantity ELSE 0 END), MAX(c.consigneeOrgId) "
            + "FROM MarketplaceConsignedStock c WHERE c.consignorOrgId = :org OR c.consigneeOrgId = :org "
            + "GROUP BY c.consignorOrgId, c.mktProductId HAVING SUM(c.quantity) <> 0 "
            + "ORDER BY c.consignorOrgId, MAX(c.productName)")
    List<Object[]> heldFor(@Param("org") Long org);

    /** MKT-5c — units the consignee holds for this consignor of one product: what its listing can sell. */
    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM MarketplaceConsignedStock c "
            + "WHERE c.consignorOrgId = :consignor AND c.consigneeOrgId = :consignee AND c.mktProductId = :product")
    long balance(@Param("consignor") Long consignor, @Param("consignee") Long consignee, @Param("product") Long product);

    /**
     * MKT-5c — the lots still held of one product, oldest first: each transfer the stock came in on, with its hold in
     * the consignor's books. Columns: transferId, holdKey, consignorProductId, units left, unitValue (as declared).
     */
    @Query("SELECT c.transferId, MAX(c.holdKey), MAX(c.consignorProductId), SUM(c.quantity), MAX(c.unitValue) "
            + "FROM MarketplaceConsignedStock c WHERE c.consignorOrgId = :consignor AND c.consigneeOrgId = :consignee "
            + "AND c.mktProductId = :product AND c.transferId IS NOT NULL "
            + "GROUP BY c.transferId HAVING SUM(c.quantity) > 0 ORDER BY MIN(c.id)")
    List<Object[]> lots(@Param("consignor") Long consignor, @Param("consignee") Long consignee, @Param("product") Long product);

    /** MKT-5c — the SOLD rows of one order line: what an Accept retried after it was written already moved. */
    List<MarketplaceConsignedStock> findByOrderLineIdAndMovement(Long orderLineId, String movement);

    /** MKT-5c — the voided tries of one line and lot: the next try's attempt number. */
    long countByTransferIdAndOrderLineIdAndMovement(Long transferId, Long orderLineId, String movement);
}
