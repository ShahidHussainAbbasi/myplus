package com.myplus.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HMS S2 — the start-up repair moves doctors attached to another organisation's venue to quarantine, but never one
 * that has bookings or slots (unprovable → reported, never guessed), and does nothing when all is well.
 */
class ProviderIntegrityRepairTest {

    JdbcTemplate jdbc;
    ProviderIntegrityRepair repair;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        repair = new ProviderIntegrityRepair(jdbc, tx);
        when(jdbc.queryForList(ProviderIntegrityRepair.COLUMNS, String.class)).thenReturn(List.of("id", "organization_id", "venue_id", "name"));
    }

    @Test
    void a_clean_database_changes_nothing() {
        when(jdbc.queryForList(ProviderIntegrityRepair.FIND)).thenReturn(List.of());
        ProviderIntegrityRepair.Result r = repair.repair();
        assertThat(r.quarantined()).isEmpty();
        assertThat(r.leftReferenced()).isEmpty();
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void an_unreferenced_cross_org_doctor_is_copied_then_removed_and_a_referenced_one_is_only_reported() {
        when(jdbc.queryForList(ProviderIntegrityRepair.FIND)).thenReturn(List.of(
                Map.of("id", 41L, "provider_org", 6L, "venue_org", 9L, "referenced", 0L),
                Map.of("id", 42L, "provider_org", 6L, "venue_org", 9L, "referenced", 1L)));

        ProviderIntegrityRepair.Result r = repair.repair();

        assertThat(r.quarantined()).containsExactly(41L);
        assertThat(r.leftReferenced()).containsExactly(42L);
        // copy first, with the shared columns named explicitly (never SELECT *), and only rows not already there
        verify(jdbc).update(argThat((String sql) -> sql.startsWith("INSERT INTO provider_quarantine (`id`, `organization_id`, `venue_id`, `name`, quarantined_at")
                && sql.contains("WHERE id IN (41)") && sql.contains("NOT IN (SELECT id FROM provider_quarantine)")));
        // then remove — only what the copy holds; 42 is never touched
        verify(jdbc).update(eq("DELETE FROM provider WHERE id IN (41) AND id IN (SELECT id FROM provider_quarantine)"));
    }

    @Test
    void a_failure_never_stops_the_service_from_starting() {
        when(jdbc.queryForList(ProviderIntegrityRepair.FIND)).thenThrow(new IllegalStateException("table missing"));
        repair.onStart();   // must not throw
    }
}
