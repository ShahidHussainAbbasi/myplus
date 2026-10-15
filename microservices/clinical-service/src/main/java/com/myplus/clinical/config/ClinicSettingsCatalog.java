package com.myplus.clinical.config;

import java.util.List;

import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingWriteGuard;
import com.myplus.common.settings.SettingsCatalogProvider;

/**
 * HMS S1 — the clinic settings an owner may change (design §1). Only keys a behaviour READS are registered (a
 * registered key nothing reads is silently inert — the B2B-P4b lesson); each names its reader.
 */
@Component
public class ClinicSettingsCatalog implements SettingsCatalogProvider {

    /** Read by PatientService.register: whether reception may add a second patient on one phone. */
    public static final String FAMILY_ON_ONE_PHONE = "clinic.patient.familyOnOnePhone";
    /** Read by PatientService.register / update: whether a CNIC is required (KP government OPD rule). */
    public static final String CNIC_REQUIRED = "clinic.patient.cnicRequired";
    /** Read by QueueService.issue (S2): whether one patient may hold tokens with several doctors in a day. */
    public static final String MULTI_DOCTOR_PER_DAY = "clinic.queue.multiDoctorPerDay";
    /** Read by PatientService.mrn: the clinic code inside the MRN. Blank = the organisation number. */
    public static final String MRN_CODE = "clinic.mrn.code";

    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.bool(FAMILY_ON_ONE_PHONE, "Allow family members on one phone",
                        "Off (default): one patient per phone number; registering a known number opens that patient. "
                                + "On: reception can add a family member on the same number, each with their own MRN. "
                                + "They share one pharmacy customer account (the household).",
                        false, "Clinic"),
                SettingEntry.bool(CNIC_REQUIRED, "Require CNIC at registration",
                        "Off (default): only the phone is required. On: a patient cannot be registered without a CNIC.",
                        false, "Clinic"),
                SettingEntry.bool(MULTI_DOCTOR_PER_DAY, "Allow several doctors for one patient in a day",
                        "On (default): a patient can have a token with more than one doctor the same day, seen one after "
                                + "the other. Off: one token per patient per day.",
                        true, "Clinic"),
                SettingEntry.text(MRN_CODE, "Clinic code in the MRN",
                        "Two to six letters or digits, e.g. ISB gives MRN-ISB-26-000123. Blank uses your "
                                + "organisation number. Set it before the first patient: existing MRNs never change.",
                        "", "Clinic"));
    }

    @org.springframework.context.annotation.Bean
    public SettingWriteGuard clinicMrnCodeGuard() {
        return (org, key, value) -> {
            if (!MRN_CODE.equals(key) || value == null || value.isBlank()) return;
            if (!value.trim().matches("[A-Za-z0-9]{2,6}"))
                throw new IllegalArgumentException("The clinic code must be 2 to 6 letters or digits, e.g. ISB.");
        };
    }
}
