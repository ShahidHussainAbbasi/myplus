package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.finance.dto.JournalLineDTO;

/** FP-6a — the direction of the alignment journal (difference = GL 2000 − supplier ledger). */
class PayablesLedgerAlignmentTest {

    @Test
    @DisplayName("GL above the ledger (org 41: 288,000 vs −25,200) → Dr 2000 / Cr 2990 by 313,200, balanced")
    void glAbove() {
        List<JournalLineDTO> l = PayablesLedgerAlignment.lines(new BigDecimal("313200"));
        assertThat(l).hasSize(2);
        assertThat(l.get(0).getAccountCode()).isEqualTo("2000");
        assertThat(l.get(0).getDebit()).isEqualByComparingTo("313200");
        assertThat(l.get(1).getAccountCode()).isEqualTo("2990");
        assertThat(l.get(1).getCredit()).isEqualByComparingTo("313200");
    }

    @Test
    @DisplayName("GL below the ledger → Dr 2990 / Cr 2000, by the absolute difference")
    void glBelow() {
        List<JournalLineDTO> l = PayablesLedgerAlignment.lines(new BigDecimal("-40.50"));
        assertThat(l.get(0).getAccountCode()).isEqualTo("2990");
        assertThat(l.get(0).getDebit()).isEqualByComparingTo("40.50");
        assertThat(l.get(1).getAccountCode()).isEqualTo("2000");
        assertThat(l.get(1).getCredit()).isEqualByComparingTo("40.50");
    }
}
