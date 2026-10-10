package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.clinical.dto.ConsultDtos.EncounterUpdate;
import com.myplus.clinical.dto.ConsultDtos.NoteRequest;
import com.myplus.clinical.entity.ClinicalNote;
import com.myplus.clinical.entity.Encounter;
import com.myplus.clinical.entity.EncounterRxItem;
import com.myplus.clinical.config.PharmaRxClient;
import com.myplus.clinical.repository.EncounterRxItemRepo;
import com.myplus.clinical.dto.ConsultDtos.RxLine;
import com.myplus.clinical.dto.ConsultDtos.RxRequest;
import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.entity.QueueStatus;
import com.myplus.clinical.entity.QueueToken;
import com.myplus.clinical.repository.ClinicalNoteRepo;
import com.myplus.clinical.repository.EncounterRepo;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.clinical.repository.QueueTokenRepo;
import com.myplus.common.web.exception.ValidationException;

/** HMS S3a — the consultation's rules: who may, one visit per token, OPEN-only edits, append-only notes. */
class ConsultServiceTest {

    EncounterRepo encounters; ClinicalNoteRepo notes; QueueTokenRepo tokens; PatientRepo patients;
    QueueService queue; ClinicAccess access; ClinicAuditService audit;
    EncounterRxItemRepo rxItems; PharmaRxClient pharma;
    ConsultService service;
    Encounter enc;

