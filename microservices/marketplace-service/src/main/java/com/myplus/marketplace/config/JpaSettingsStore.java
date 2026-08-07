package com.myplus.marketplace.config;

import com.myplus.common.settings.SettingsStore;
import com.myplus.marketplace.entity.OrgSetting;
import com.myplus.marketplace.repository.OrgSettingRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * marketplace-service's {@link SettingsStore} — the SPI impl backing the shared common-settings engine onto this
 * service's own {@code org_setting} table (OMS O3).
 *
 * <p><b>Its presence is what activates the engine at all:</b> {@code CommonSettingsAutoConfiguration} is
 * {@code @ConditionalOnBean(SettingsStore.class)}, so without this class no {@code SettingsService} and no
 * {@code /settings} endpoint exist. That is not a theoretical note — O3 was first written with the catalog, the
 * migration and the resolver in place but no store, and the outcome was silent: {@code ShippingPolicy} injects
 * {@code SettingsService} optionally, so it simply stayed null and every store kept the platform default fees
 * while the owner's Configuration screen returned nothing. A missing store must be assumed to mean "settings do
 * not work here", never "settings fall back safely".
 */
@Component
@RequiredArgsConstructor
public class JpaSettingsStore implements SettingsStore {

    private final OrgSettingRepository repo;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(Long organizationId, String key) {
        // Read on the anonymous checkout path, so the org arrives as a parameter (SettingsService.effectiveFor)
        // rather than from the security context — see §2.4 of the O3 design.
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
