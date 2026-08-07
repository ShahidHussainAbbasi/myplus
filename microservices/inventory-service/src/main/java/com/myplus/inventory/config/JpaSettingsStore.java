package com.myplus.inventory.config;

import com.myplus.common.settings.SettingsStore;
import com.myplus.inventory.entity.OrgSetting;
import com.myplus.inventory.repository.OrgSettingRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * inventory-service's {@link SettingsStore} — the SPI impl backing the shared common-settings engine onto this
 * service's own {@code org_setting} table (OMS O5a).
 *
 * <p><b>Its presence is what activates the engine at all:</b> {@code CommonSettingsAutoConfiguration} is
 * {@code @ConditionalOnBean(SettingsStore.class)}, so without this class no {@code SettingsService} and no
 * {@code /settings} endpoint exist. OMS O3 shipped exactly that way in marketplace — catalog, migration and
 * resolver all present, no store — and because the resolver injected the service optionally, the result was
 * silent: every tenant kept the platform default while the Configuration screen returned nothing. Hence the
 * required injection in {@code ReservationPolicy}.
 */
@Component
@RequiredArgsConstructor
public class JpaSettingsStore implements SettingsStore {

    private final OrgSettingRepository repo;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(Long organizationId, String key) {
        // Read by the SWEEPER, which has no security context, so the org always arrives as a parameter
        // (SettingsService.effectiveFor) rather than from CurrentUser.
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
