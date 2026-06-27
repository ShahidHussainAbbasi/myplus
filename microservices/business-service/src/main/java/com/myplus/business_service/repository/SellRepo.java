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
    // their org rows + their legacy org-NULL rows. SUPER callers use findScoped (whole org) instead.
    @Query("select s from Sell s where s.userId = :userId "
         + "and (s.organizationId = :orgId or s.organizationId is null) order by s.sellId desc")
    List<Sell> findOwnScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

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
    @Query("SELECT s FROM Sell s WHERE s.updated >= :sd AND s.updated <= :ed "
         + "AND (s.organizationId = :orgId or (s.organizationId is null and s.userId = :userId))")
    List<Sell> findSellByDates(
        @Param("sd") LocalDateTime sd,
        @Param("ed") LocalDateTime ed,
        @Param("orgId") Long orgId,
        @Param("userId") Long userId
    );

    // M3c.1 (slice 76): backfill product_id onto historical (Stock-linked) sells from the item→product map, so the
    // Stock FK can later be retired. Idempotent (only NULL product_id rows); tenant-scoped (NULL-fallback). Run after
    // /migrate-catalog (which maps every item) for full coverage.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE sell s JOIN stock st ON s.stock_id = st.stock_id "
         + "JOIN item_catalog_map m ON m.item_id = st.item_id "
         + "SET s.product_id = m.product_id "
         + "WHERE s.product_id IS NULL AND s.stock_id IS NOT NULL "
         + "AND (s.organization_id = :orgId OR (s.organization_id IS NULL AND s.user_id = :userId))", nativeQuery = true)
    int backfillProductIds(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM sell s WHERE s.product_id IS NULL AND s.stock_id IS NOT NULL "
         + "AND (s.organization_id = :orgId OR (s.organization_id IS NULL AND s.user_id = :userId))", nativeQuery = true)
    long countWithoutProductId(@Param("orgId") Long orgId, @Param("userId") Long userId);
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
