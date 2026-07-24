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
                        false, "Branch policy")
        );
    }
}
