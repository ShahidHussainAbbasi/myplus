package com.myplus.finance.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.finance.entity.PayableNoteRow;

/** FP-4a — debit notes per payable document (idx_payable_note_doc). */
public interface PayableNoteRepository extends JpaRepository<PayableNoteRow, Long> {

    List<PayableNoteRow> findByPayableDocIdIn(Collection<Long> docIds);

    @Modifying
    @Query("delete from PayableNoteRow n where n.payableDocId = :docId")
    int deleteByDoc(@Param("docId") Long docId);
}
