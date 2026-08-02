package com.myplus.education.config;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The education policies an owner may configure — this service's contribution to the shared common-settings
 * catalog. Each entry is one Configuration-screen toggle; behaviour reads {@code settingsService.getBool(key)}.
 * Adding a policy here is all it takes to surface it (no schema change).
 *
 * Branch-policy toggles default OFF = org-wide (a single-branch school never needs them; a group can opt in).
 * When ON, visibility is DERIVED from students (guardian via Student.guardianId, discount via Student.discountId),
 * so a cross-campus parent stays visible from either branch — see GuardianController / DiscountController.
 */
@Component
public class EducationSettingsCatalog implements SettingsCatalogProvider {

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.bool("edu.guardian.branchScoped",
                        "Restrict guardians to their branch",
                        "Off (default): guardians are visible org-wide (a parent may have children at more than one "
                                + "campus). On: a branch's staff see only guardians who have a student at that branch.",
                        false, "Branch policy"),
                SettingEntry.bool("edu.discount.branchScoped",
                        "Restrict discounts to their branch",
                        "Off (default): fee discounts are visible org-wide. On: a branch sees only discounts applied "
                                + "to a student at that branch.",
                        false, "Branch policy"),
                SettingEntry.bool("edu.staff.branchScoped",
                        "Restrict staff to their branch",
                        "Off (default): the staff list is visible org-wide. On: a branch sees only staff assigned to "
                                + "a class at that branch. A teacher who covers two campuses stays visible at both, "
                                + "and staff assigned to no class stay visible everywhere.",
                        false, "Branch policy"),
                SettingEntry.bool("edu.subject.branchScoped",
                        "Restrict subjects to their branch",
                        "Off (default): the subject list is visible org-wide (one shared curriculum). On: a branch "
                                + "sees only subjects attached to a class at that branch. A subject attached to no "
                                + "class stays visible everywhere.",
                        false, "Branch policy"),
                SettingEntry.bool("edu.fee.creditOnOverpayment",
                        "Carry an overpayment forward as fee credit",
                        "On (default): if a parent pays more than is owed, the surplus is held as fee credit and "
                                + "applied automatically to the next charge. Off: the payment is refused and only "
                                + "the outstanding amount may be collected — for schools that do not hold "
                                + "parent money.",
                        true, "Fees")
                // NOTE: fee-collection branch scoping is deliberately NOT here. It already exists as
                // FeeSetting.feeCollectionBranchScoped on the Fee Settings screen (see FeeCollectionController
                // .branchVisible). Adding a second switch for the same behaviour would give the owner two
                // controls that can disagree — and nothing to say which one wins.
                ,
                // ── Slice 1.4: grading policy. The BANDS are an entity (grade_band), not settings — a list
                // does not fit a scalar store. Only these two scalar policies live here.
                SettingEntry.bool("edu.grading.absentCountsAsZero",
                        "Absence counts as zero",
                        "On (default): a paper the student did not sit counts as 0% in averages and report "
                                + "cards. Off: the paper is excluded from BOTH sides of the average, as if it "
                                + "were never set — for schools that run supplementary exams. Marks entry keeps "
                                + "'absent' and a genuine zero apart, so either policy can be applied honestly.",
                        true, "Grading"),
                SettingEntry.bool("edu.grading.roundHalfUp",
                        "Round percentages up at the halfway point",
                        "On (default): 74.45% becomes 74.5%. Off: percentages are truncated instead. Only "
                                + "affects the boundary case, but it decides which band a mark falls into when "
                                + "it lands exactly between two.",
                        true, "Grading"),
                // ── Slice 1.5: what a report CARD shows. The card's contents are a snapshot (report_card),
                // not settings; these two decide only what is RENDERED from it.
                SettingEntry.bool("edu.reportCard.showRank",
                        "Show class rank on report cards",
                        "Off (default): position in class is never printed. Several jurisdictions prohibit "
                                + "publishing rank, so this is opt-in rather than opt-out. Rank is always "
                                + "RECORDED when a card is issued, so turning this on later cannot invent a "
                                + "position for cards issued while it was off — and turning it off does not "
                                + "erase what was already printed. Ties share a rank.",
                        false, "Report card"),
                SettingEntry.bool("edu.reportCard.showAttendance",
                        "Show attendance on report cards",
                        "On (default): the card shows days present out of days recorded for the term. "
                                + "Counted from the term's date range, so terms recorded before attendance was "
                                + "linked to terms are still summarised correctly.",
                        true, "Report card"),
                // ── Slice 1.6: the promotion rule. The class LADDER is deliberately not here — the target
                // class is a per-batch decision the admin makes on screen (D1), not org configuration.
                SettingEntry.bool("edu.promotion.requirePass",
                        "Require a pass mark to be promoted",
                        "Off (default): every student is promoted, which matches no-detention policies. "
                                + "On: a student whose year average is below the pass mark below is proposed "
                                + "for retention. The proposal can always be overridden per student — this "
                                + "setting decides the default, never the outcome.",
                        false, "Promotion"),
                SettingEntry.intOf("edu.promotion.minPercent",
                        "Promotion pass mark (%)",
                        "The year average a student must reach to be promoted. Only consulted when the "
                                + "setting above is on. Averaged over the report cards actually ISSUED for "
                                + "the year, so a term with no card does not count against the student.",
                        33, "Promotion"),
                SettingEntry.intOf("edu.exam.minAttendancePercent",
                        "Minimum attendance for exam eligibility (%)",
                        "0 (default) disables the check. Above 0, a student below this attendance for the "
                                + "term is flagged as ineligible on the marksheet and the report card. It is "
                                + "a FLAG, not a block: students are not registered for papers individually, "
                                + "so there is nothing for the system to refuse — the school acts on it.",
                        0, "Promotion"),
                // ── Slice 2.3: staff attendance & leave. The leave TYPES are an entity (a list); only
                // these two scalar policies live here — the 1.1/1.4 split applied again.
                SettingEntry.intOf("edu.attendance.staffGraceMinutes",
                        "Lateness grace period (minutes)",
                        "How long after their contracted start a staff member may arrive before the "
                                + "register records LATE instead of PRESENT. Derived when the register is "
                                + "marked, so the threshold is one org-wide policy rather than a judgement "
                                + "made row by row.",
                        15, "Staff attendance"),
                SettingEntry.bool("edu.leave.requireApproval",
                        "Leave requests need approval",
                        "On (default): a request is submitted as PENDING and a head approves it. Off: it is "
                                + "recorded as approved immediately — for small schools where the person "
                                + "entering it is the person deciding. Either way the approved days become "
                                + "absences, so the substitution screen knows which lessons need cover.",
                        true, "Staff attendance")
        );
    }
}
