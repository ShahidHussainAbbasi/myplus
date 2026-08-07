package com.myplus.inventory.repository;

import com.myplus.inventory.entity.OrgSetting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Per-tenant setting overrides (OMS O5a). Always keyed by organization — there is no global read. */
public interface OrgSettingRepository extends JpaRepository<OrgSetting, Long> {

    Optional<OrgSetting> findByOrganizationIdAndSettingKey(Long organizationId, String settingKey);

    List<OrgSetting> findByOrganizationId(Long organizationId);
}
