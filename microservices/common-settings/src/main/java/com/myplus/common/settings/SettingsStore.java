package com.myplus.common.settings;

import java.util.List;
import java.util.Optional;

/**
 * SPI — persistence of per-tenant overrides. Each service supplies ONE bean backing onto its own
 * {@code org_setting} table (so data ownership stays with the service; the shared lib carries no @Entity and
 * needs no cross-module @EntityScan). All calls are already org-scoped by the caller ({@link SettingsService}
 * passes the resolved organizationId).
 */
public interface SettingsStore {

    /** The stored override for (org, key), or empty if the tenant has never set it. */
    Optional<String> find(Long organizationId, String key);

    /** All overrides for an org — {@link Stored} pairs (key, value). */
    List<Stored> findAll(Long organizationId);

    /** Insert or update the override for (org, key); {@code userId} is audit. */
    void upsert(Long organizationId, Long userId, String key, String value);

    record Stored(String key, String value) { }
}
