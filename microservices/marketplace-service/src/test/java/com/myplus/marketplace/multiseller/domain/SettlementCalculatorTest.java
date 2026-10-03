package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("[MKT-R15.5] the source's worked example: 5,000 − 500 − 200 − 50 − 100 = 4,150, and it reconciles")
    void sourceWorkedExample() {
        SettlementCalculator.Breakdown b = SettlementCalculator.calculate(new SettlementCalculator.Inputs(
                n("5000"), CommissionPolicy.fixed(n("500")), n("200"), n("50"), null, n("100"), null));
        assertThat(b.merchantPayable()).isEqualByComparingTo("4150.00");
        assertThat(b.reconciles()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R15.5] a 10% items commission excludes the delivery fee: 5,000 with 200 delivery → 480")
    void percentOfItems() {
        SettlementCalculator.Breakdown b = SettlementCalculator.calculate(new SettlementCalculator.Inputs(
                n("5000"), CommissionPolicy.percentOfItems(n("0.10")), n("200"), n("50"), null, n("100"), null));
        assertThat(b.commission()).isEqualByComparingTo("480.00");
        assertThat(b.merchantPayable()).isEqualByComparingTo("4170.00");
        assertThat(b.reconciles()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R15.5] a fractional rate is applied at full precision (12.5%, not 13%)")
    void fractionalRateNotRoundedFirst() {
        SettlementCalculator.Breakdown b = SettlementCalculator.calculate(new SettlementCalculator.Inputs(
                n("1000"), CommissionPolicy.percentOfItems(n("0.125")), null, null, null, null, null));
        assertThat(b.commission()).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("[MKT-R15.5] every amount reconciles to the paisa across awkward inputs")
    void reconcilesEverywhere() {
        String[] amounts = {"0.01", "0.99", "1", "333.33", "999.99", "12345.67"};
        String[] rates = {"0", "0.03", "0.075", "0.1", "0.15", "1"};
        for (String a : amounts) {
            for (String r : rates) {
                SettlementCalculator.Breakdown b = SettlementCalculator.calculate(new SettlementCalculator.Inputs(
                        n(a), CommissionPolicy.percentOfItems(n(r)), null, null, null, null, null));
                assertThat(b.reconciles()).as("amount %s rate %s", a, r).isTrue();
                assertThat(b.merchantPayable().scale()).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("[MKT-R15.5] deductions larger than the sale are refused, never paid as a negative")
    void negativePayableRefused() {
        assertThatThrownBy(() -> SettlementCalculator.calculate(new SettlementCalculator.Inputs(
                n("100"), CommissionPolicy.fixed(n("90")), n("20"), null, null, null, null)))
                .isInstanceOf(MarketplaceRuleException.class)
                .extracting(e -> ((MarketplaceRuleException) e).code()).isEqualTo("NEGATIVE_PAYABLE");
    }

    @Test
    @DisplayName("[MKT-R7.4] a commission rate outside 0–100% is refused")
    void invalidRate() {
        assertThatThrownBy(() -> CommissionPolicy.percentOfItems(n("1.5")))
                .isInstanceOf(MarketplaceRuleException.class);
        assertThatThrownBy(() -> CommissionPolicy.percentOfItems(n("-0.01")))
                .isInstanceOf(MarketplaceRuleException.class);
    }
}
