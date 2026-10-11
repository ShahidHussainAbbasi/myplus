package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.AmendRequest;
import com.myplus.pharma.dto.PrescriptionItemDTO;
import com.myplus.pharma.entity.Prescription;
import com.myplus.pharma.entity.PrescriptionAmendment;
import com.myplus.pharma.entity.PrescriptionItem;
import com.myplus.pharma.repository.PrescriptionAmendmentRepository;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;

/**
 * HMS H3b — the pharmacist changes the prescription itself (client ruling L-3): a reason, never below what was
 * dispensed, a dispensed line neither removed nor substituted, new medicines from this pharmacy's catalogue, a stale
 * screen refused — and every change recorded before → after.
 */
class PrescriptionAmendServiceTest {

    PrescriptionRepository rxRepo; PrescriptionItemRepository itemRepo; PrescriptionAmendmentRepository amendRepo;
    PrescribedProductCheck catalogue; PrescriptionService rxService;
    PrescriptionAmendService service;
    Prescription rx; PrescriptionItem panadol, amoxil;

    @BeforeEach
    void setUp() {
        rxRepo = mock(PrescriptionRepository.class); itemRepo = mock(PrescriptionItemRepository.class);
        amendRepo = mock(PrescriptionAmendmentRepository.class); catalogue = mock(PrescribedProductCheck.class);
        rxService = mock(PrescriptionService.class);
        StoreScope branches = mock(StoreScope.class);
        when(branches.canAccess(any())).thenReturn(true);
        service = new PrescriptionAmendService(rxRepo, itemRepo, amendRepo, catalogue, rxService, branches);
        rx = Prescription.builder().id(9L).organizationId(1L).status(Prescription.Status.PARTIALLY_DISPENSED)
                .source(Prescription.SOURCE_DOCTOR).patientName("Ali").build();
        panadol = PrescriptionItem.builder().id(100L).prescription(rx).productId(31L).medicineName("Panadol").quantity(10)
                .dosage("1 tab").dispensedQuantity(4).build();
        amoxil = PrescriptionItem.builder().id(101L).prescription(rx).productId(32L).medicineName("Amoxil").quantity(15)
                .dosage("1 cap").dispensedQuantity(0).build();
        when(rxRepo.findByIdScoped(9L, 1L, 5L)).thenReturn(Optional.of(rx));
        when(itemRepo.findByPrescriptionId(9L)).thenReturn(new ArrayList<>(List.of(panadol, amoxil)));
        when(itemRepo.save(any(PrescriptionItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(amendRepo.countByOrganizationIdAndPrescriptionId(1L, 9L)).thenReturn(0L);
    }

    private static PrescriptionItemDTO line(Long id, long productId, String name, int qty, String dose) {
        PrescriptionItemDTO d = new PrescriptionItemDTO();
        d.setId(id); d.setProductId(productId); d.setMedicineName(name); d.setQuantity(qty); d.setDosage(dose);
        return d;
    }

    private static AmendRequest req(String reason, PrescriptionItemDTO... lines) {
        AmendRequest r = new AmendRequest();
        r.setReason(reason);
        r.setAmendmentsSeen(0L);
        r.getItems().addAll(List.of(lines));
        return r;
    }

    @Test
    void a_change_is_saved_and_recorded_before_and_after() {
        service.amend(9L, req("500mg not in stock", line(100L, 31, "Panadol", 10, "1+0+1"), line(101L, 32, "Amoxil", 15, "1 cap")), 1L, 5L);
        ArgumentCaptor<PrescriptionAmendment> a = ArgumentCaptor.forClass(PrescriptionAmendment.class);
        verify(amendRepo).save(a.capture());
        assertThat(a.getValue().getKind()).isEqualTo("AMEND");
        assertThat(a.getValue().getReason()).isEqualTo("500mg not in stock");
        assertThat(a.getValue().getAmendedBy()).isEqualTo(5L);
        assertThat(a.getValue().getChanges()).contains("\"before\"").contains("1 tab").contains("1+0+1").doesNotContain("Amoxil");
        assertThat(panadol.getDosage()).isEqualTo("1+0+1");
    }

    @Test
    void a_reason_is_required_and_nothing_changed_is_refused() {
        assertThatThrownBy(() -> service.amend(9L, req(" ", line(100L, 31, "Panadol", 10, "1 tab")), 1L, 5L))
                .hasMessageContaining("Say why");
        assertThatThrownBy(() -> service.amend(9L, req("x", line(100L, 31, "Panadol", 10, "1 tab"), line(101L, 32, "Amoxil", 15, "1 cap")), 1L, 5L))
                .hasMessage("Nothing was changed.");
        verify(amendRepo, never()).save(any());
    }

    @Test
    void never_below_what_was_dispensed_and_a_dispensed_line_is_neither_removed_nor_substituted() {
        assertThatThrownBy(() -> service.amend(9L, req("x", line(100L, 31, "Panadol", 3, "1 tab"), line(101L, 32, "Amoxil", 15, "1 cap")), 1L, 5L))
                .hasMessageContaining("4 already dispensed");
        assertThatThrownBy(() -> service.amend(9L, req("x", line(101L, 32, "Amoxil", 15, "1 cap")), 1L, 5L))
                .hasMessage("'Panadol' was partly dispensed; it cannot be removed.");
        assertThatThrownBy(() -> service.amend(9L, req("x", line(100L, 33, "Calpol", 10, "1 tab"), line(101L, 32, "Amoxil", 15, "1 cap")), 1L, 5L))
                .hasMessageContaining("was partly dispensed; add the other medicine as a new line");
        verify(amendRepo, never()).save(any());
    }

    @Test
    void an_undispensed_line_may_be_substituted_or_removed_and_a_new_medicine_is_checked_against_the_catalogue() {
        doThrow(new ValidationException("'Ghost' is not in this pharmacy's list. Choose it again from the list."))
                .when(catalogue).assertInCatalogue(any());
        assertThatThrownBy(() -> service.amend(9L, req("x", line(100L, 31, "Panadol", 10, "1 tab"), line(null, 99, "Ghost", 1, "1")), 1L, 5L))
                .hasMessageContaining("not in this pharmacy's list");
        verify(amendRepo, never()).save(any());
    }

    @Test
    void removing_the_undispensed_line_is_recorded_as_removed() {
        service.amend(9L, req("patient has it at home", line(100L, 31, "Panadol", 10, "1 tab")), 1L, 5L);
        verify(itemRepo).delete(amoxil);
        ArgumentCaptor<PrescriptionAmendment> a = ArgumentCaptor.forClass(PrescriptionAmendment.class);
        verify(amendRepo).save(a.capture());
        assertThat(a.getValue().getChanges()).contains("Amoxil").contains("\"after\":null");
    }

    @Test
    void a_stale_screen_is_refused_and_so_are_finished_prescriptions() {
        when(amendRepo.countByOrganizationIdAndPrescriptionId(1L, 9L)).thenReturn(1L);
        assertThatThrownBy(() -> service.amend(9L, req("x", line(100L, 31, "Panadol", 10, "2 tab")), 1L, 5L))
                .hasMessageContaining("changed on another screen");
        rx.setStatus(Prescription.Status.FULLY_DISPENSED);
        assertThatThrownBy(() -> service.amend(9L, req("x", line(100L, 31, "Panadol", 10, "2 tab")), 1L, 5L))
                .hasMessage("A fully dispensed prescription cannot be changed.");
    }

    @Test
    void cancelling_a_doctors_prescription_needs_a_reason_the_doctor_will_see() {
        assertThatThrownBy(() -> service.cancel(9L, " ", 1L, 5L)).hasMessageContaining("the doctor sees it");
        verify(rxService, never()).cancel(anyLong(), anyLong(), anyLong());
        service.cancel(9L, "Patient refused", 1L, 5L);
        verify(rxService).cancel(9L, 1L, 5L);
        ArgumentCaptor<PrescriptionAmendment> a = ArgumentCaptor.forClass(PrescriptionAmendment.class);
        verify(amendRepo).save(a.capture());
        assertThat(a.getValue().getKind()).isEqualTo("CANCEL");
        assertThat(a.getValue().getReason()).isEqualTo("Patient refused");
    }
}
