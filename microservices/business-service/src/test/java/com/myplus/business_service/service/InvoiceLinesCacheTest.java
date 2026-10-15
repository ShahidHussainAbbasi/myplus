package com.myplus.business_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.business_service.dto.SellDTO;

/** INV-CACHE — the cache-aside rules for a printed invoice's lines (no Spring, no database). */
class InvoiceLinesCacheTest {

    private static InvoiceLinesCache.Lines lines(String name) {
        SellDTO s = new SellDTO();
        s.setItemName(name);
        return new InvoiceLinesCache.Lines(List.of(s), BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a second print is served from the cache — the database is read once")
    void hit() {
        InvoiceLinesCache c = new InvoiceLinesCache(300);
        AtomicInteger reads = new AtomicInteger();
        InvoiceLinesCache.Lines first = c.get(7L, 100L, () -> { reads.incrementAndGet(); return lines("Panadol"); });
        InvoiceLinesCache.Lines second = c.get(7L, 100L, () -> { reads.incrementAndGet(); return lines("Panadol"); });
        assertSame(first, second);
        assertEquals(1, reads.get());
    }

    @Test
    @DisplayName("a write to the invoice evicts it — the next print reads the database again")
    void evict() {
        InvoiceLinesCache c = new InvoiceLinesCache(300);
        c.get(7L, 100L, () -> lines("old"));
        c.evict(7L, 100L);
        assertEquals("new", c.get(7L, 100L, () -> lines("new")).sales().get(0).getItemName());
    }

    @Test
    @DisplayName("a row with no business on it evicts the invoice under every business")
    void evictWithoutOrg() {
        InvoiceLinesCache c = new InvoiceLinesCache(300);
        c.get(7L, 100L, () -> lines("old"));
        c.evict(null, 100L);
        assertEquals("new", c.get(7L, 100L, () -> lines("new")).sales().get(0).getItemName());
    }

    @Test
    @DisplayName("a read that started before an eviction does not store its older lines")
    void raceWithAWrite() {
        InvoiceLinesCache c = new InvoiceLinesCache(300);
        // The loader is the slow read; the write lands (and evicts) while it is running.
        InvoiceLinesCache.Lines stale = c.get(7L, 100L, () -> { c.evict(7L, 100L); return lines("stale"); });
        assertEquals("stale", stale.sales().get(0).getItemName(), "the caller still gets its answer");
        assertEquals("fresh", c.get(7L, 100L, () -> lines("fresh")).sales().get(0).getItemName(), "but it was not cached");
    }

    @Test
    @DisplayName("an invoice with no lines is not cached; another business's invoice is a different entry")
    void emptyAndTenant() {
        InvoiceLinesCache c = new InvoiceLinesCache(300);
        c.get(7L, 100L, () -> new InvoiceLinesCache.Lines(List.of(), BigDecimal.ZERO));
        assertEquals("added", c.get(7L, 100L, () -> lines("added")).sales().get(0).getItemName());
        assertEquals("other", c.get(8L, 100L, () -> lines("other")).sales().get(0).getItemName());
    }

    @Test
    @DisplayName("TTL 0 switches it off: every print reads the database")
    void off() {
        InvoiceLinesCache c = new InvoiceLinesCache(0);
        AtomicInteger reads = new AtomicInteger();
        c.get(7L, 100L, () -> { reads.incrementAndGet(); return lines("a"); });
        c.get(7L, 100L, () -> { reads.incrementAndGet(); return lines("a"); });
        assertEquals(2, reads.get());
    }
}
