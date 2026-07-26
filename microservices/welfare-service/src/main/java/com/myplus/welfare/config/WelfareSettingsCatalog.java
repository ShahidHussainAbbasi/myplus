package com.myplus.welfare.config;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The welfare/donations policies an owner may configure — this service's contribution to the shared
 * common-settings catalog. Each entry is one Configuration-screen toggle; behaviour reads
 * {@code settingsService.getBool(key)}. Both defaults are OFF = today's behaviour, so enabling the lib changes
 * nothing until an owner opts in.
 */
@Component
public class WelfareSettingsCatalog implements SettingsCatalogProvider {

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.bool("welfare.donation.requireDonor",
                        "Require a donor on every donation",
                        "Off (default): a donation may be recorded without naming a donor. On: a donation must name a "
                                + "donor (attribution/audit for grant-funded charities).",
                        false, "Donations"),
                SettingEntry.bool("welfare.donator.allowDuplicateNames",
                        "Allow donors with the same name",
                        "Off (default): a new donor with a name that already exists is refused. On: same-name donors "
                                + "are allowed (families, common names).",
                        false, "Donors")
        );
    }
}
