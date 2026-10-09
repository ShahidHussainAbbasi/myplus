package com.myplus.expense.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.expense.entity.OrgSetting;

/** EX-2f — expense settings overrides, always read inside one tenant. */
public interface OrgSettingRepo extends JpaRepository<OrgSetting, Long> {

    List<OrgSetting> findByOrganizationId(Long organizationId);

    Optional<OrgSetting> findByOrganizationIdAndSettingKey(Long organizationId, String settingKey);
}
