package com.myplus.finance.repository;

import com.myplus.finance.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** F3 (GL): journal entries (headers). Lines cascade from the entry; reads aggregate via JournalLineRepository. */
@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    /** EX-0b — the journal a document posted, for a reversal to mirror. Served by idx_je_org_source_ref (V8). */
    java.util.Optional<JournalEntry> findFirstByOrganizationIdAndSourceAndSourceRef(Long organizationId, String source, String sourceRef);

    /** EX-0b — has this document already been posted (or reversed)? Served by idx_je_org_source_ref (V8). */
    boolean existsByOrganizationIdAndSourceAndSourceRef(Long organizationId, String source, String sourceRef);
}
