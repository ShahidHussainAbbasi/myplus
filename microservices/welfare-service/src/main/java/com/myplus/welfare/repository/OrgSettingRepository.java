package com.myplus.welfare.repository;

import com.myplus.welfare.entity.OrgSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgSettingRepository extends JpaRepository<OrgSetting, Long> {
    List<OrgSetting> findByOrganizationId(Long organizationId);
    Optional<OrgSetting> findByOrganizationIdAndSettingKey(Long organizationId, String settingKey);
}
