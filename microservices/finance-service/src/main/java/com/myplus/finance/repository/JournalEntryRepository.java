package com.myplus.finance.repository;

import com.myplus.finance.entity.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** F3 (GL): journal entries (headers). Lines cascade from the entry; reads aggregate via JournalLineRepository. */
@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
}
