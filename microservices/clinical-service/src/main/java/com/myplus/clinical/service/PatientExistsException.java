package com.myplus.clinical.service;

import com.myplus.clinical.dto.PatientDtos.PatientView;

/**
 * HMS S1 (M-02) — the phone already belongs to a patient of this clinic. Not an error of the system: the front
 * desk's answer, carrying the patient so the screen can open them. Rendered as 200 + success:false + the patient
 * (the platform's refusal envelope), by {@code PatientController}.
 */
public class PatientExistsException extends RuntimeException {

    private final transient PatientView existing;

    public PatientExistsException(PatientView existing) {
        super("This phone is already registered to " + existing.getName() + " — " + existing.getMrn() + ".");
        this.existing = existing;
    }

    public PatientView getExisting() { return existing; }
}
