package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.myplus.education.entity.Term;

import org.junit.jupiter.api.Test;

/**
 * Slice 1.1 — the "which term is it?" rule (design D3).
 *
 * Pure logic, so it runs on every {@code mvn test} with no database and no Docker. Every branch of
 * {@code resolveCurrent} is covered, including the two that are easy to get wrong: the gap BETWEEN
 * terms, and a school that has defined no terms at all (a permanently legitimate state).
 */
class TermServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private static Term term(String name, String start, String end, boolean pinned) {
        return Term.builder()
                .id((long) name.hashCode() & 0xffff)
                .name(name)
                .startDate(start == null ? null : LocalDate.parse(start))
                .endDate(end == null ? null : LocalDate.parse(end))
                .pinnedCurrent(pinned)
                .academicYearId(1L).userId(1L).organizationId(1L)
                .build();
    }

    /** Term 1 ended, Term 2 is running, Term 3 has not started. */
    private static List<Term> threeTerms() {
        return Arrays.asList(
                term("Term 1", "2026-08-01", "2026-10-31", false),
                term("Term 2", "2026-11-01", "2027-01-31", false),
                term("Term 3", "2027-02-01", "2027-04-30", false));
    }

    @Test
    void today_inside_a_term_selects_that_term() {
        assertThat(TermService.resolveCurrent(threeTerms(), TODAY).getName()).isEqualTo("Term 2");
    }

    @Test
    void between_terms_falls_back_to_the_most_recently_ended() {
        // 1 Nov is a holiday gap: Term 1 ended 31 Oct, Term 2 starts 5 Nov.
        List<Term> gapped = Arrays.asList(
                term("Term 1", "2026-08-01", "2026-10-31", false),
                term("Term 2", "2026-11-05", "2027-01-31", false));
        Term current = TermService.resolveCurrent(gapped, LocalDate.of(2026, 11, 2));
        assertThat(current).isNotNull();
        assertThat(current.getName()).as("the gap belongs to the term that just ended").isEqualTo("Term 1");
    }

    @Test
    void a_pinned_term_wins_over_the_date_comparison() {
        // The real case: the school holds Term 1 open past its end date to finish entering marks.
        List<Term> terms = Arrays.asList(
                term("Term 1", "2026-08-01", "2026-10-31", true),
                term("Term 2", "2026-11-01", "2027-01-31", false));
        assertThat(TermService.resolveCurrent(terms, TODAY).getName()).isEqualTo("Term 1");
    }

    @Test
    void no_terms_defined_yields_null_rather_than_an_error() {
        // A school that has not set terms up must keep working; callers stamp a null term_id.
        assertThat(TermService.resolveCurrent(Collections.emptyList(), TODAY)).isNull();
        assertThat(TermService.resolveCurrent(null, TODAY)).isNull();
    }

    @Test
    void before_the_first_term_ever_starts_there_is_no_current_term() {
        // Nothing contains today and nothing has ended — inventing "Term 1" here would be wrong.
        assertThat(TermService.resolveCurrent(threeTerms(), LocalDate.of(2026, 1, 1))).isNull();
    }

    @Test
    void after_the_last_term_ends_the_last_term_stays_current() {
        Term current = TermService.resolveCurrent(threeTerms(), LocalDate.of(2027, 7, 1));
        assertThat(current).isNotNull();
        assertThat(current.getName()).isEqualTo("Term 3");
    }

    @Test
    void terms_without_dates_are_ignored_by_the_date_rules_but_can_still_be_pinned() {
        List<Term> undated = Arrays.asList(
                term("Unscheduled", null, null, false),
                term("Term 2", "2026-11-01", "2027-01-31", false));
        assertThat(TermService.resolveCurrent(undated, TODAY).getName()).isEqualTo("Term 2");

        List<Term> undatedPinned = Arrays.asList(
                term("Unscheduled", null, null, true),
                term("Term 2", "2026-11-01", "2027-01-31", false));
        assertThat(TermService.resolveCurrent(undatedPinned, TODAY).getName()).isEqualTo("Unscheduled");
    }

    @Test
    void several_pinned_rows_resolve_deterministically_rather_than_arbitrarily() {
        // The UI prevents this, but data can still arrive this way; the answer must not depend on order.
        List<Term> terms = Arrays.asList(
                term("Term 1", "2026-08-01", "2026-10-31", true),
                term("Term 2", "2026-11-01", "2027-01-31", true));
        assertThat(TermService.resolveCurrent(terms, TODAY).getName()).isEqualTo("Term 2");
        Collections.reverse(terms = new java.util.ArrayList<>(terms));
        assertThat(TermService.resolveCurrent(terms, TODAY).getName()).isEqualTo("Term 2");
    }
}
