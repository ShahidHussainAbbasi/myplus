package com.myplus.clinical.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.clinical.dto.PatientDtos.PatientView;
import com.myplus.clinical.dto.PatientDtos.RegisterRequest;
import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.PartyCustomerRef;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.common.web.exception.ValidationException;

/**
 * HMS S1 — the register's decisions (design §1): one patient per phone, the refusal names who is on it, a race is
 * the same refusal, family members only when allowed, and the customer link never loses a registration.
 */
class PatientServiceTest {

    PatientRepo repo; PatientWriter writer; ClinicAccess access; ClinicSettings settings; ClinicAuditService audit;
    PartyClient party; TradeClient trade; TransactionTemplate tx;
    PatientService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(PatientRepo.class); writer = mock(PatientWriter.class); access = mock(ClinicAccess.class);
        settings = mock(ClinicSettings.class); audit = mock(ClinicAuditService.class);
        party = mock(PartyClient.class); trade = mock(TradeClient.class); tx = mock(TransactionTemplate.class);
        when(access.org()).thenReturn(7L);
        when(access.userId()).thenReturn(70L);
        when(settings.mrnCode()).thenReturn("ISB");
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(repo.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PatientService(repo, writer, access, settings, audit, party, trade, tx);
    }

    private static Patient patient(long id, String name, int seq) {
        Patient p = new Patient();
        p.setId(id); p.setOrganizationId(7L); p.setName(name); p.setPhone("03001234567"); p.setPhoneKey("3001234567");
        p.setFamilySeq(seq); p.setMrn("MRN-ISB-26-00000" + id);
        return p;
    }

    private void insertsAs(long id) {
        when(writer.insert(any(Patient.class), eq("ISB"))).thenAnswer(inv -> {
            Patient p = inv.getArgument(0);
            p.setId(id); p.setMrn("MRN-ISB-26-00000" + id);
            when(repo.lockScoped(id, 7L)).thenReturn(Optional.of(p));
            return p;
        });
    }

    @Test
    void a_new_phone_registers_with_only_the_phone_and_links_the_person_and_the_customer() {
        when(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(7L, "3001234567")).thenReturn(List.of());
        insertsAs(1);
        when(party.upsert(any())).thenReturn(PartyRef.builder().id(500L).build());
        when(trade.customerForParty(any())).thenReturn(PartyCustomerRef.builder().customerId(900L).created(true).build());

        PatientView v = service.register(RegisterRequest.builder().phone("+92 300 1234567").build());

        assertThat(v.getName()).isEqualTo("03001234567");
        assertThat(v.getPartyId()).isEqualTo(500L);
        assertThat(v.getCustomerId()).isEqualTo(900L);
        assertThat(v.isLinkPending()).isFalse();
        verify(audit).patient(eq("PATIENT_REGISTER"), eq("MRN-ISB-26-000001"), anyString());
    }

    @Test
    void a_known_phone_is_refused_with_the_patient_already_on_it_even_under_another_name() {
        when(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(7L, "3001234567"))
                .thenReturn(List.of(patient(1, "Rashid Ahmed", 0)));

        assertThatThrownBy(() -> service.register(RegisterRequest.builder().phone("03001234567").name("Usman Rashid").build()))
                .isInstanceOf(PatientExistsException.class)
                .hasMessageContaining("Rashid Ahmed").hasMessageContaining("MRN-ISB-26-000001");
        verify(writer, never()).insert(any(), anyString());
    }

    @Test
    void two_desks_at_once_the_loser_gets_the_same_answer_not_an_error() {
        when(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(7L, "3001234567"))
                .thenReturn(List.of())                                    // both desks' pre-check passes
                .thenReturn(List.of(patient(1, "Ali Khan", 0)));           // the winner, read after the loss
        when(writer.insert(any(), anyString())).thenThrow(new DataIntegrityViolationException("uq_patient_phone"));

        assertThatThrownBy(() -> service.register(RegisterRequest.builder().phone("03001234567").build()))
                .isInstanceOf(PatientExistsException.class).hasMessageContaining("Ali Khan");
    }

    @Test
    void a_family_member_is_refused_while_the_clinic_keeps_one_patient_per_phone() {
        when(settings.familyOnOnePhone()).thenReturn(false);
        when(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(7L, "3001234567"))
                .thenReturn(List.of(patient(1, "Rashid Ahmed", 0)));

        assertThatThrownBy(() -> service.register(RegisterRequest.builder()
                .phone("03001234567").name("Usman Rashid").addFamilyMember(true).build()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("one patient per phone");
    }

    @Test
    void with_family_allowed_the_next_member_gets_the_next_seat_on_the_number() {
        when(settings.familyOnOnePhone()).thenReturn(true);
        when(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(7L, "3001234567"))
                .thenReturn(List.of(patient(1, "Rashid Ahmed", 0)));
        when(repo.maxFamilySeq(7L, "3001234567")).thenReturn(0);
        insertsAs(2);

        PatientView v = service.register(RegisterRequest.builder()
                .phone("03001234567").name("Usman Rashid").addFamilyMember(true).build());

        assertThat(v.getFamilySeq()).isEqualTo(1);
    }

    @Test
    void party_service_down_keeps_the_patient_and_says_the_link_is_pending() {
        when(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(7L, "3001234567")).thenReturn(List.of());
        insertsAs(3);
        when(party.upsert(any())).thenThrow(new IllegalStateException("connect timed out"));

        PatientView v = service.register(RegisterRequest.builder().phone("03001234567").name("Ali Khan").build());

        assertThat(v.getMrn()).isEqualTo("MRN-ISB-26-000003");
        assertThat(v.isLinkPending()).isTrue();
        verify(trade, never()).customerForParty(any());
    }

    @Test
    void the_module_switched_off_refuses_before_anything_is_read() {
        org.mockito.Mockito.doThrow(new ValidationException("The clinic is not switched on")).when(access).assertModuleOn();
        assertThatThrownBy(() -> service.lookup("03001234567")).hasMessageContaining("not switched on");
        verify(repo, never()).findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(any(), any());
    }
}
