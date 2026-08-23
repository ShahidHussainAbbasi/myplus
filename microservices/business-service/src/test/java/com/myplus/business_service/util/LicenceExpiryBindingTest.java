package com.myplus.business_service.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * The customer form's optional date fields, both halves of them.
 *
 * <p>Pure logic, no container — this runs on every {@code mvn test}, which matters because the bug it pins
 * down took out a core screen and nothing in the build noticed.
 *
 * <h3>What went wrong</h3>
 * {@code licenseExpiry} was fixed once, for BLANK: a form posts every input it owns, so an owner who never
 * touches the field submits {@code licenseExpiry=}, and strict ISO parsing of {@code ""} rejected every
 * customer save. The other half was missed. The input carries {@code class="datePicker"}, whose wire format
 * is {@code dd-MM-yyyy} by documented contract, while the binder still called the bare {@code LocalDate.parse}
 * — strict ISO. So a licence expiry that was actually filled in failed with
 * {@code Text '31-08-2030' could not be parsed at index 0}.
 *
 * <p>The lesson worth keeping: an optional field has TWO paths, and fixing the empty one is not fixing the
 * field.
 */
class LicenceExpiryBindingTest {

    private final AppUtil appUtil = new AppUtil();

    @Test
    void the_pickers_own_format_binds() {
        // dd-MM-yyyy — what /js/common/date-picker.js actually emits, and the exact value from the report.
        assertThat(appUtil.toLocalDateStrict("31-08-2030")).isEqualTo(LocalDate.of(2030, 8, 31));
    }

    @Test
    void iso_still_binds() {
        // Edit screens and HTML date inputs emit ISO; both are the contract, so both must bind.
        assertThat(appUtil.toLocalDateStrict("2030-08-31")).isEqualTo(LocalDate.of(2030, 8, 31));
    }

    @Test
    void blank_is_absent_not_an_error() {
        // The B2B-P3g case: an untouched optional field must not reject the save.
        assertThat(appUtil.toLocalDateStrict("")).isNull();
        assertThat(appUtil.toLocalDateStrict("   ")).isNull();
        assertThat(appUtil.toLocalDateStrict(null)).isNull();
    }

    @Test
    void a_typo_is_refused_rather_than_silently_dropped() {
        // The deliberate difference from toLocalDateOrNull. This licence is printed on the invoice as evidence
        // the buyer may be supplied; storing nothing and saying nothing is the worse failure.
        assertThatThrownBy(() -> appUtil.toLocalDateStrict("31/08/2030"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dd-MM-yyyy");

        assertThatThrownBy(() -> appUtil.toLocalDateStrict("not a date"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_lenient_sibling_still_swallows_by_design() {
        // Guards the split: the M3c.4b purchase path relies on a bad batch expiry NOT failing the purchase.
        // If someone ever "tidies" the two into one, this fails and says why.
        assertThatCode(() -> assertThat(appUtil.toLocalDateOrNull("31/08/2030")).isNull())
                .doesNotThrowAnyException();
        assertThat(appUtil.toLocalDateOrNull("31-08-2030")).isEqualTo(LocalDate.of(2030, 8, 31));
    }
}
