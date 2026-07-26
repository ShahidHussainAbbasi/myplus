package com.myplus.agriculture.config;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The agriculture policies an owner may configure — this service's contribution to the shared common-settings
 * catalog. Behaviour reads {@code settingsService.getBool(key)}. The default is OFF = today's behaviour, so
 * enabling the lib changes nothing until an owner opts in.
 */
@Component
public class AgricultureSettingsCatalog implements SettingsCatalogProvider {

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.bool("agri.entry.requireLand",
                        "Require a land/plot on every income & expense",
                        "Off (default): an income or expense may be recorded without a plot. On: every entry must "
                                + "reference a land/plot — enabling real per-plot profit & loss.",
                        false, "Entries")
        );
    }
}
