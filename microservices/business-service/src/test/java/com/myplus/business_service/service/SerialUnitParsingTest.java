package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SER-2 — how the submitted serial text becomes a list of units.
 *
 * <p>Pure logic, so it runs on every {@code mvn test} rather than only against a deployed stack. It is worth
 * testing on its own because the parsing is not a free choice: the serials arrive as ONE parameter because the
 * monolith's purchase proxy keeps only the first value of a repeated one, so every unit a shop receives is
 * riding on this split being right.
 */
class SerialUnitParsingTest {

    @Test
    @DisplayName("one serial per line — the shape the textarea produces")
    void splits_on_newlines() {
        List<String> out = SerialUnitService.split("111\n222\n333");
        assertThat(out).containsExactly("111", "222", "333");
    }

    @Test
    @DisplayName("⭐ blank lines do not become units")
    void blank_lines_are_dropped() {
        /*
         * A trailing newline is unavoidable in a textarea, and an operator separating batches with a blank
         * line is normal. Counting either as a unit would make the serial count disagree with the quantity
         * and refuse a purchase that is perfectly correct — the refusal would be real, and the reason
         * invisible on screen.
         */
        List<String> out = SerialUnitService.split("111\n\n222\n\r\n  \n333\n");
        assertThat(out).containsExactly("111", "222", "333");
    }

    @Test
    @DisplayName("commas work too — a paste from a supplier's spreadsheet")
    void splits_on_commas() {
        // The textarea is line-based, but a list pasted from a spreadsheet arrives comma-separated. Refusing
        // that would be a rule with no purpose behind it.
        assertThat(SerialUnitService.split("111,222 , 333")).containsExactly("111", "222", "333");
    }

    @Test
    @DisplayName("serials are normalised, so the same handset cannot be recorded twice")
    void normalises_case_and_space() {
        /*
         * The uniqueness guarantee is a database index on the stored value. Without normalising, "a1b2" and
         * "A1B2 " are two different units to that index and the shop believes it owns the same handset twice.
         * Case matters because a serial is transcribed off a box as often as it is scanned.
         */
        assertThat(SerialUnitService.split(" a1b2 \n A1B2")).containsExactly("A1B2", "A1B2");
        assertThat(SerialUnitService.normalise("  imei-9  ")).isEqualTo("IMEI-9");
    }

    @Test
    @DisplayName("nothing supplied is an empty list, never null")
    void empty_input_is_an_empty_list() {
        // The caller loops over the result on the ordinary path — most products in most shops have no serial
        // — so returning null here would put an NPE on the commonest purchase in the product.
        assertThat(SerialUnitService.split(null)).isEmpty();
        assertThat(SerialUnitService.split("   ")).isEmpty();
        assertThat(SerialUnitService.normalise(null)).isNull();
    }
}
