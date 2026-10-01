package com.myplus.common.docnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * EX-0c — the allocation ORDER is the algorithm. Two earlier orders broke in production-like tests (a deadlock,
 * then a gap-lock timeout), so this pins the third: read → create-if-missing → bump → read. The real locking is
 * proven against MySQL by business OrgDocumentSeqConcurrencyTest and finance ReceiptNumberConcurrencyTest.
 */
class DocumentNumberServiceTest {

    /** An in-memory counter table that records every statement in order. */
    static final class FakeStore implements DocumentCounterStore {
        final Map<String, Long> rows = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean vanishOnBump;
        @Override public Long current(Long o, String t) { calls.add("current"); return rows.get(o + "/" + t); }
        @Override public int bump(Long o, String t) {
            calls.add("bump");
            if (vanishOnBump) return 0;
            return rows.computeIfPresent(o + "/" + t, (k, v) -> v + 1) == null ? 0 : 1;
        }
        @Override public void createCounterAtZero(Long o, String t) { calls.add("create"); rows.putIfAbsent(o + "/" + t, 0L); }
    }

    private static DocumentNumberService svc(FakeStore store) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("store", store);
        // No proxy in a unit test: self() falls back to this instance, which is what the order assertions need.
        return new DocumentNumberService(bf.getBeanProvider(DocumentCounterStore.class),
                bf.getBeanProvider(DocumentNumberService.class));
    }

    @Test @DisplayName("a tenant's first document: read, create at zero, bump, read back → 1")
    void first_allocation_order() {
        FakeStore store = new FakeStore();
        assertThat(svc(store).next(7L, "EXPENSE")).isEqualTo(1L);
        assertThat(store.calls).containsExactly("current", "create", "bump", "current");
    }

    @Test @DisplayName("later documents never create, and number on in sequence")
    void sequence() {
        FakeStore store = new FakeStore();
        DocumentNumberService s = svc(store);
        s.next(7L, "EXPENSE");
        store.calls.clear();
        assertThat(s.next(7L, "EXPENSE")).isEqualTo(2L);
        assertThat(store.calls).containsExactly("current", "bump", "current");
        assertThat(s.next(7L, "EXPENSE")).isEqualTo(3L);
    }

    @Test @DisplayName("each org and each document type has its own series")
    void independent_series() {
        DocumentNumberService s = svc(new FakeStore());
        s.next(7L, "EXPENSE"); s.next(7L, "EXPENSE");
        assertThat(s.next(8L, "EXPENSE")).as("another tenant starts at 1").isEqualTo(1L);
        assertThat(s.next(7L, "INVOICE")).as("another type starts at 1").isEqualTo(1L);
    }

    @Test @DisplayName("no organisation → refused before any statement runs")
    void org_required() {
        FakeStore store = new FakeStore();
        assertThatThrownBy(() -> svc(store).next(null, "EXPENSE")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.calls).isEmpty();
    }

    @Test @DisplayName("a counter that vanishes is refused loudly, never numbered zero")
    void vanished_counter() {
        FakeStore store = new FakeStore();
        store.vanishOnBump = true;
        assertThatThrownBy(() -> svc(store).next(7L, "EXPENSE")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vanished");
    }

    @Test @DisplayName("a service with no store fails with a message that says how to fix it")
    void missing_store() {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        DocumentNumberService s = new DocumentNumberService(bf.getBeanProvider(DocumentCounterStore.class),
                bf.getBeanProvider(DocumentNumberService.class));
        assertThatThrownBy(() -> s.next(7L, "EXPENSE")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DocumentCounterStore");
    }
}
