package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.PayableSnapshot;
import com.myplus.finance.entity.PayableDoc;
import com.myplus.finance.repository.PayableDocRepository;

/** FP-1 — snapshots are idempotent, newest-wins, and status comes from the figures. */
class PayableServiceTest {

    private final PayableDocRepository repo = mock(PayableDocRepository.class);
    private final com.myplus.finance.repository.PayableNoteRepository notes = mock(com.myplus.finance.repository.PayableNoteRepository.class);
    private final PayableService svc = new PayableService(repo, mock(GlService.class), notes);

    private static PayableSnapshot snap(long version, String amount, String paid, boolean voided) {
        return PayableSnapshot.builder().source("PURCHASE").sourceRef("42").sourceVersion(version)
                .partyType("VENDOR").partyId(7L).partyName("ABC").amount(new BigDecimal(amount))
                .paid(new BigDecimal(paid)).voided(voided).build();
    }

    @Test @DisplayName("a new document is stored OPEN with what is still owed")
    void create_open() {
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "PURCHASE", "42")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(svc.applyOne(6L, snap(1, "100", "0", false))).isTrue();
        verify(repo).saveAndFlush(org.mockito.ArgumentMatchers.argThat(d ->
                PayableDoc.OPEN.equals(d.getStatus()) && d.open().compareTo(new BigDecimal("100")) == 0));
    }

    @Test @DisplayName("⭐ an OLDER snapshot is ignored — a late redelivery never overwrites newer figures")
    void older_ignored() {
        PayableDoc held = new PayableDoc();
        held.setSourceVersion(5L);
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "PURCHASE", "42")).thenReturn(Optional.of(held));
        assertThat(svc.applyOne(6L, snap(4, "100", "0", false))).isFalse();
        verify(repo, never()).saveAndFlush(any());
    }

    @Test @DisplayName("the same version again (a replay) updates in place — idempotent, no second row")
    void replay_same_version() {
        PayableDoc held = new PayableDoc();
        held.setSourceVersion(5L);
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "PURCHASE", "42")).thenReturn(Optional.of(held));
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(svc.applyOne(6L, snap(5, "100", "60", false))).isTrue();
        assertThat(held.getPaid()).isEqualByComparingTo("60");
        assertThat(held.open()).isEqualByComparingTo("40");
    }

    @Test @DisplayName("status from the figures: paid in full → SETTLED, overpaid never goes negative, voided → VOID")
    void status_rules() {
        assertThat(PayableService.statusOf(false, new BigDecimal("100"), new BigDecimal("100"))).isEqualTo("SETTLED");
        assertThat(PayableService.statusOf(false, new BigDecimal("100"), new BigDecimal("150"))).isEqualTo("SETTLED");
        assertThat(PayableService.statusOf(true, new BigDecimal("100"), BigDecimal.ZERO)).isEqualTo("VOID");
        PayableDoc v = new PayableDoc();
        v.setAmount(new BigDecimal("100"));
        v.setPaid(BigDecimal.ZERO);
        v.setStatus(PayableDoc.VOID);
        assertThat(v.open()).as("a void owes nothing").isEqualByComparingTo("0");
    }

    @Test @DisplayName("FP-4a: an accepted snapshot REPLACES the note trail; an older one leaves it; null leaves it")
    void notes_replaced_under_the_version_guard() {
        PayableDoc held = new PayableDoc();
        held.setId(11L);
        held.setSourceVersion(5L);
        when(repo.findByOrganizationIdAndSourceAndSourceRef(6L, "PURCHASE", "42")).thenReturn(Optional.of(held));
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        PayableSnapshot withNote = snap(6, "70", "0", false);
        withNote.setIssuedAmount(new BigDecimal("100"));
        withNote.setNotes(java.util.List.of(com.myplus.finance.dto.PayableNote.builder()
                .noteNo("DN-1").amount(new BigDecimal("30")).build()));
        assertThat(svc.applyOne(6L, withNote)).isTrue();
        assertThat(held.getIssuedAmount()).isEqualByComparingTo("100");
        verify(notes).deleteByDoc(11L);
        verify(notes).save(org.mockito.ArgumentMatchers.argThat(n -> "DN-1".equals(n.getNoteNo())
                && n.getAmount().compareTo(new BigDecimal("30")) == 0 && n.getPayableDocId() == 11L));

        org.mockito.Mockito.clearInvocations(notes);
        assertThat(svc.applyOne(6L, snap(7, "70", "10", false))).as("notes null = an older sender").isTrue();
        verify(notes, never()).deleteByDoc(any());

        assertThat(svc.applyOne(6L, withNote)).as("version 6 < 7 held").isFalse();
        verify(notes, never()).deleteByDoc(any());
    }
}
