package com.myplus.education.service;

import com.myplus.common.security.CurrentUser;
import com.myplus.education.config.SettingsCatalog;
import com.myplus.education.entity.OrgSetting;
import com.myplus.education.repository.OrgSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the per-tenant configuration store. A value is the org's OVERRIDE if present, else the catalog
 * default — so behaviour code just asks {@code getBool("edu.guardian.branchScoped")} and gets the right answer
 * whether or not the owner has touched it. All reads/writes are scoped to the caller's organization.
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final OrgSettingRepository repo;

    /** Effective boolean value of a setting for the caller's org (override else catalog default). */
    @Transactional(readOnly = true)
    public boolean getBool(String key) {
        return "true".equalsIgnoreCase(effective(key));
    }

    /** Effective raw value (override else catalog default; null if the key is unknown). */
    @Transactional(readOnly = true)
    public String effective(String key) {
        Long org = CurrentUser.organizationId();
        if (org != null) {
            OrgSetting o = repo.findByOrganizationIdAndSettingKey(org, key).orElse(null);
            if (o != null && o.getSettingValue() != null) return o.getSettingValue();
        }
        SettingsCatalog.Entry e = SettingsCatalog.byKey(key);
        return e == null ? null : e.defaultValue();
    }

    /** The whole catalog with each entry's effective value + whether it is an org override — feeds the UI. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogForOrg() {
        Long org = CurrentUser.organizationId();
        Map<String, String> overrides = new LinkedHashMap<>();
        if (org != null)
            for (OrgSetting o : repo.findByOrganizationId(org)) overrides.put(o.getSettingKey(), o.getSettingValue());
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SettingsCatalog.Entry e : SettingsCatalog.ENTRIES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.key());
            m.put("label", e.label());
            m.put("help", e.help());
            m.put("type", e.type().name());
            m.put("group", e.group());
            m.put("value", overrides.getOrDefault(e.key(), e.defaultValue()));
            m.put("isDefault", !overrides.containsKey(e.key()));
            out.add(m);
        }
        return out;
    }

    /** Upsert an override for the caller's org. Rejects keys not in the catalog (no free-form settings). */
    @Transactional
    public void set(String key, String value) {
        if (SettingsCatalog.byKey(key) == null)
            throw new IllegalArgumentException("Unknown setting: " + key);
        Long org = CurrentUser.organizationId();
        OrgSetting o = repo.findByOrganizationIdAndSettingKey(org, key)
                .orElseGet(() -> OrgSetting.builder().organizationId(org).settingKey(key).build());
        o.setSettingValue(value);
        o.setUserId(CurrentUser.userId());
        o.setUpdated(LocalDateTime.now());
        repo.save(o);
    }
}
