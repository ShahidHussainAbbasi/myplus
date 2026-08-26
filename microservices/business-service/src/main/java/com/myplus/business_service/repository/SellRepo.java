/**
 * 
 */
package com.myplus.business_service.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.stereotype.Repository;

import com.myplus.business_service.entity.Sell;

/**
 * @author sabbasi
 *
 */
@Repository
public interface SellRepo extends JpaRepository<Sell, Long>,QueryByExampleExecutor<Sell> {
	

    // Tenant-scoped read with NULL-fallback (own org's rows + caller's pre-migration org-NULL rows),
    // newest first. Replaces the Example-by-userId reads.
    @Query("select s from Sell s where s.organizationId = :orgId "
         + "or (s.organizationId is null and s.userId = :userId) order by s.sellId desc")
    List<Sell> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    // Paged overload (slice 24) — newest first, LIMIT/OFFSET via Pageable.
    @Query("select s from Sell s where s.organizationId = :orgId "
         + "or (s.organizationId is null and s.userId = :userId) order by s.sellId desc")
    List<Sell> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    // OWN rows only (role-aware visibility, Phase 7a): a non-SUPER caller sees just what they created —
    // Multi-location (P2b): store-aware variants — used only when the caller has store grants (non-empty set).
    // Legacy store-NULL rows remain visible so nothing disappears before data is re-saved with a store.
    @Query("select s from Sell s where s.organizationId = :orgId "
         + "and (s.storeId in :storeIds or s.storeId is null)")
    List<Sell> findScopedByStores(@Param("orgId") Long orgId, @Param("storeIds") java.util.Collection<Long> storeIds);

    @Query("select s from Sell s where s.organizationId = :orgId and s.userId = :userId "
         + "and (s.storeId in :storeIds or s.storeId is null)")
    List<Sell> findOwnScopedByStores(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                     @Param("storeIds") java.util.Collection<Long> storeIds);

