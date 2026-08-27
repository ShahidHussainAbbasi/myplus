package com.myplus.pharma.config;

import com.myplus.common.settings.SettingsStore;
import com.myplus.pharma.entity.OrgSetting;
import com.myplus.pharma.repository.OrgSettingRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * pharma-service's {@link SettingsStore} — the SPI impl backing the shared common-settings engine onto this
 * service's own {@code org_setting} table (C3b).
 *
 * <h3>Its presence is what activates the engine at all</h3>
 * {@code CommonSettingsAutoConfiguration} is {@code @ConditionalOnBean(SettingsStore.class)}, so without this
 * class there is no {@code SettingsService}, no {@code /settings} endpoint and — the reason it was written —
 * no {@code CapabilityService}. That is precisely why prescriptions could not be capability-guarded before:
 * the library was not merely absent from the pom, it had nothing to bind to.
 *
 * <p>OMS O3 shipped the other half of this mistake in marketplace — catalog, migration and resolver all
 * present, no store — and because the resolver injected the service <b>optionally</b>, the result was silent:
 * every tenant kept the platform default while the Configuration screen returned nothing. The capability
 * guards therefore inject {@code CapabilityService} as REQUIRED, so a missing store fails the service at
 * startup instead of quietly disabling every check.
 */
@Component
@RequiredArgsConstructor
public class JpaSettingsStore implements SettingsStore {

    private final OrgSettingRepository repo;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(Long organizationId, String key) {
        // The org always arrives as a parameter rather than from CurrentUser: not every reader of a tenant
        // policy is an authenticated member of that tenant (SettingsService.effectiveFor).
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
