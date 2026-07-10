package com.myplus.finance.service;

import com.myplus.finance.dto.JournalLineDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** F3 (GL): the pure double-entry rule — balanced journals pass, everything else is rejected. No Spring/DB. */
class GlValidateTest {

    private static JournalLineDTO dr(String amt) {
        return JournalLineDTO.builder().accountId(1L).debit(new BigDecimal(amt)).build();
    }
    private static JournalLineDTO cr(String amt) {
        return JournalLineDTO.builder().accountId(2L).credit(new BigDecimal(amt)).build();
    }

    @Test void balancedTwoLineJournalPasses() {
        assertDoesNotThrow(() -> GlService.validate(List.of(dr("100.00"), cr("100.00"))));
    }

    @Test void balancedMultiLineJournalPasses() {
        // Dr AR 110 ; Cr Sales 100 + Cr Tax 10
        assertDoesNotThrow(() -> GlService.validate(List.of(dr("110.00"), cr("100.00"), cr("10.00"))));
    }

    @Test void unbalancedIsRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () -> GlService.validate(List.of(dr("100"), cr("90"))));
        assertTrue(ex.getMessage().toLowerCase().contains("unbalanced"));
    }

    @Test void singleLineIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GlService.validate(List.of(dr("100"))));
    }

    @Test void lineWithBothDebitAndCreditIsRejected() {
        var bad = JournalLineDTO.builder().accountId(1L).debit(new BigDecimal("50")).credit(new BigDecimal("50")).build();
        assertThrows(IllegalArgumentException.class, () -> GlService.validate(List.of(bad, cr("50"))));
    }

    @Test void negativeAmountIsRejected() {
        var bad = JournalLineDTO.builder().accountId(1L).debit(new BigDecimal("-100")).build();
        assertThrows(IllegalArgumentException.class, () -> GlService.validate(List.of(bad, cr("-100"))));
    }

    @Test void zeroTotalIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GlService.validate(List.of(dr("0"), cr("0"))));
    }
}
