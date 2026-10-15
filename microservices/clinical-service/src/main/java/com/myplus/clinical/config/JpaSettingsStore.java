package com.myplus.clinical.config;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.settings.SettingsStore;
import com.myplus.clinical.entity.OrgSetting;
import com.myplus.clinical.repository.OrgSettingRepo;

import lombok.RequiredArgsConstructor;

/**
 * EX-2f — clinical-service's {@link SettingsStore}: the shared common-settings engine backed onto this service's own
 * {@code org_setting} table. Its presence activates the shared SettingsService (the auto-config is
 * {@code @ConditionalOnBean(SettingsStore.class)}). Same shape as welfare's and education's.
 */
@Component
@RequiredArgsConstructor
public class JpaSettingsStore implements SettingsStore {

    private final OrgSettingRepo repo;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(Long organizationId, String key) {
        return repo.findByOrganizationIdAndSettingKey(organizationId, key).map(OrgSetting::getSettingValue);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stored> findAll(Long organizationId) {
        return repo.findByOrganizationId(organizationId).stream()
                .map(o -> new Stored(o.getSettingKey(), o.getSettingValue())).toList();
    }

    @Override
    @Transactional
    public void upsert(Long organizationId, Long userId, String key, String value) {
        OrgSetting o = repo.findByOrganizationIdAndSettingKey(organizationId, key)
                .orElseGet(() -> OrgSetting.builder().organizationId(organizationId).settingKey(key).build());
        o.setSettingValue(value);
        o.setUserId(userId);
        o.setUpdated(LocalDateTime.now());
        repo.save(o);
    }

    @Override
    @Transactional
    public void remove(Long organizationId, String key) {
        if (organizationId == null) return;
        repo.findByOrganizationIdAndSettingKey(organizationId, key).ifPresent(repo::delete);
    }
}
