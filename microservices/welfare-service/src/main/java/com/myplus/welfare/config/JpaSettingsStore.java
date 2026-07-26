package com.myplus.welfare.config;

import com.myplus.common.settings.SettingsStore;
import com.myplus.welfare.entity.OrgSetting;
import com.myplus.welfare.repository.OrgSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * welfare-service's {@link SettingsStore} — the SPI impl backing the shared common-settings engine onto this
 * service's own {@code org_setting} table (Flyway V5). Its presence activates the shared
 * SettingsService/Controller (the auto-config is @ConditionalOnBean(SettingsStore.class)).
 */
@Component
@RequiredArgsConstructor
public class JpaSettingsStore implements SettingsStore {

    private final OrgSettingRepository repo;

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
}
