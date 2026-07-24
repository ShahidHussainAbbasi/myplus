package com.myplus.common.settings;

import java.util.List;

/**
 * SPI — a service registers the settings it exposes to owners by publishing a bean of this type. Several
 * providers may coexist (grouped by their entries' {@code group}); {@link SettingsService} aggregates them.
 * Adding a configurable policy = one {@link SettingEntry} in a provider, no schema change.
 */
public interface SettingsCatalogProvider {
    List<SettingEntry> entries();
}
