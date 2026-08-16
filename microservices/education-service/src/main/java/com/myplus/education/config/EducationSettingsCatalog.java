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
 * so a cross-campus guardian stays visible from either branch — see GuardianController / DiscountController.
 */
@Component
public class EducationSettingsCatalog implements SettingsCatalogProvider {

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.bool("edu.guardian.branchScoped",
                        "Restrict guardians to their branch",
                        "Off (default): guardians are visible org-wide (a guardian may have children at more than one "
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
                        "On (default): if a guardian pays more than is owed, the surplus is held as fee credit and "
                                + "applied automatically to the next charge. Off: the payment is refused and only "
                                + "the outstanding amount may be collected — for schools that do not hold "
                                + "guardian money.",
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
                        true, "Staff attendance"),
                // ── Slice 3.1: the guardian portal. OFF by default and fails CLOSED — this is the first
                // surface an outsider can reach, and a portal that goes live the moment code deploys is
                // not a decision anyone made. Standard C3 inverted: the SAFE state is off, so that is
                // both the default and the fallback when the setting cannot be read.
                SettingEntry.bool("edu.portal.enabled",
                        "Guardian portal is open",
                        "Off (default): guardians cannot sign in at all, even ones who have been invited. "
                                + "On: an invited guardian can see results, attendance, fee dues and "
                                + "homework for THEIR OWN children only — never another family's, and "
                                + "never anything staff-facing. Turn this off to close the portal "
                                + "immediately for everyone without withdrawing individual invitations.",
                        false, "Guardian portal"),
                // ── Slice 3.3: the STUDENT portal, a SECOND switch and deliberately not a widening of the
                // one above. A school running the guardian portal has not thereby decided that children
                // should log in — that is a different decision about a different audience, most obviously
                // in a primary school. Off by default and fails CLOSED, for 3.1's reason exactly.
                //
                // It is SUBORDINATE, not parallel: StudentResolver checks edu.portal.enabled FIRST, so
                // closing the portal closes it for everyone and this flag cannot resurrect it. The gate
                // asserts both directions and both halves (standard C2).
                SettingEntry.bool("edu.portal.students.enabled",
                        "Student portal is open",
                        "Off (default): students cannot sign in, even ones who have been invited. "
                                + "On: an invited student sees their OWN timetable, results, homework and "
                                + "attendance — and never fee dues or behaviour notes, which stay between "
                                + "the school and the guardian. Requires the guardian portal switch above "
                                + "to be on as well; turning that off closes both.",
                        false, "Guardian portal"),
                // ── Slice 3.5: notice DELIVERY. Default ON and FAILS ON (standard C3), like N1's cover
                // notice and unlike edu.portal.enabled — a missed closure notice is worse than a duplicate
                // one, whereas for the portal the unsafe direction is disclosure.
                //
                // ⚠ It governs the SEND, not the RECORD. With this off, publishing still works and both
                // portals still show the notice; only the emails stop. That distinction is the whole of
                // finding C (the record is the deliverable), and the gate asserts both halves.
                SettingEntry.bool("edu.notify.notices",
                        "Email families when a notice is published",
                        "On (default): publishing a notice also emails every guardian or student it "
                                + "reaches who has an address on record. Off: the notice is still "
                                + "published and still visible in the guardian and student portals — only "
                                + "the emails are not sent.",
                        true, "Notices"),
                // ── Slice N1: the cover-assigned notice. Default ON and, per standard C3, it also FAILS
                // ON — if the setting cannot be read the notice is still queued. The failure mode of an
                // extra email is noise; the failure mode of a missing one is a teacher not knowing they
                // are covering a class. Deliberately the opposite of edu.portal.enabled above, where the
                // unsafe direction is disclosure and the safe state is therefore off.
                SettingEntry.bool("edu.notify.coverAssigned",
                        "Email a teacher when they are assigned cover",
                        "On (default): when a teacher is assigned to cover an absent colleague's lesson, "
                                + "they are emailed the class, period, subject and date. Off: cover is "
                                + "still recorded and still shows on the substitution screen, but nothing "
                                + "is sent — for schools that assign cover verbally at the morning "
                                + "briefing. A teacher with no email address on record is never emailed "
                                + "either way, and the screen says so when that happens.",
                        true, "Staff attendance"),
                // ── UI/UX P7.3: keyboard navigation on the REGISTRATION modals (Student, Guardian,
                // Staff, Campus, Class, Subject, Vehicle, Discount, Owner). Same two keys, same
                // wording and same defaults as business's — one behaviour, so one contract.
                //
                // Both fail OPEN, unlike the branch-policy and portal switches above. The reasoning
                // inverts because the risk does: closing a portal wrongly discloses nothing, whereas
                // Enter moving to the next box cannot do anything a Tab press could not. Losing the
                // feature to a config-read hiccup is the worse outcome, so an unreadable setting
                // resolves to ON (see loadKeyboardFlags() in education.js).
                SettingEntry.bool("ui.keyboard.formNav.enabled",
                        "Keyboard navigation on registration forms",
                        "On (default): Enter moves to the next field on the Student, Guardian, Staff, "
                                + "Campus, Class, Subject, Vehicle and Discount forms, Shift+Enter goes "
                                + "back, and Esc closes the form. Off: those forms behave as before, with "
                                + "Tab only. Phones and small tablets are unaffected either way.",
                        true, "Data entry"),
                SettingEntry.bool("ui.keyboard.enterSubmits",
                        "Enter saves on the last field",
                        "On (default): pressing Enter on the last field of a registration form saves it, "
                                + "so a student is enrolled without reaching for the mouse. Off: only "
                                + "Ctrl+Enter saves, which suits offices worried that a stray Enter could "
                                + "save a half-typed record. Ctrl+Enter always saves either way.",
                        true, "Data entry")
        );
    }
}
