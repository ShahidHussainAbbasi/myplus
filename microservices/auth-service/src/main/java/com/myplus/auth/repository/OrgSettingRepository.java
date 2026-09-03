package com.myplus.auth.repository;

import com.myplus.auth.entity.OrgSetting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Per-tenant setting overrides (C3c). Always keyed by organization — there is no global read. */
public interface OrgSettingRepository extends JpaRepository<OrgSetting, Long> {

    Optional<OrgSetting> findByOrganizationIdAndSettingKey(Long organizationId, String settingKey);

    List<OrgSetting> findByOrganizationId(Long organizationId);

    /**
     * ONB-1 — the rows a shape change clears.
     *
     * <p>Deleting them, rather than setting each to NULL, is what hands the decision back to the shape
     * preset: {@code SettingsService.overrideFor} returns {@code Optional.empty()} for a missing row exactly
     * as it does for a null one, and deleting leaves nothing behind for the next shape change to reason about.
     */
    List<OrgSetting> findByOrganizationIdAndSettingKeyStartingWith(Long organizationId, String prefix);
}
