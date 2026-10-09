package com.myplus.marketplace.multiseller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.marketplace.multiseller.domain.AcceptanceByValue.Tier;

/** MKT-2-06 — the acceptance window by the part's value (R10.6). */
class AcceptanceByValueTest {

    static Tier t(String above, int minutes) {
        return new Tier(new BigDecimal(above), minutes);
    }

    @Test
    @DisplayName("[MKT-R10.6] a part ABOVE a rule's amount gets its minutes; of several, the highest it is above; exactly the amount is not above")
    void minutesFor() {
        AcceptanceByValue v = AcceptanceByValue.of(List.of(t("500000", 30), t("100000", 15)));
        assertThat(v.minutesFor(new BigDecimal("52000"), 5)).isEqualTo(5);
        assertThat(v.minutesFor(new BigDecimal("100000"), 5)).as("exactly the amount").isEqualTo(5);
        assertThat(v.minutesFor(new BigDecimal("100000.01"), 5)).isEqualTo(15);
        assertThat(v.minutesFor(new BigDecimal("150000"), 5)).isEqualTo(15);
        assertThat(v.minutesFor(new BigDecimal("600000"), 5)).isEqualTo(30);
        assertThat(v.minutesFor(null, 5)).isEqualTo(5);
        assertThat(AcceptanceByValue.none().minutesFor(new BigDecimal("9999999"), 7)).as("no rules: the base").isEqualTo(7);
    }

    @Test
    @DisplayName("[MKT-R10.6] a rule may also SHORTEN the window: the rule is the operator's, not a floor")
    void shorter() {
        assertThat(AcceptanceByValue.of(List.of(t("100000", 2))).minutesFor(new BigDecimal("200000"), 10)).isEqualTo(2);
    }

    @Test
    @DisplayName("[MKT-R10.6] refused in a sentence: no amount, Rs 0, minutes outside 1–60, the same amount twice, more than 5 rules")
    void refused() {
        assertThatThrownBy(() -> AcceptanceByValue.of(List.of(new Tier(null, 10)))).hasMessage("Each rule needs an amount above Rs 0, up to Rs 100,000,000.");
        assertThatThrownBy(() -> AcceptanceByValue.of(List.of(t("0", 10)))).hasMessage("Each rule needs an amount above Rs 0, up to Rs 100,000,000.");
        assertThatThrownBy(() -> AcceptanceByValue.of(List.of(t("100000001", 10)))).hasMessage("Each rule needs an amount above Rs 0, up to Rs 100,000,000.");
        assertThatThrownBy(() -> AcceptanceByValue.of(List.of(t("1000", 0)))).hasMessage("Each rule's minutes are 1 to 60.");
        assertThatThrownBy(() -> AcceptanceByValue.of(List.of(t("1000", 61)))).hasMessage("Each rule's minutes are 1 to 60.");
        assertThatThrownBy(() -> AcceptanceByValue.of(List.of(t("100000", 10), t("100000.00", 20)))).hasMessage("Two rules have the same amount: Rs 100,000.");
        List<Tier> six = new ArrayList<>();
        for (int i = 1; i <= 6; i++) six.add(t(String.valueOf(i * 1000), 10));
        assertThatThrownBy(() -> AcceptanceByValue.of(six)).hasMessage("At most 5 rules.");
        assertThat(AcceptanceByValue.of(six.subList(0, 5)).tiers()).hasSize(5);
        assertThat(AcceptanceByValue.of(null).tiers()).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R10.6] stored and read back the same; an unreadable stored value reads as no rules (the base window)")
    void roundTrip() {
        AcceptanceByValue v = AcceptanceByValue.of(List.of(t("500000", 30), t("100000", 15)));
        assertThat(v.format()).isEqualTo("100000.00:15;500000.00:30");
        assertThat(AcceptanceByValue.parse(v.format())).isEqualTo(v);
        assertThat(AcceptanceByValue.parse("").tiers()).isEmpty();
        assertThat(AcceptanceByValue.parse(null).tiers()).isEmpty();
        assertThat(AcceptanceByValue.parse("abc").tiers()).isEmpty();
        assertThat(AcceptanceByValue.parse("100000:99").tiers()).as("out of range: refused, read as none").isEmpty();
        assertThat(AcceptanceByValue.none().format()).isEmpty();
    }
}
