package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.myplus.pharma.config.LinkedRxClients;
import com.myplus.pharma.dto.AmendmentView;
import com.myplus.pharma.dto.PrescriptionDTO;

/** HC-1d — the pharmacy side of a linked clinic's prescription: who may send, what crosses, where it is written. */
class LinkedRxServiceTest {

    static final long CLINIC = 10L, PHARMACY = 20L;

    LinkedRxClients.Route routes = mock(LinkedRxClients.Route.class);
    LinkedRxClients.Medicines catalog = mock(LinkedRxClients.Medicines.class);
    PrescribedProductCheck check = mock(PrescribedProductCheck.class);
    PrescriptionService prescriptions = mock(PrescriptionService.class);
    PrescriptionAmendService amendments = mock(PrescriptionAmendService.class);
    LinkedRxService svc = new LinkedRxService(routes, catalog, check, prescriptions, amendments);

    void routeIs(String route) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("route", route);
        if ("LINKED".equals(route)) {
            data.put("pharmacyOrgId", PHARMACY);
            data.put("pharmacyName", "Tahseen Pharmacy");
            data.put("clinicName", "Tahseen Clinic");
        }
        when(routes.route()).thenReturn(Map.of("success", true, "data", data));
    }

    static PrescriptionDTO rx() {
        PrescriptionDTO d = new PrescriptionDTO();
        d.setPatientName("Patient");
        d.setEncounterId(5L);
        return d;
    }

    @Test
    void a_clinic_with_no_link_is_refused_by_code_and_nothing_is_written() {
        routeIs("NONE");
        assertThatThrownBy(() -> svc.send(rx(), CLINIC))
                .isInstanceOfSatisfying(LinkedRxService.LinkedRxRefusedException.class, e -> assertThat(e.getCode()).isEqualTo(LinkedRxService.NO_LINK));
        verify(prescriptions, never()).createFromLinkedClinic(any(), any(), any(), any());
        routeIs("SAME_ORG");   // a pharmacy or a shop calling this path is refused the same way
        assertThatThrownBy(() -> svc.medicines()).isInstanceOf(LinkedRxService.LinkedRxRefusedException.class);
    }

    @Test
    void the_list_carries_id_name_and_strength_only() {
        routeIs("LINKED");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7);
        row.put("name", "Amoxil 500mg");
        row.put("formula", "Amoxicillin 500mg");
        row.put("sellingPrice", new BigDecimal("37.00"));
        row.put("requiresSerial", false);
        when(catalog.picker(anyInt(), anyInt())).thenReturn(Map.of("data", Map.of("content", List.of(row), "last", true)));
        Map<String, Object> out = svc.medicines();
        assertThat(out.get("pharmacyName")).isEqualTo("Tahseen Pharmacy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> meds = (List<Map<String, Object>>) out.get("medicines");
        assertThat(meds).hasSize(1);
        assertThat(meds.get(0)).containsOnlyKeys("id", "name", "formula").containsEntry("id", 7L);
    }

    @Test
    void the_list_reads_every_page_until_the_last() {
        routeIs("LINKED");
        when(catalog.picker(eq(0), anyInt())).thenReturn(Map.of("data", Map.of("content", List.of(Map.of("id", 1, "name", "A")), "last", false)));
        when(catalog.picker(eq(1), anyInt())).thenReturn(Map.of("data", Map.of("content", List.of(Map.of("id", 2, "name", "B")), "last", true)));
        assertThat((List<?>) svc.medicines().get("medicines")).hasSize(2);
    }

    @Test
    void a_send_is_written_in_the_PHARMACY_with_the_clinic_as_sender() {
        routeIs("LINKED");
        PrescriptionDTO saved = new PrescriptionDTO();
        saved.setId(99L);
        when(prescriptions.createFromLinkedClinic(any(), eq(PHARMACY), eq(CLINIC), eq("Tahseen Clinic"))).thenReturn(saved);
        PrescriptionDTO out = svc.send(rx(), CLINIC);
        verify(check).assertInCatalogue(any());
        assertThat(out.getId()).isEqualTo(99L);
        assertThat(out.getFromOrgName()).as("the clinic is told where it went").isEqualTo("Tahseen Pharmacy");
    }

    @Test
    void a_medicine_not_in_the_pharmacy_list_is_refused_before_anything_is_written() {
        routeIs("LINKED");
        org.mockito.Mockito.doThrow(new com.myplus.common.web.exception.ValidationException("'X' is not in this pharmacy's list."))
                .when(check).assertInCatalogue(any());
        assertThatThrownBy(() -> svc.send(rx(), CLINIC)).hasMessageContaining("not in this pharmacy");
        verify(prescriptions, never()).createFromLinkedClinic(any(), any(), any(), any());
    }

    @Test
    void calls_made_as_the_pharmacy_carry_a_user_id_or_the_callee_refuses_them() {
        // HC-1d gate 2026-10-16: runAs(null, pharmacy) reached catalog-service with no X-User-Id → 403, empty list
        assertThat(LinkedRxService.SERVICE_USER).isNotNull().isEqualTo(0L);
    }

    @Test
    void the_change_history_never_names_the_pharmacist() {
        when(prescriptions.pharmacyOfLinked(99L, CLINIC)).thenReturn(PHARMACY);
        when(amendments.history(99L, PHARMACY, null)).thenReturn(List.of(AmendmentView.builder().id(1L).amendedBy(555L).reason("matched").build()));
        List<AmendmentView> out = svc.amendments(99L, CLINIC);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getAmendedBy()).isNull();
        assertThat(out.get(0).getReason()).isEqualTo("matched");
    }
}