    // their org rows + their legacy org-NULL rows. SUPER callers use findScoped (whole org) instead.
    @Query("select s from Sell s where s.userId = :userId "
         + "and (s.organizationId = :orgId or s.organizationId is null) order by s.sellId desc")
    List<Sell> findOwnScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * UI/UX P3 — quick-pick tiles: this SHOP's best sellers by units over a recent window.
     *
     * <p>Row = [productId, sum(quantity)], highest first. Sums {@code quantity}, not revenue: the tiles
     * exist to save keystrokes on the items rung most OFTEN, which is a count of units, not of money.
     * A single expensive sale would otherwise push a rarely-touched product onto the grid.
     *
     * <p><b>Scoped by ORG, deliberately not by user.</b> {@code SellRepository.topSellingItems} — the one
     * the dashboard uses — groups by {@code userId}, which is right for "my performance" and wrong for a
     * till: a shared counter would show each cashier a different grid, and a newly hired one an empty
     * grid on their first shift. What belongs on a till is what the SHOP sells.
     *
     * <p>Store-aware on the same terms as {@link #findScopedByStores}: a caller with store grants sees
     * their stores plus legacy store-NULL rows, so a branch that sells different lines gets its own
     * tiles. Pass a null/empty {@code storeIds} to span the whole org — handled by the caller, since JPQL
     * {@code IN} cannot take an empty collection.
     */
    @Query("select s.productId, sum(s.quantity) from Sell s "
         + "where s.productId is not null and s.dated >= :since "
         + "and (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
         + "group by s.productId order by sum(s.quantity) desc")
    List<Object[]> topProductsScoped(@Param("since") LocalDateTime since,
                                     @Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);

    /** Store-aware variant of {@link #topProductsScoped}, for a caller who holds store grants. */
    @Query("select s.productId, sum(s.quantity) from Sell s "
         + "where s.productId is not null and s.dated >= :since "
         + "and s.organizationId = :orgId and (s.storeId in :storeIds or s.storeId is null) "
         + "group by s.productId order by sum(s.quantity) desc")
    List<Object[]> topProductsByStores(@Param("since") LocalDateTime since, @Param("orgId") Long orgId,
                                       @Param("storeIds") java.util.Collection<Long> storeIds, Pageable pageable);

    // Multi-rate tax: output taxable + tax grouped by rate over [from,to], tenant-scoped. Only tax-bearing lines
    // (taxRate set) — legacy pre-tax sells are excluded. Voided lines are deleted + returns reduce the amounts in
    // place, so this reflects the net current taxable per rate. Row = [rate, sum(totalAmount), sum(taxAmount)].
    @Query("select s.taxRate, sum(s.totalAmount), sum(s.taxAmount) from Sell s "
         + "where s.dated between :from and :to and s.taxRate is not null "
         + "and (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
         + "group by s.taxRate")
    List<Object[]> taxBreakdownByRate(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to,
                                      @Param("orgId") Long orgId, @Param("userId") Long userId);

    // All line items of one invoice (customer_history), tenant-scoped — used to load a sale for editing
    // so an invoice is never truncated by the report's pagination/recent-N cap.
    @Query("select s from Sell s where s.customerHistory.customer_history_id = :chId "
         + "and (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
         + "order by s.sellId asc")
    List<Sell> findByInvoiceScoped(@Param("chId") Long chId, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT s FROM Sell s WHERE s.dated >= :sd "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    public List<Sell> findSellByStartDate(@Param("sd") LocalDateTime sd, @Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query("SELECT s FROM Sell s WHERE s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    public List<Sell> findSellByEndDate(@Param("ed") LocalDateTime ed, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // @EntityGraph(attributePaths = {"stock", "customerHistory", "customerHistory.customer"})
    /**
     * The 6-month trend, grouped in SQL: {@code [year, month, count, revenue]} per month.
     *
     * <p>Replaces loading every {@code Sell} of the last six months and bucketing them with a
     * {@code Map.merge} loop. That read is why {@code /getDashboardChartData} answered in ~2 seconds.
     *
     * <p><b>{@code dated}, not {@code updated}.</b> {@code dated} is {@code @Column(updatable=false)} — when
     * the sale happened, fixed for the life of the row. {@code updated} moves every time the row is touched,
     * so filtering a monthly report by it means an edited old invoice silently leaves its month and reappears
     * in the current one. See the note on {@link #findSellByDates}.
     *
     * <p><b>{@code totalAmount}</b>, deliberately: the sibling stats endpoint sums {@code netAmount} for its
     * revenue figure while the trend chart has always used {@code totalAmount}. Quietly harmonising them
     * would change a number on a chart somebody reads, with nothing announcing it — so the difference is
     * preserved and flagged rather than tidied away.
     */
    @Query("SELECT year(s.dated), month(s.dated), count(s), coalesce(sum(s.totalAmount), 0) FROM Sell s "
         + "WHERE s.dated >= :sd AND s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
         + "GROUP BY year(s.dated), month(s.dated)")
    List<Object[]> monthlyTrendScoped(@Param("sd") LocalDateTime sd, @Param("ed") LocalDateTime ed,
                                      @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Revenue per DAY of a period: {@code [dayOfMonth, revenue]}. Same column and same measure as the trend. */
    @Query("SELECT day(s.dated), coalesce(sum(s.totalAmount), 0) FROM Sell s "
         + "WHERE s.dated >= :sd AND s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
         + "GROUP BY day(s.dated)")
    List<Object[]> dailyRevenueScoped(@Param("sd") LocalDateTime sd, @Param("ed") LocalDateTime ed,
                                      @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Top products by quantity over a period: {@code [productId, qty]}, ordered, limited by {@code Pageable}.
     *
     * <p>Deliberately NOT {@link #topProductsScoped}, which looks like the same query and is not: that one
     * filters on {@code dated} and takes an open-ended {@code since}. This endpoint has always bucketed by
     * a closed month, where {@link #topProductsScoped} takes an open-ended {@code since}. Both now filter
     * {@code dated}; the range shape is what still separates them.
     */
    @Query("SELECT s.productId, coalesce(sum(s.quantity), 0) FROM Sell s "
         + "WHERE s.productId is not null AND s.dated >= :sd AND s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId)) "
         + "GROUP BY s.productId ORDER BY sum(s.quantity) desc")
    List<Object[]> topProductsInRange(@Param("sd") LocalDateTime sd, @Param("ed") LocalDateTime ed,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId,
                                        Pageable pageable);

    /**
     * The dashboard's two numbers for a period — count and revenue — computed in SQL.
     *
     * <p>Replaces loading every {@code Sell} in the range and running {@code .size()} and a
     * {@code mapToDouble().sum()} over it in Java. At the volumes this was written against that is a thousand
     * entities hydrated to produce two numbers.
     *
     * <p>Returns ONE row: {@code [count, revenue]}. {@code coalesce} so an empty period answers 0 rather than
     * a null that the caller would have to remember to handle — the previous code summed an empty stream to
     * 0.0, and that behaviour is preserved rather than quietly changed.
     *
     * <p><b>Same predicate and same column as {@link #findSellByDates}</b> — {@code dated}. Two queries
     * feeding one screen from different columns is exactly the kind of difference nobody notices until a
     * figure is questioned.
     */
    @Query("SELECT count(s), coalesce(sum(s.netAmount), 0) FROM Sell s WHERE s.dated >= :sd AND s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    Object[] sumSellByDates(@Param("sd") LocalDateTime sd, @Param("ed") LocalDateTime ed,
                            @Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Sales in a date range — the read behind the Sale Detail Report and the dashboard's period figures.
     *
     * <h3>{@code dated}, corrected 2026-08-26</h3>
     * This filtered {@code updated} — the column that moves every time a row is touched — so an invoice
     * edited months later silently left its own month and reappeared in the current one. {@code dated} is
     * {@code @Column(updatable=false)}: when the sale happened, and immutable by construction.
     *
     * <p>The report it serves was already inconsistent with itself: {@link #findSellByStartDate} and
     * {@link #findSellByEndDate} both filter {@code dated}, so on the same screen a start-and-end range
     * meant one thing and a start-only range meant another. All three now agree.
     *
     * <p>No figure moved when this changed: at the time, zero rows in the table had {@code dated} and
     * {@code updated} in different months — though 31 had been edited, so it was latency of luck rather
     * than of design. That made it the cheapest possible moment to correct it.
     */
    @Query("SELECT s FROM Sell s WHERE s.dated >= :sd AND s.dated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    List<Sell> findSellByDates(
        @Param("sd") LocalDateTime sd,
        @Param("ed") LocalDateTime ed,
        @Param("orgId") Long orgId,
        @Param("userId") Long userId
    );

    // M3c.4f (slice 88): the product_id backfill-from-stock queries were retired with the local Stock table.
    // The historical backfill ran at Flyway time (V5/V6) before the drop; nothing references local Stock anymore.
//    @Query(value = "SELECT * FROM appointment a,patient p WHERE a.FK_doctor_id = :doctor_id AND a.date = :date AND "
//    		+ "p.mobile = :mobile AND a.FK_patient_id = p.patient_id",nativeQuery=true)
//    Optional<Appointment> isPatientAppointed(@Param("doctor_id") Long doctor_id, @Param("date") String date, @Param("mobile") String mobile);
//    
//    @Query(value = "SELECT * FROM appointment t where t.FK_patient_id = :patient_id",nativeQuery=true)
//    public Optional<Appointment> findByPatient(@Param("patient_id") Long patient_id);
//
//    @Query(value = "SELECT * FROM appointment t where t.FK_doctor_id = :doctor_id",nativeQuery=true)
//    List<Appointment> findByDoctor(Long doctor_id);
//
//    @Query(value = "SELECT * FROM appointment a WHERE a.FK_hospital_id =:FK_hospital_id AND a.FK_doctor_id = :doctor_id AND a.date = :date"
//    		+" ORDER BY a.patients_appointed DESC LIMIT 1",nativeQuery=true)
//    Appointment getLastAppointment(Long FK_hospital_id, Long doctor_id, String date);
}
