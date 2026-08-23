package com.myplus.business_service.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.myplus.business_service.entity.Installment;
import com.myplus.business_service.entity.InstallmentPlan;
import com.myplus.business_service.entity.InstallmentReminder;
import com.myplus.business_service.repository.InstallmentPlanRepo;
import com.myplus.business_service.repository.InstallmentReminderRepo;
import com.myplus.common.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/**
 * INST-3a — notices that an installment needs chasing, and records that it noticed.
 *
 * <h3>⚠ This runs with NO authenticated user, and that is the whole risk of the slice</h3>
 * Every read in this service is normally org-scoped through {@code RequestUtil}. On a {@code @Scheduled}
 * thread there is no request, no JWT and no {@code AuthenticatedUser}, so every {@code findScoped} helper is
 * meaningless here.
 *
 * <p>The two relays already in this service look like precedent and are <b>not</b>.
 * {@code GlOutboxService.flushPending} and {@code AuditService} re-drive rows that were enqueued on a request
 * thread and are already stamped with their tenant — their scheduled half never needs to know an org exists.
 * This scanner <b>originates</b> rows, so it must enumerate tenants explicitly and stamp each reminder from
 * the plan it read.
 *
 * <p><b>The cross-tenant licence stops here.</b> The worklist read and the mark-as-chased write live in
 * {@code InstallmentReminderService} on ordinary request threads, scoped and anti-IDOR checked.
 *
 * <h3>Why the timer cannot cause duplicates</h3>
 * It will run again in five minutes, after a restart, and twice at once during a rolling deploy. Correctness
 * does not depend on the scanner remembering what it did: {@code installment_reminder.dedupe_key} is UNIQUE,
 * the check is a read followed by an insert, and the race between the two is caught rather than prevented.
 * A duplicate key means another pass won — which is a success, not an error.
 *
 * <h3>What it deliberately does not do</h3>
 * It does not send anything (the customer chose the worklist first — there is no address to send to, since the
 * sale panel collects a phone number and nothing else), and it does not write a status onto the installment.
 * Overdue stays derived, so the screen and the reminder cannot disagree.
 */
@Service
@RequiredArgsConstructor
public class ReminderScanner {

    private static final Logger LOG = LoggerFactory.getLogger(ReminderScanner.class);

    /** A tenant must ask for this. A default is not a decision. */
    static final String KEY_ENABLED = "pos.installment.remind.enabled";
    /** How many days before the due date a courtesy chase appears. */
    static final String KEY_BEFORE_DAYS = "pos.installment.remind.beforeDays";

    private final InstallmentPlanRepo planRepo;
    private final InstallmentReminderRepo reminderRepo;
    private final SettingsService settingsService;

    /**
     * A due date changes once a day, so scanning is not urgent — but a fixed delay rather than a cron keeps
     * the first pass close to start-up, which is what makes the feature testable without waiting for midnight.
     */
    @Scheduled(fixedDelayString = "${installment.remind.scan-delay-ms:900000}",
               initialDelayString = "${installment.remind.initial-delay-ms:60000}")
    public void scan() {
        try {
            scanAllTenants(LocalDate.now());
        } catch (Exception e) {
            // A scanner that throws on a timer takes its scheduler thread down with it and every later pass
            // silently never happens. Swallow at the boundary, loudly.
            LOG.error("Installment reminder scan failed", e);
        }
    }

    /**
     * @param today passed in rather than read from a clock inside the loop, so the whole pass agrees on a date
     *              and so a test can scan a fixed day without freezing time
     * @return how many reminders were newly recorded — the number the gate asserts, because "the scan ran" is
     *         an artefact and "somebody now appears on the worklist" is the property
     */
    public int scanAllTenants(LocalDate today) {
        int recorded = 0;
        for (Long orgId : planRepo.findTenantsWithOpenPlansAcrossTenants()) {
            try {
                recorded += scanTenant(orgId, today);
            } catch (Exception e) {
                // One tenant's bad data must not stop every other tenant being chased.
                LOG.error("Installment reminder scan failed for org {}", orgId, e);
            }
        }
        if (recorded > 0) LOG.info("Installment reminders recorded: {}", recorded);
        return recorded;
    }

    /**
     * One tenant, on its own settings. Public so the gate can drive a scan without waiting for the timer.
     *
     * <p><b>Deliberately NOT {@code @Transactional}.</b> Two reasons, either of which alone would be enough.
     * It is called from {@code scanAllTenants} in this same class, and a self-invocation never passes through
     * the proxy, so the annotation would have been decorative. And if it HAD taken effect it would have been
     * actively harmful: the duplicate-key catch below cannot rescue a transaction that a constraint violation
     * has already marked rollback-only, so one lost race would have destroyed the whole tenant's scan at
     * commit under the unhelpful banner "Transaction silently rolled back".
     *
     * <p>Instead the read is a single fetch-join and each {@code save()} is its own transaction, so a lost
     * race costs exactly the one row it lost.
     */
    public int scanTenant(Long orgId, LocalDate today) {
        if (!settingsService.getBoolFor(orgId, KEY_ENABLED)) return 0;

        int beforeDays = settingsService.getIntFor(orgId, KEY_BEFORE_DAYS, 3);
        LocalDate soonThrough = today.plusDays(Math.max(0, beforeDays));

        int recorded = 0;
        List<InstallmentPlan> plans = planRepo.findOpenWithInstallmentsScoped(orgId);
        for (InstallmentPlan plan : plans) {
            for (Installment i : plan.getInstallments()) {
                if (i.outstanding().signum() <= 0) continue;
                if (i.getDueDate() == null) continue;

                String stage = stageFor(i.getDueDate(), today, soonThrough);
                if (stage == null) continue;

                if (record(plan, i, stage)) recorded++;
            }
        }
        return recorded;
    }

    /**
     * Which chase this installment warrants, or {@code null} for none.
     *
     * <p>An installment due TODAY is {@code DUE_SOON}, never {@code OVERDUE} — the same boundary INST-1 set,
     * where a payment due today is not yet late. Getting this off by one day tells a shop to make a collection
     * call to a customer who has until close of business.
     */
    static String stageFor(LocalDate dueDate, LocalDate today, LocalDate soonThrough) {
        if (dueDate.isBefore(today)) return InstallmentReminder.STAGE_OVERDUE;
        if (!dueDate.isAfter(soonThrough)) return InstallmentReminder.STAGE_DUE_SOON;
        return null;
    }

    /** @return true if this call created the row; false if it already existed or another pass won the race */
    private boolean record(InstallmentPlan plan, Installment i, String stage) {
        String key = InstallmentReminder.keyFor(plan.getPlanNo(), i.getSeqNo(), stage);
        if (reminderRepo.findByDedupeKey(key).isPresent()) return false;

        InstallmentReminder r = new InstallmentReminder();
        r.setOrganizationId(plan.getOrganizationId());   // FROM THE PLAN — there is no request context here
        r.setPlanId(plan.getId());
        r.setInstallmentId(i.getId());
        r.setCustomerId(plan.getCustomerId());
        r.setStage(stage);
        r.setDueDate(i.getDueDate());
        r.setDedupeKey(key);
        r.setNoticedAt(LocalDateTime.now());

        try {
            reminderRepo.save(r);
            return true;
        } catch (DataIntegrityViolationException dup) {
            // Two passes overlapped and the other one won. The UNIQUE constraint did exactly its job; this is
            // the success path for a concurrent scanner, not a failure to report.
            return false;
        }
    }
}
