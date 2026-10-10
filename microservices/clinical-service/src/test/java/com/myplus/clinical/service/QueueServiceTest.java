package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.clinical.config.AppointmentDirectoryClient;
import com.myplus.clinical.config.AppointmentDirectoryClient.Doctor;
import com.myplus.clinical.config.AppointmentDirectoryClient.Envelope;
import com.myplus.clinical.dto.QueueDtos.IssueRequest;
import com.myplus.clinical.dto.QueueDtos.TokenView;
import com.myplus.clinical.entity.ClinicProvider;
import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.entity.ProviderDay;
import com.myplus.clinical.entity.QueueStatus;
import com.myplus.clinical.entity.QueueToken;
import com.myplus.clinical.repository.ClinicProviderRepo;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.clinical.repository.ProviderDayRepo;
import com.myplus.clinical.repository.QueueTokenRepo;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

/** HMS S2 — issuing a token and moving it: the refusals are the product, so each one is pinned. */
class QueueServiceTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 9);

    QueueTokenRepo tokens; PatientRepo patients; ClinicProviderRepo prefixes; ProviderDayRepo days; TokenWriter writer;
    AppointmentDirectoryClient directory; ClinicAccess access; ClinicSettings settings; ClinicAuditService audit;
    QueueService service;
    Patient ali;

    @BeforeEach
    void setUp() {
        TenantClock.useClock(java.time.Clock.fixed(DAY.atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant(), ZoneId.of("Asia/Karachi")));
        tokens = mock(QueueTokenRepo.class); patients = mock(PatientRepo.class); prefixes = mock(ClinicProviderRepo.class);
        days = mock(ProviderDayRepo.class); writer = mock(TokenWriter.class); directory = mock(AppointmentDirectoryClient.class);
        access = mock(ClinicAccess.class); settings = mock(ClinicSettings.class); audit = mock(ClinicAuditService.class);
        service = new QueueService(tokens, patients, prefixes, days, writer, directory, access, settings, audit);
        when(access.org()).thenReturn(7L);
        when(settings.multiDoctorPerDay()).thenReturn(true);
        ali = new Patient(); ali.setId(1L); ali.setOrganizationId(7L); ali.setName("Ali Khan"); ali.setMrn("MRN-7-26-000001");
        when(patients.findByIdAndOrganizationId(1L, 7L)).thenReturn(Optional.of(ali));
        when(directory.doctors()).thenReturn(new Envelope<>(true, null, List.of(
                Doctor.builder().id(11L).name("Dr Ahmed").appointmentOfferType("count").appointmentOfferValue(2).build(),
                Doctor.builder().id(12L).name("Dr Sana").build())));
        ClinicProvider a = new ClinicProvider(); a.setProviderId(11L); a.setTokenPrefix("A");
        when(prefixes.findByOrganizationIdAndProviderId(7L, 11L)).thenReturn(Optional.of(a));
        when(tokens.patientTokens(eq(7L), eq(1L), eq(DAY), anyCollection())).thenReturn(List.of());
        when(days.findById(any())).thenReturn(Optional.empty());
        when(writer.insert(any(QueueToken.class), eq("A"))).thenAnswer(inv -> {
            QueueToken t = inv.getArgument(0); t.setId(100L); t.setTokenNo(1); t.setTokenLabel("A-001"); return t;
        });
    }

    @AfterEach
    void clock() { TenantClock.useClock(null); }

    private static QueueToken token(long id, long provider, String providerName, String label, String status) {
        QueueToken t = new QueueToken();
        t.setId(id); t.setOrganizationId(7L); t.setPatientId(1L); t.setProviderId(provider); t.setProviderName(providerName);
        t.setTokenLabel(label); t.setStatus(status); t.setVisitDate(DAY); t.setTokenNo(1);
        return t;
    }

    @Test
    void a_token_is_issued_waiting_with_the_doctors_letter() {
        TokenView v = service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build());
        assertThat(v.getTokenLabel()).isEqualTo("A-001");
        assertThat(v.getStatus()).isEqualTo(QueueStatus.WAITING);
        assertThat(v.getProviderName()).isEqualTo("Dr Ahmed");
    }

    @Test
    void the_same_doctor_twice_in_a_day_is_refused_naming_the_first_token() {
        when(tokens.patientTokens(eq(7L), eq(1L), eq(DAY), anyCollection()))
                .thenReturn(List.of(token(100, 11, "Dr Ahmed", "A-001", QueueStatus.WAITING)));
        assertThatThrownBy(() -> service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already has token A-001 with Dr Ahmed");
        verify(writer, never()).insert(any(), anyString());
    }

    @Test
    void another_doctor_the_same_day_is_allowed_unless_the_clinic_turned_that_off() {
        when(tokens.patientTokens(eq(7L), eq(1L), eq(DAY), anyCollection()))
                .thenReturn(List.of(token(100, 12, "Dr Sana", "B-001", QueueStatus.WAITING)));
        assertThat(service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()).getTokenLabel()).isEqualTo("A-001");

        when(settings.multiDoctorPerDay()).thenReturn(false);
        assertThatThrownBy(() -> service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()))
                .hasMessageContaining("one token per patient per day");
    }

    @Test
    void the_daily_limit_and_a_closed_day_are_refused_in_words() {
        when(tokens.countIssued(7L, 11L, DAY)).thenReturn(2L);
        assertThatThrownBy(() -> service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()))
                .hasMessageContaining("Dr Ahmed's limit for today (2 patients) is reached");

        ProviderDay closed = new ProviderDay(); closed.setClosed(true);
        when(days.findById(any())).thenReturn(Optional.of(closed));
        assertThatThrownBy(() -> service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()))
                .hasMessageContaining("not seeing patients today");
    }

    @Test
    void a_one_day_no_limit_lifts_the_usual_limit() {
        when(tokens.countIssued(7L, 11L, DAY)).thenReturn(2L);
        ProviderDay open = new ProviderDay(); open.setCap(0); open.setClosed(false);
        when(days.findById(any())).thenReturn(Optional.of(open));
        assertThat(service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()).getTokenLabel()).isEqualTo("A-001");
    }

    @Test
    void a_doctor_of_another_clinic_answers_not_found() {
        assertThatThrownBy(() -> service.issue(IssueRequest.builder().patientId(1L).providerId(999L).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void a_completed_token_cannot_be_called_and_the_refusal_says_why() {
        when(tokens.findByIdAndOrganizationId(100L, 7L)).thenReturn(Optional.of(token(100, 11, "Dr Ahmed", "A-001", QueueStatus.COMPLETED)));
        when(writer.transition(eq(100L), eq(7L), anyCollection(), eq(QueueStatus.CALLED), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.move(100L, "call", null))
                .hasMessage("A-001 is completed — it cannot be called.");
    }

    @Test
    void a_patient_with_one_doctor_cannot_be_called_by_another() {
        when(tokens.findByIdAndOrganizationId(101L, 7L)).thenReturn(Optional.of(token(101, 12, "Dr Sana", "B-001", QueueStatus.WAITING)));
        when(tokens.patientTokens(eq(7L), eq(1L), eq(DAY), eq(QueueStatus.WITH_DOCTOR)))
                .thenReturn(List.of(token(100, 11, "Dr Ahmed", "A-001", QueueStatus.IN_CONSULTATION)));
        assertThatThrownBy(() -> service.move(101L, "call", null)).hasMessageContaining("with Dr Ahmed now (A-001)");
        verify(writer, never()).transition(anyLong(), anyLong(), anyCollection(), anyString(), any(), any());
    }

    @Test
    void a_slow_doctor_list_is_retried_once_and_then_said_in_words_never_a_500() {
        org.springframework.web.client.ResourceAccessException timeout =
                new org.springframework.web.client.ResourceAccessException("Read timed out");
        // first call times out, the retry answers: the token is issued
        when(directory.doctors()).thenThrow(timeout).thenReturn(new Envelope<>(true, null, List.of(
                Doctor.builder().id(11L).name("Dr Ahmed").build())));
        assertThat(service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()).getTokenLabel()).isEqualTo("A-001");

        // both time out: a sentence, as a refusal (ValidationException → 400 / success:false), not an unhandled error
        when(directory.doctors()).thenThrow(timeout);
        assertThatThrownBy(() -> service.issue(IssueRequest.builder().patientId(1L).providerId(11L).build()))
                .isInstanceOf(ValidationException.class).hasMessage("The doctor list is not reachable right now. Try again in a moment.");
    }

    @Test
    void parking_needs_a_reason() {
        when(tokens.findByIdAndOrganizationId(100L, 7L)).thenReturn(Optional.of(token(100, 11, "Dr Ahmed", "A-001", QueueStatus.IN_CONSULTATION)));
        assertThatThrownBy(() -> service.move(100L, "park", " ")).hasMessageContaining("Say why");
    }
}