    @BeforeEach
    void setUp() {
        encounters = mock(EncounterRepo.class); notes = mock(ClinicalNoteRepo.class); tokens = mock(QueueTokenRepo.class);
        patients = mock(PatientRepo.class); queue = mock(QueueService.class); access = mock(ClinicAccess.class);
        audit = mock(ClinicAuditService.class);
        rxItems = mock(EncounterRxItemRepo.class); pharma = mock(PharmaRxClient.class);
        service = new ConsultService(encounters, notes, tokens, patients, queue, access, audit, rxItems, pharma);
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(70L);
        Patient p = new Patient(); p.setId(1L); p.setOrganizationId(7L); p.setName("Ali Khan"); p.setMrn("MRN-7-26-000001");
        when(patients.findByIdAndOrganizationId(1L, 7L)).thenReturn(Optional.of(p));
        enc = new Encounter(); enc.setId(5L); enc.setOrganizationId(7L); enc.setTokenId(100L); enc.setPatientId(1L);
        enc.setProviderId(11L); enc.setVersion(3L);
        when(encounters.findByIdAndOrganizationId(5L, 7L)).thenReturn(Optional.of(enc));
        when(encounters.history(eq(7L), eq(1L), eq(5L), any())).thenReturn(List.of());
        when(notes.forEncounters(eq(7L), anyCollection())).thenReturn(List.of());
        when(tokens.findAllById(any())).thenReturn(List.of());
        when(encounters.saveAndFlush(any(Encounter.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void reception_without_clinic_consult_is_refused_before_anything_is_read() {
        doThrow(new org.springframework.security.access.AccessDeniedException("Only a doctor can open clinical records."))
                .when(access).assertCanConsult();
        assertThatThrownBy(() -> service.open(5L)).hasMessageContaining("Only a doctor");
        verify(encounters, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void the_screen_shows_name_mrn_and_records_the_view() {
        var v = service.open(5L);
        assertThat(v.getPatient().getName()).isEqualTo("Ali Khan");
        assertThat(v.getPatient().getMrn()).isEqualTo("MRN-7-26-000001");
        verify(audit).patient(eq("PATIENT_CLINICAL_VIEW"), eq("MRN-7-26-000001"), anyString());
    }

    @Test
    void starting_a_called_token_moves_it_and_a_second_start_returns_the_same_visit() {
        QueueToken t = new QueueToken(); t.setId(100L); t.setOrganizationId(7L); t.setPatientId(1L); t.setProviderId(11L);
        t.setStatus(QueueStatus.CALLED); t.setTokenLabel("A-001");
        when(tokens.findByIdAndOrganizationId(100L, 7L)).thenReturn(Optional.of(t));
        when(encounters.findByOrganizationIdAndTokenId(7L, 100L)).thenReturn(Optional.of(enc));
        assertThat(service.start(100L).getId()).isEqualTo(5L);
        verify(queue).move(100L, "start", null);
        verify(encounters, never()).saveAndFlush(any());   // the existing visit, not a second one
    }

    @Test
    void a_waiting_token_cannot_be_started_without_being_called() {
        QueueToken t = new QueueToken(); t.setId(100L); t.setOrganizationId(7L); t.setStatus(QueueStatus.WAITING); t.setTokenLabel("A-001");
        when(tokens.findByIdAndOrganizationId(100L, 7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.start(100L)).hasMessage("A-001 is waiting — call the patient first.");
    }

    @Test
    void vitals_are_range_checked_and_a_completed_or_stale_visit_is_not_edited() {
        assertThatThrownBy(() -> service.update(5L, EncounterUpdate.builder().temperatureF("986").version(3L).build()))
                .hasMessageContaining("between 90 and 110");
        assertThatThrownBy(() -> service.update(5L, EncounterUpdate.builder().pulse("78").version(2L).build()))
                .hasMessageContaining("changed on another screen");
        var ok = service.update(5L, EncounterUpdate.builder().bloodPressure("120/80").temperatureF("98.6").version(3L).build());
        assertThat(ok.getBloodPressure()).isEqualTo("120/80");
        enc.setStatus(Encounter.COMPLETED);
        assertThatThrownBy(() -> service.update(5L, EncounterUpdate.builder().pulse("78").build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Add a note to correct it");
    }

    @Test
    void a_note_is_appended_and_a_correction_must_point_at_a_note_of_this_visit() {
        when(notes.saveAndFlush(any(ClinicalNote.class))).thenAnswer(inv -> inv.getArgument(0));
        service.note(5L, NoteRequest.builder().body("Throat congested.").build());
        verify(notes).saveAndFlush(any(ClinicalNote.class));
        when(notes.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.note(5L, NoteRequest.builder().body("Correction").amendsNoteId(999L).build()))
                .hasMessageContaining("was not found on this visit");
    }

    // ── S3b-1: the prescription ────────────────────────────────────────────────────────────────────

    private static RxLine line(Long productId, String name, String qty) {
        return RxLine.builder().productId(productId).medicineName(name).quantity(qty).dosage("1 tab").frequency("TDS").duration("5 days").build();
    }

    private EncounterRxItem saved(Long productId, String name, int qty) {
        EncounterRxItem i = new EncounterRxItem(); i.setOrganizationId(7L); i.setEncounterId(5L); i.setLineNo(1);
        i.setProductId(productId); i.setMedicineName(name); i.setQuantity(qty); return i;
    }

    @Test
    void the_prescription_is_saved_as_a_whole_and_each_line_is_checked() {
        service.saveRx(5L, RxRequest.builder().lines(List.of(line(31L, "Panadol 500mg", "10"))).build());
        verify(rxItems).deleteForEncounter(7L, 5L);
        verify(rxItems).save(any(EncounterRxItem.class));
        assertThatThrownBy(() -> service.saveRx(5L, RxRequest.builder().lines(List.of(line(null, "Typed by hand", "10"))).build()))
                .hasMessageContaining("not from the pharmacy's list");
        assertThatThrownBy(() -> service.saveRx(5L, RxRequest.builder().lines(List.of(line(31L, "Panadol 500mg", "0"))).build()))
                .hasMessageContaining("between 1 and 10000");
        assertThatThrownBy(() -> service.saveRx(5L, RxRequest.builder().lines(List.of(line(31L, "Panadol", "1"), line(31L, "Panadol", "2"))).build()))
                .hasMessageContaining("twice");
    }

    @Test
    void a_submitted_prescription_is_frozen() {
        enc.setRxId(900L);
        assertThatThrownBy(() -> service.saveRx(5L, RxRequest.builder().lines(List.of(line(31L, "Panadol 500mg", "10"))).build()))
                .hasMessageContaining("with the pharmacy already");
        verify(rxItems, never()).deleteForEncounter(any(), any());
    }

    @Test
    void submit_sends_one_keyed_prescription_and_a_second_submit_sends_nothing() {
        QueueToken t = new QueueToken(); t.setId(100L); t.setOrganizationId(7L); t.setTokenLabel("A-040"); t.setProviderName("Dr Ahmed");
        when(tokens.findByIdAndOrganizationId(100L, 7L)).thenReturn(Optional.of(t));
        when(rxItems.findByOrganizationIdAndEncounterIdOrderByLineNo(7L, 5L)).thenReturn(List.of(saved(31L, "Panadol 500mg", 10)));
        PharmaRxClient.Rx made = PharmaRxClient.Rx.builder().id(900L).build();
        when(pharma.create(any())).thenReturn(new PharmaRxClient.Envelope(true, "Prescription recorded", made));

        var v = service.submitRx(5L);

        org.mockito.ArgumentCaptor<PharmaRxClient.Rx> sent = org.mockito.ArgumentCaptor.forClass(PharmaRxClient.Rx.class);
        verify(pharma).create(sent.capture());
        assertThat(sent.getValue().getExternalRef()).isEqualTo("enc-5");
        assertThat(sent.getValue().getTokenLabel()).isEqualTo("A-040");
        assertThat(sent.getValue().getDoctorName()).isEqualTo("Dr Ahmed");
        assertThat(sent.getValue().getItems()).hasSize(1);
        assertThat(v.getRxId()).isEqualTo(900L);
        verify(audit).patient(eq("RX_SUBMIT"), eq("MRN-7-26-000001"), anyString());

        service.submitRx(5L);                       // rxId is now set
        verify(pharma, times(1)).create(any());     // never a second call
    }

    @Test
    void an_empty_prescription_is_not_submitted_and_a_pharmacy_refusal_is_said_in_words() {
        when(rxItems.findByOrganizationIdAndEncounterIdOrderByLineNo(7L, 5L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.submitRx(5L)).hasMessageContaining("Add at least one medicine");

        when(rxItems.findByOrganizationIdAndEncounterIdOrderByLineNo(7L, 5L)).thenReturn(List.of(saved(31L, "Panadol 500mg", 10)));
        when(pharma.create(any())).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
        assertThatThrownBy(() -> service.submitRx(5L)).hasMessageContaining("it will not be sent twice");
        assertThat(enc.getRxId()).isNull();
    }

    @Test
    void opening_a_parked_visit_resumes_it() {
        QueueToken t = new QueueToken(); t.setId(100L); t.setOrganizationId(7L); t.setStatus(QueueStatus.PARKED); t.setTokenLabel("A-001");
        when(tokens.findByIdAndOrganizationId(100L, 7L)).thenReturn(Optional.of(t));
        when(encounters.findByOrganizationIdAndTokenId(7L, 100L)).thenReturn(Optional.of(enc));
        service.start(100L);
        verify(queue).move(100L, "resume", null);
    }

    @Test
    void completing_closes_the_token_and_the_visit_together() {
        var v = service.complete(5L);
        verify(queue).move(100L, "complete", null);
        assertThat(v.getStatus()).isEqualTo(Encounter.COMPLETED);
    }
}
