package com.myplus.pharma.repository;

import com.myplus.pharma.entity.Prescription;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    // NOTE: every finder here MUST carry SCOPE. Unscoped derived queries (findByStatus / findByPatientName... /
    // findByUserId) were removed — they were unused, but any caller would have read across tenants.

    // P5 (slice 41): tenant-scoped, NULL-fallback per the multi-tenancy standard.
    String SCOPE = "(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))";

    /** Newest-first page. BOUNDED on purpose — the unpaged version returned every prescription the org had ever
     *  taken, and the list screen only ever shows the recent ones. */
    /** H4: the caller's branches (StoreScope) — a row with no branch is everyone's. */
    String BRANCH = " AND (:allStores = true OR p.storeId IS NULL OR p.storeId IN :stores)";

    /** RW-4b — how many prescriptions are WAITING for the pharmacy, with exactly the list's scope (tenant + branches). */
    @Query("SELECT COUNT(p) FROM Prescription p WHERE " + SCOPE + BRANCH + " AND p.status IN :statuses")
    long countScopedByStatus(@Param("orgId") Long orgId, @Param("userId") Long userId,
                             @Param("allStores") boolean allStores, @Param("stores") java.util.Collection<Long> stores,
                             @Param("statuses") java.util.Collection<Prescription.Status> statuses);

    @Query("SELECT p FROM Prescription p WHERE " + SCOPE + BRANCH + " ORDER BY p.createdAt DESC")
    List<Prescription> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                  @Param("allStores") boolean allStores, @Param("stores") java.util.Collection<Long> stores,
                                  Pageable pageable);

    /** HMS S4-lite — the pharmacist's search box, by text: patient name, phone or doctor. Newest first, paged. */
    @Query("SELECT p FROM Prescription p WHERE " + SCOPE + BRANCH + " AND ("
            + " LOWER(p.patientName) LIKE LOWER(CONCAT('%', :q, '%'))"
            + " OR p.patientPhone LIKE CONCAT('%', :q, '%')"
            + " OR LOWER(p.doctorName) LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY p.createdAt DESC")
    List<Prescription> searchScoped(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                    @Param("allStores") boolean allStores, @Param("stores") java.util.Collection<Long> stores,
                                    @Param("q") String q,
                                    Pageable pageable);

    /** HMS S4-lite — one person's prescriptions (a clinic token / MRN / phone resolved to its party). V8 indexes it. */
    @Query("SELECT p FROM Prescription p WHERE " + SCOPE + BRANCH + " AND p.partyId = :partyId ORDER BY p.createdAt DESC")
    List<Prescription> findByPartyScoped(@Param("orgId") Long orgId, @Param("userId") Long userId,
                                         @Param("allStores") boolean allStores, @Param("stores") java.util.Collection<Long> stores,
                                         @Param("partyId") Long partyId, Pageable pageable);

    /** HMS S3b-1 — the prescription a doctor's Submit already made (uq_rx_external_ref). */
    java.util.Optional<Prescription> findByOrganizationIdAndExternalRef(Long organizationId, String externalRef);

    /** Party bridge: stamp ONLY party_id (targeted — never a full-entity save, which could clobber other columns). */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "update prescriptions set party_id = :partyId where id = :id", nativeQuery = true)
    void updatePartyId(@Param("id") Long id, @Param("partyId") Long partyId);

    @Query("SELECT p FROM Prescription p WHERE p.id = :id AND " + SCOPE)
    Optional<Prescription> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId, @Param("userId") Long userId);

    // P4 contact-view backfill: already-bridged rows, walked by an id cursor so the admin job can resume in batches.
    @Query("SELECT p FROM Prescription p WHERE p.partyId IS NOT NULL AND p.id > :afterId AND " + SCOPE + " ORDER BY p.id ASC")
    List<Prescription> findBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId,
                                        @Param("userId") Long userId, Pageable pageable);

    @Query("SELECT COUNT(p) FROM Prescription p WHERE p.partyId IS NOT NULL AND p.id > :afterId AND " + SCOPE)
    long countBridgedAfter(@Param("afterId") Long afterId, @Param("orgId") Long orgId, @Param("userId") Long userId);
}
