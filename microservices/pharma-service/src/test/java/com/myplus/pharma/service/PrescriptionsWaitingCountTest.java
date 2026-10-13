package com.myplus.pharma.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.myplus.pharma.entity.Prescription;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;

/**
 * RW-4b — the overview's "prescriptions waiting": PENDING and PARTIALLY_DISPENSED only (never dispensed, expired or
 * cancelled), counted in the caller's OWN branch scope — the same scope as the list the pharmacist sees.
 */
class PrescriptionsWaitingCountTest {

    @Test
    @SuppressWarnings("unchecked")
    void counts_pending_and_partly_dispensed_in_the_callers_branches() {
        PrescriptionRepository repo = mock(PrescriptionRepository.class);
        StoreScope branches = mock(StoreScope.class);
        when(branches.seesAll()).thenReturn(false);
        when(branches.stores()).thenReturn(List.of(7L));
        when(repo.countScopedByStatus(any(), any(), anyBoolean(), any(), any())).thenReturn(4L);
        PrescriptionService service = new PrescriptionService(repo, mock(PrescriptionItemRepository.class),
                mock(PartyBridgeService.class), branches);

        assertThat(service.countWaiting(15L, 82L)).isEqualTo(4L);

        ArgumentCaptor<Collection<Prescription.Status>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(repo).countScopedByStatus(eq(15L), eq(82L), eq(false), eq(List.of(7L)), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                Prescription.Status.PENDING, Prescription.Status.PARTIALLY_DISPENSED);
    }
}
