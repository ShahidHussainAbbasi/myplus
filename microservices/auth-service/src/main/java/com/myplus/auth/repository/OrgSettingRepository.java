package com.myplus.auth.repository;

import com.myplus.auth.entity.OrgSetting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Per-tenant setting overrides (C3c). Always keyed by organization — there is no global read. */
public interface OrgSettingRepository extends JpaRepository<OrgSetting, Long> {

    Optional<OrgSetting> findByOrganizationIdAndSettingKey(Long organizationId, String settingKey);

    List<OrgSetting> findByOrganizationId(Long organizationId);
}
