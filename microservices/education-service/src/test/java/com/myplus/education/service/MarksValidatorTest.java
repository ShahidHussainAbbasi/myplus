package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Slice 1.3 (D3) — the marks validation matrix: bounds × absent × blank.
 *
 * Pure, so it runs on every {@code mvn test} with no database and no Docker, and it enumerates
 * combinations a browser test would never reach. The Cypress gate proves the rule is WIRED IN;
 * this proves the rule is RIGHT.
 */
class MarksValidatorTest {

    private static final Integer MAX = 50;

    @Test
    void a_mark_inside_the_range_is_valid() {
        assertThat(MarksValidator.validate(0, false, MAX)).isNull();
        assertThat(MarksValidator.validate(25, false, MAX)).isNull();
        assertThat(MarksValidator.validate(50, false, MAX)).as("the maximum itself is attainable").isNull();
    }

    @Test
    void a_mark_above_the_maximum_is_refused_and_names_both_numbers() {
        String r = MarksValidator.validate(105, false, MAX);
        assertThat(r).isNotNull();
        assertThat(r).contains("105").contains("50");
    }

    @Test
    void negative_marks_are_refused() {
        assertThat(MarksValidator.validate(-1, false, MAX)).isNotNull();
    }

    @Test
    void absent_with_no_marks_is_valid() {
        assertThat(MarksValidator.validate(null, true, MAX)).isNull();
    }

    @Test
    void absent_WITH_marks_is_contradictory_and_refused() {
        // D2: silently dropping one of the two would decide for the teacher which they meant.
        String r = MarksValidator.validate(30, true, MAX);
        assertThat(r).isNotNull();
        assertThat(r).containsIgnoringCase("absent");
    }

    @Test
    void a_blank_row_is_not_an_error() {
        // "Not marked yet" is legitimate — a teacher may be saving a partial sheet. Forcing a value
        // would push them to type 0, which D2 says means something else entirely.
        assertThat(MarksValidator.validate(null, false, MAX)).isNull();
    }

    @Test
    void a_paper_with_no_maximum_accepts_any_non_negative_mark() {
        // maxMarks is nullable on ExamPaper, so the validator must not assume one exists.
        assertThat(MarksValidator.validate(999, false, null)).isNull();
        assertThat(MarksValidator.validate(-1, false, null)).as("negative is still wrong").isNotNull();
    }

    @Test
    void absent_beats_the_range_check() {
        // An absent student has no mark to be out of range, even against a zero-max paper.
        assertThat(MarksValidator.validate(null, true, 0)).isNull();
    }

    @Test
    void hasContent_distinguishes_a_real_entry_from_an_untouched_row() {
        assertThat(MarksValidator.hasContent(null, false, null)).as("untouched").isFalse();
        assertThat(MarksValidator.hasContent(null, false, "   ")).as("whitespace is not a remark").isFalse();
        assertThat(MarksValidator.hasContent(0, false, null)).as("zero IS an entry").isTrue();
        assertThat(MarksValidator.hasContent(null, true, null)).as("absent IS an entry").isTrue();
        assertThat(MarksValidator.hasContent(null, false, "sick note")).as("a remark alone counts").isTrue();
    }
}
