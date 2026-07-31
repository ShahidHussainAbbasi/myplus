package com.myplus.education.service;

import com.myplus.education.entity.Term;
import com.myplus.education.repository.TermRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Slice 1.1 — resolves "which term is it?".
 *
 * D3: the answer is DERIVED from dates, never stored as an {@code isCurrent} flag. A stored flag needs
 * a nightly job, and a job that silently stops leaves a school marking attendance into last term. A
 * date comparison is true the moment it becomes true.
 *
 * This is the ONE place the rule lives (DRY) — no controller re-derives it.
 */
@Service
public class TermService {

    @Autowired
    private TermRepository termRepository;

    /**
     * The current term for a tenant, or null when the school has not set terms up yet.
     * A null term is a legitimate, permanent state — see {@code resolveCurrent}.
     */
    @Transactional(readOnly = true)
    public Term currentTerm(Long orgId, Long userId) {
        return resolveCurrent(termRepository.findScoped(orgId, userId), LocalDate.now());
    }

    /** Just the id, for stamping onto new rows. Null-safe. */
    @Transactional(readOnly = true)
    public Long currentTermId(Long orgId, Long userId) {
        Term t = currentTerm(orgId, userId);
        return t == null ? null : t.getId();
    }

    /**
     * The D3 rule, as a pure function so every branch is unit-testable without a database:
     *
     * <ol>
     *   <li>an explicitly pinned term WINS over everything (a school holding a term open to finish
     *       entering marks);</li>
     *   <li>otherwise the term whose [startDate, endDate] contains today;</li>
     *   <li>otherwise the most recently ENDED term (holidays, the gap between terms);</li>
     *   <li>otherwise null — the school has defined no terms, and everything must still work.</li>
     * </ol>
     *
     * Terms with no dates cannot be matched on date, but can still be pinned.
     */
    public static Term resolveCurrent(List<Term> terms, LocalDate today) {
        if (terms == null || terms.isEmpty()) return null;

        // 1. pinned wins. If several are pinned (data the UI should prevent), take the latest-starting
        //    one rather than an arbitrary row, so the choice is at least deterministic.
        Term pinned = terms.stream()
                .filter(Term::isPinnedCurrent)
                .max(Comparator.comparing(Term::getStartDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (pinned != null) return pinned;

        // 2. today falls inside a term
        Term containing = terms.stream()
                .filter(t -> t.getStartDate() != null && t.getEndDate() != null)
                .filter(t -> !today.isBefore(t.getStartDate()) && !today.isAfter(t.getEndDate()))
                .max(Comparator.comparing(Term::getStartDate))
                .orElse(null);
        if (containing != null) return containing;

        // 3. between terms → the most recently ended one
        return terms.stream()
                .filter(t -> t.getEndDate() != null && t.getEndDate().isBefore(today))
                .max(Comparator.comparing(Term::getEndDate))
                .orElse(null);
    }
}
