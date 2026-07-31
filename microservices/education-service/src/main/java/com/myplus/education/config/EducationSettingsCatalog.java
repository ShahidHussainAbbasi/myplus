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
                        true, "Grading")
        );
    }
}
