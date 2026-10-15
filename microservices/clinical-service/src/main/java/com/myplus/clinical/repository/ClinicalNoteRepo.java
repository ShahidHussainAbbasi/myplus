package com.myplus.clinical.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.clinical.entity.ClinicalNote;

/** HMS S3a — the doctor's notes. Append-only: this repository is only ever used to INSERT and to read. */
public interface ClinicalNoteRepo extends JpaRepository<ClinicalNote, Long> {

    @Query("select n from ClinicalNote n where n.organizationId = :org and n.encounterId in :encounters order by n.id")
    List<ClinicalNote> forEncounters(@Param("org") Long org, @Param("encounters") Collection<Long> encounters);
}
