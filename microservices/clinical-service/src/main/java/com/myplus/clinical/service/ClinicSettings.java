package com.myplus.clinical.service;

import org.springframework.stereotype.Component;

import com.myplus.clinical.config.ClinicSettingsCatalog;
import com.myplus.common.settings.SettingsService;

import lombok.RequiredArgsConstructor;

/** HMS S1 — the clinic settings a behaviour reads, typed at the one place they are read. */
@Component
@RequiredArgsConstructor
public class ClinicSettings {

    private final SettingsService settings;

    public boolean familyOnOnePhone() { return settings.getBool(ClinicSettingsCatalog.FAMILY_ON_ONE_PHONE); }

    public boolean cnicRequired() { return settings.getBool(ClinicSettingsCatalog.CNIC_REQUIRED); }

    /** The code inside the MRN, upper-cased; blank when the owner has not set one. */
    public String mrnCode() {
        String v = settings.getText(ClinicSettingsCatalog.MRN_CODE);
        return v == null ? "" : v.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
