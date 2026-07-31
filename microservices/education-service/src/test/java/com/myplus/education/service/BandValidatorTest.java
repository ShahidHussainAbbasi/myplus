package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.myplus.education.entity.GradeBand;

import org.junit.jupiter.api.Test;

/**
 * Slice 1.4 (D5) — a grading scale is validated as a SET, because a band is only correct relative to its
 * neighbours. Pure, so the whole matrix runs on `mvn test`.
 */
class BandValidatorTest {

    private static GradeBand band(String name, int min, int max) {
        return GradeBand.builder().name(name).minPercent(min).maxPercent(max)
                .userId(1L).organizationId(1L).build();
    }

    private static List<GradeBand> validScale() {
        return Arrays.asList(band("F", 0, 32), band("C", 33, 59), band("B", 60, 79), band("A", 80, 100));
    }

    @Test
    void a_contiguous_scale_covering_0_to_100_is_valid() {
        assertThat(BandValidator.validateSet(validScale())).isEmpty();
    }

    @Test
    void an_EMPTY_scale_is_valid() {
        // D2: a school that has not configured grading keeps working — marks still show a percentage.
        // Refusing this would force every tenant to set grading up before they could use marks at all.
        assertThat(BandValidator.validateSet(Collections.emptyList())).isEmpty();
        assertThat(BandValidator.validateSet(null)).isEmpty();
    }

    @Test
    void overlapping_bands_are_refused_and_both_are_named() {
        List<GradeBand> overlap = Arrays.asList(band("F", 0, 79), band("B", 60, 100));
        List<String> problems = BandValidator.validateSet(overlap);
        assertThat(problems).isNotEmpty();
        assertThat(problems.get(0)).contains("F").contains("B").contains("overlap");
    }

    @Test
    void a_gap_is_refused_and_the_uncovered_range_is_named() {
        List<GradeBand> gap = Arrays.asList(band("F", 0, 32), band("A", 40, 100));
        List<String> problems = BandValidator.validateSet(gap);
        assertThat(problems).isNotEmpty();
        // The message must say WHICH percentages nobody grades — "invalid scale" would not help.
        assertThat(problems.get(0)).contains("33").contains("39");
    }

    @Test
    void a_scale_not_starting_at_zero_is_refused() {
        assertThat(BandValidator.validateSet(Arrays.asList(band("C", 33, 59), band("A", 60, 100))))
                .isNotEmpty();
    }

    @Test
    void a_scale_not_ending_at_100_is_refused() {
        assertThat(BandValidator.validateSet(Arrays.asList(band("F", 0, 32), band("C", 33, 90))))
                .isNotEmpty();
    }

    @Test
    void a_single_band_covering_everything_is_valid() {
        // Unusual but coherent: pass/fail-only schools exist.
        assertThat(BandValidator.validateSet(Collections.singletonList(band("Pass", 0, 100)))).isEmpty();
    }

    @Test
    void an_inverted_band_is_refused() {
        assertThat(BandValidator.validateSet(Collections.singletonList(band("Broken", 80, 20)))).isNotEmpty();
    }

    @Test
    void out_of_range_percentages_are_refused() {
        assertThat(BandValidator.validateSet(Collections.singletonList(band("Silly", 0, 150)))).isNotEmpty();
        assertThat(BandValidator.validateSet(Collections.singletonList(band("Silly", -5, 100)))).isNotEmpty();
    }

    @Test
    void a_band_without_a_name_is_refused() {
        assertThat(BandValidator.validateSet(Collections.singletonList(band("  ", 0, 100)))).isNotEmpty();
    }

    @Test
    void a_band_missing_its_bounds_is_refused_without_throwing() {
        GradeBand b = GradeBand.builder().name("Half").minPercent(0).maxPercent(null).userId(1L).build();
        assertThat(BandValidator.validateSet(Collections.singletonList(b))).isNotEmpty();
    }

    @Test
    void negative_gpa_points_are_refused() {
        GradeBand b = band("A", 0, 100);
        b.setGpaPoints(-1.0);
        assertThat(BandValidator.validateSet(Collections.singletonList(b))).isNotEmpty();
    }

    @Test
    void input_order_does_not_matter() {
        // The scale is validated after sorting, so an owner adding "A" before "F" is not an error.
        List<GradeBand> shuffled = Arrays.asList(band("A", 80, 100), band("F", 0, 32),
                band("B", 60, 79), band("C", 33, 59));
        assertThat(BandValidator.validateSet(shuffled)).isEmpty();
    }
}
