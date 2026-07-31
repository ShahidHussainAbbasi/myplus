package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.ExamPaper;
import com.myplus.education.entity.GradeBand;
import com.myplus.education.entity.Mark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 1.4 — percentage and band derivation, including the absent policy 1.3 deliberately deferred here.
 *
 * The DB is mocked away: what matters is the arithmetic and the policy branch, both of which a Cypress
 * test would exercise slowly and prove less about.
 */
@ExtendWith(MockitoExtension.class)
class GradingServiceTest {

    @Mock private com.myplus.education.repository.GradeBandRepository gradeBandRepository;
    @Mock private SettingsService settingsService;

    @InjectMocks private GradingService service;

    private static GradeBand band(String name, int min, int max) {
        return GradeBand.builder().name(name).minPercent(min).maxPercent(max).userId(1L).build();
    }

    private static List<GradeBand> scale() {
        return Arrays.asList(band("F", 0, 32), band("C", 33, 59), band("B", 60, 79), band("A", 80, 100));
    }

    private static ExamPaper paper(Integer maxMarks) {
        return ExamPaper.builder().examId(1L).subjectId(1L).maxMarks(maxMarks).userId(1L).build();
    }

    private static Mark mark(Integer obtained, boolean absent) {
        return Mark.builder().examPaperId(1L).studentEnrollNo("E1")
                .marksObtained(obtained).absent(absent).userId(1L).build();
    }

    // ── percentage ──────────────────────────────────────────────────────────────────────────────

    @Test
    void a_mark_becomes_a_percentage_of_its_paper() {
        lenient().when(settingsService.getBool(GradingService.ROUND_HALF_UP)).thenReturn(true);
        assertThat(service.percentFor(mark(37, false), paper(50))).isEqualTo(74.0);
    }

    @Test
    void an_unmarked_row_has_no_percentage_and_is_not_a_zero() {
        assertThat(service.percentFor(mark(null, false), paper(50))).isNull();
    }

    @Test
    void a_paper_with_no_maximum_yields_no_percentage() {
        assertThat(service.percentFor(mark(37, false), paper(null))).isNull();
        assertThat(service.percentFor(mark(37, false), paper(0))).as("no divide by zero").isNull();
    }

    // ── the absent policy (D3), the question 1.3 deferred to this slice ─────────────────────────

    @Test
    void absent_counts_as_zero_when_the_policy_is_ON() {
        when(settingsService.getBool(GradingService.ABSENT_AS_ZERO)).thenReturn(true);
        assertThat(service.percentFor(mark(null, true), paper(50))).isEqualTo(0.0);
    }

    @Test
    void absent_is_EXCLUDED_when_the_policy_is_OFF() {
        // null means the paper leaves BOTH sides of the average. Returning 0.0 here would be exactly the
        // bug the setting exists to prevent.
        when(settingsService.getBool(GradingService.ABSENT_AS_ZERO)).thenReturn(false);
        assertThat(service.percentFor(mark(null, true), paper(50))).isNull();
    }

    @Test
    void the_absent_policy_fails_to_counting_as_zero() {
        // If settings are unreachable, the honest default is that a missed paper still counts — a
        // flattering average over an empty set is the worse failure.
        when(settingsService.getBool(GradingService.ABSENT_AS_ZERO)).thenThrow(new RuntimeException("down"));
        assertThat(service.absentCountsAsZero()).isTrue();
    }

    // ── banding ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_percentage_resolves_to_its_band() {
        assertThat(service.bandFor(scale(), 74.0).getName()).isEqualTo("B");
        assertThat(service.bandFor(scale(), 0.0).getName()).isEqualTo("F");
        assertThat(service.bandFor(scale(), 100.0).getName()).isEqualTo("A");
    }

    @Test
    void band_boundaries_are_inclusive_at_both_ends() {
        assertThat(service.bandFor(scale(), 32.0).getName()).isEqualTo("F");
        assertThat(service.bandFor(scale(), 33.0).getName()).isEqualTo("C");
        assertThat(service.bandFor(scale(), 59.0).getName()).isEqualTo("C");
        assertThat(service.bandFor(scale(), 60.0).getName()).isEqualTo("B");
    }

    @Test
    void no_scale_means_no_band_but_never_an_error() {
        // D2 — a school without a configured scale still gets its numbers.
        assertThat(service.bandFor(Collections.emptyList(), 74.0)).isNull();
        assertThat(service.bandFor(null, 74.0)).isNull();
    }

    @Test
    void a_null_percentage_has_no_band() {
        assertThat(service.bandFor(scale(), null)).isNull();
    }
}
