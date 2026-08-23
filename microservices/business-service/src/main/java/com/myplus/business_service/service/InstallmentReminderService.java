package com.myplus.business_service.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.dto.ReminderViewDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.entity.InstallmentReminder;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.InstallmentPlanRepo;
import com.myplus.business_service.repository.InstallmentReminderRepo;

import lombok.RequiredArgsConstructor;

/**
 * INST-3a — the collections worklist, and recording that somebody was rung.
 *
 * <h3>⚠ This is where the scanner's cross-tenant licence STOPS</h3>
 * {@link ReminderScanner} enumerates tenants because a {@code @Scheduled} thread has no authenticated user.
 * Everything here runs on an ordinary request thread and is scoped to the caller's own org, in the query
 * rather than after it. Getting that wrong shows one shop its competitor's debtors, which is why it is the
 * assertion the gate leads with rather than a footnote.
 */
@Service
@RequiredArgsConstructor
public class InstallmentReminderService {

    private final InstallmentReminderRepo reminderRepo;
    private final InstallmentPlanRepo planRepo;
    private final CustomerRepo customerRepo;
    private final ReminderScanner scanner;

    /**
     * The worklist: who to ring, most urgent first, with what we already know.
     *
     * @param stage {@code null} for everything, or one of the {@code InstallmentReminder.STAGE_*} values
     */
    @Transactional(readOnly = true)
    public List<ReminderViewDTO> worklist(Long orgId, String stage) {
        if (orgId == null) return List.of();

        List<InstallmentReminder> rows = reminderRepo.findScoped(orgId, blankToNull(stage));
        if (rows.isEmpty()) return List.of();

        // Both lookups are batched. One query per row would make a 200-name worklist issue 400 queries —
        // the O(n^2) shape this codebase has already paid for twice.
        Set<Long> planIds = new HashSet<>();
        Set<Long> customerIds = new HashSet<>();
        for (InstallmentReminder r : rows) {
            if (r.getPlanId() != null) planIds.add(r.getPlanId());
            if (r.getCustomerId() != null) customerIds.add(r.getCustomerId());
        }

        Map<Long, InstallmentPlan> plans = new HashMap<>();
        for (InstallmentPlan p : planRepo.findAllById(planIds)) plans.put(p.getId(), p);

        // The whole Customer, not just the name: this worklist exists to be rung, so it needs the number.
        Map<Long, Customer> customers = new HashMap<>();
        for (Customer c : customerRepo.findAllById(customerIds)) customers.put(c.getCustomerId(), c);

        LocalDate today = LocalDate.now();
        List<ReminderViewDTO> out = new ArrayList<>();
        for (InstallmentReminder r : rows) {
            InstallmentPlan plan = plans.get(r.getPlanId());
            out.add(ReminderViewDTO.of(r, plan, installmentOf(plan, r.getInstallmentId()),
                    customers.get(r.getCustomerId()), today));
        }
        return out;
    }

    /**
     * Record that somebody actually rang.
     *
     * <p>This is the half that makes the list a collections tool rather than a list — without it the shop
     * rings the same customer three times and never rings another.
     *
     * <p>Read by id AND org, never by id alone: an id off the wire is exactly the anti-IDOR case the D2
     * credit-standing leak established.
     *
     * @return false when the row does not exist <b>or belongs to another tenant</b> — the caller cannot tell
     *         the two apart, which is deliberate: a distinguishable "not yours" confirms the row exists
     */
    @Transactional
    public boolean recordAction(Long orgId, Long reminderId, String outcome, String note) {
        if (orgId == null || reminderId == null) return false;

        InstallmentReminder r = reminderRepo.findScopedById(reminderId, orgId).orElse(null);
        if (r == null) return false;

        r.setActedAt(LocalDateTime.now());
        r.setOutcome(trim(outcome, 32));
        r.setNote(trim(note, 255));
        reminderRepo.save(r);
        return true;
    }

    /**
     * Scan this tenant now, rather than waiting for the timer.
     *
     * <p>Scoped to the caller's own org on purpose. The scheduler sweeps every tenant because it has no user
     * to ask; a request does have one, so there is no reason for this path to hold the wider licence.
     *
     * @return how many reminders were newly recorded
     */
    public int scanNow(Long orgId) {
        if (orgId == null) return 0;
        return scanner.scanTenant(orgId, LocalDate.now());
    }

    private static Installment installmentOf(InstallmentPlan plan, Long installmentId) {
        if (plan == null || installmentId == null) return null;
        for (Installment i : plan.getInstallments()) {
            if (installmentId.equals(i.getId())) return i;
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Truncate rather than refuse: a shopkeeper typing a long note should not lose the call they just made. */
    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
