package com.myplus.business_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.SerialUnit;

/**
 * SER-2 — the per-unit register. <b>Every query is tenant-scoped;</b> there is no unscoped read, because a
 * serial is exactly the kind of identifier somebody would try to look up across tenants.
 */
public interface SerialUnitRepo extends JpaRepository<SerialUnit, Long> {

    /**
     * The live unit carrying this serial, if any.
     *
     * <p>Keyed on {@code status} rather than on {@code liveSerialNo}: the generated column exists to make the
     * DATABASE enforce uniqueness, and querying it here would tie the read to that implementation detail. The
     * status filter says what is meant — "the one that is in stock".
     */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.serialNo = :serialNo "
         + "AND s.status = 'IN_STOCK'")
    Optional<SerialUnit> findLive(@Param("orgId") Long orgId, @Param("serialNo") String serialNo);

    /** Everything ever recorded under this serial — the history a warranty or police enquiry actually needs. */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.serialNo = :serialNo "
         + "ORDER BY s.serialUnitId DESC")
    List<SerialUnit> findHistory(@Param("orgId") Long orgId, @Param("serialNo") String serialNo);

    /** Units of a product currently in stock — what a cashier picks from when selling a tracked item. */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.productId = :productId "
         + "AND s.status = 'IN_STOCK' ORDER BY s.serialUnitId")
    List<SerialUnit> findInStock(@Param("orgId") Long orgId, @Param("productId") Long productId);

    /** How many units of this product are on the shelf. A COUNT, not a list-and-size. */
    @Query("SELECT COUNT(s) FROM SerialUnit s WHERE s.organizationId = :orgId AND s.productId = :productId "
         + "AND s.status = 'IN_STOCK'")
    long countInStock(@Param("orgId") Long orgId, @Param("productId") Long productId);

    /**
     * Units ON THE SHELF, counted by condition \u2014 the dashboard's condition card.
     *
     * <h3>\u26a0 IN_STOCK only, and that is the whole point</h3>
     * The register holds sold units for ever: that is what answers "who did we sell this handset to?". A card
     * counting every row would tell a shop it holds 12 used handsets when 11 are on the shelf and one is in a
     * customer's pocket. The status filter is what makes the number actionable rather than historical.
     *
     * <p>One grouped query rather than a count per grade: three round trips to draw one card is three too
     * many, and a fourth grade added later would cost a fourth with no edit here.
     */
    @Query("SELECT s.conditionGrade, COUNT(s) FROM SerialUnit s WHERE s.organizationId = :orgId "
         + "AND s.status = 'IN_STOCK' GROUP BY s.conditionGrade")
    List<Object[]> countInStockByCondition(@Param("orgId") Long orgId);

    /**
     * The units themselves, one grade at a time, newest first and PAGED.
     *
     * <h3>Why this is paged when the shop probably has eleven</h3>
     * Eleven today; a shop taking trade-ins has hundreds within a year, and an unbounded read is the defect
     * that has to be designed out rather than discovered. Newest first because a used handset booked in this
     * morning is the one somebody is asking about.
     *
     * <p>Status is a parameter rather than fixed to IN_STOCK so the same query answers "what did we sell"
     * without a second one that could drift from it.
     */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId "
         + "AND s.conditionGrade = :grade AND s.status = :status ORDER BY s.dated DESC, s.serialUnitId DESC")
    Page<SerialUnit> findByCondition(
            @Param("orgId") Long orgId, @Param("grade") String grade, @Param("status") String status,
            Pageable pageable);

    /**
     * SER-3 — claim a unit for a sale. Returns the number of rows changed: <b>1 won, 0 lost.</b>
     *
     * <h3>Why the WHERE clause carries {@code status = 'IN_STOCK'}</h3>
     * Marking a unit sold is an UPDATE, and no unique index can referee an update the way one referees the
     * insert that V52 protects. Two tills selling the same handset in the same second would both read
     * "in stock" and both write "sold", and the shop would have sold one phone twice.
     *
     * <p>Making the status part of the WHERE turns it into a compare-and-set: the database decides the winner,
     * and the loser gets 0 rows back and can say so. A read-then-write in application code cannot close that
     * window, which is the same argument V44 makes for the insert side.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE SerialUnit s SET s.status = 'SOLD', s.invoiceNo = :invoiceNo, s.updated = :now "
         + "WHERE s.organizationId = :orgId AND s.serialNo = :serialNo AND s.status = 'IN_STOCK'")
    int markSold(@Param("orgId") Long orgId, @Param("serialNo") String serialNo,
                 @Param("invoiceNo") String invoiceNo, @Param("now") java.time.LocalDateTime now);

    /**
     * SER-3 — put a unit back on the shelf (a sale return or a repossession).
     *
     * <p>Guarded on {@code status = 'SOLD'} for the mirror-image reason: restocking a unit that is already in
     * stock would create a second live row for the same serial, which is precisely what V52's unique index
     * exists to prevent — and it would surface as a constraint error on an unrelated write later.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE SerialUnit s SET s.status = 'IN_STOCK', s.invoiceNo = null, s.updated = :now "
         + "WHERE s.organizationId = :orgId AND s.serialNo = :serialNo AND s.status = 'SOLD'")
    int markReturned(@Param("orgId") Long orgId, @Param("serialNo") String serialNo,
                     @Param("now") java.time.LocalDateTime now);

    /** The units a given purchase brought in — used when a purchase is edited or reversed. */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.purchaseId = :purchaseId")
    List<SerialUnit> findByPurchase(@Param("orgId") Long orgId, @Param("purchaseId") Long purchaseId);

    /**
     * The units several purchases brought in, in ONE query.
     *
     * <p>Batched deliberately. The purchase register renders every bill a shop has, and the list endpoint
     * already resolves products and vendors in batches for exactly this reason; a per-row serial lookup would
     * put an N+1 behind the busiest read screen in the product.
     *
     * <p>Ordered by purchase then id so the serials of one bill come back in the order they were received —
     * the order the operator typed them, and the order the edit form has to show them back in.
     */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.purchaseId IN :purchaseIds "
         + "ORDER BY s.purchaseId, s.serialUnitId")
    List<SerialUnit> findByPurchaseIds(@Param("orgId") Long orgId,
                                       @Param("purchaseIds") java.util.Collection<Long> purchaseIds);

    /**
     * Every unit that left on a given SALE invoice.
     *
     * <p>Exists because "does 10225 exist?" is asked with a document number far more often than with a
     * serial: an operator holds a bill or a receipt, not a handset. {@code findHistory} answers only the
     * serial question, and its empty result reads as "no such unit" when the truth is "that is not a serial".
     */
    @Query("SELECT s FROM SerialUnit s WHERE s.organizationId = :orgId AND s.invoiceNo = :invoiceNo "
         + "ORDER BY s.serialUnitId DESC")
    List<SerialUnit> findBySaleInvoice(@Param("orgId") Long orgId, @Param("invoiceNo") String invoiceNo);
}
