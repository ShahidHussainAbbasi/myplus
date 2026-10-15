package com.myplus.clinical.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.clinical.entity.EncounterRxItem;

/** HMS S3b-1 — the doctor's prescription lines of a visit. Every read and the delete are org-scoped. */
public interface EncounterRxItemRepo extends JpaRepository<EncounterRxItem, Long> {

    List<EncounterRxItem> findByOrganizationIdAndEncounterIdOrderByLineNo(Long organizationId, Long encounterId);

    /** Save replaces the list as a whole (the screen sends every line it shows). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM EncounterRxItem i WHERE i.organizationId = :org AND i.encounterId = :encounterId")
    int deleteForEncounter(@Param("org") Long org, @Param("encounterId") Long encounterId);
}
