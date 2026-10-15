package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.pharma.dto.PrescriptionDTO;

/** HMS S3b-1 — only the doctor's Submit (clinic.consult) may mark a prescription as a visit's. */
class DoctorPrescriptionClaimTest {

    private static PrescriptionDTO withRef(String ref) {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setExternalRef(ref);
        return d;
    }

    @Test
    void the_counter_cannot_claim_a_visit() {
        assertThatThrownBy(() -> PrescriptionService.assertMayClaimVisit(withRef("enc-5"), false))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Only a doctor's Submit");
    }

    @Test
    void the_doctor_may_and_an_ordinary_prescription_needs_nothing() {
        assertThatCode(() -> PrescriptionService.assertMayClaimVisit(withRef("enc-5"), true)).doesNotThrowAnyException();
        assertThatCode(() -> PrescriptionService.assertMayClaimVisit(withRef(null), false)).doesNotThrowAnyException();
        assertThatCode(() -> PrescriptionService.assertMayClaimVisit(withRef("  "), false)).doesNotThrowAnyException();
    }
}
