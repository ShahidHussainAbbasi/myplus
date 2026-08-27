package com.myplus.auth.config;

import com.myplus.auth.entity.OrgSetting;
import com.myplus.auth.repository.OrgSettingRepository;
import com.myplus.common.settings.SettingsStore;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * auth-service's {@link SettingsStore} — and, for capabilities, <b>the</b> store.
 *
 * <p>Its presence activates the shared settings engine here ({@code @ConditionalOnBean}), which is what lets
 * auth-service resolve a tenant's effective capabilities and stamp them into the JWT. Without it there is no
 * {@code CapabilityService} to ask.
 *
 * <p>The reads happen at LOGIN, not on any hot path: one query per token mint, behind the same bounded
 * per-tenant Caffeine cache every other settings reader uses.
 */
@Component
@RequiredArgsConstructor
public class JpaSettingsStore implements SettingsStore {

    private final OrgSettingRepository repo;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(Long organizationId, String key) {
        if (organizationId == null) return Optional.empty();
        return repo.findByOrganizationIdAndSettingKey(organizationId, key).map(OrgSetting::getSettingValue);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stored> findAll(Long organizationId) {
        if (organizationId == null) return List.of();
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
