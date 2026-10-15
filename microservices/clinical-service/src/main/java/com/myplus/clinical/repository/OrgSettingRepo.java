package com.myplus.clinical.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.clinical.entity.OrgSetting;

/** HMS S1 — clinic settings overrides, always read inside one tenant. */
public interface OrgSettingRepo extends JpaRepository<OrgSetting, Long> {

    List<OrgSetting> findByOrganizationId(Long organizationId);

    Optional<OrgSetting> findByOrganizationIdAndSettingKey(Long organizationId, String settingKey);
}
