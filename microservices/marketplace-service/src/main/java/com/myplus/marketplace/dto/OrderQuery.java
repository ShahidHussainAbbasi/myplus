package com.myplus.marketplace.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.ToString;

/**
 * OMS O4 — the back-office list filter, normalised once.
 *
 * <h3>Why this is a type and not five controller parameters</h3>
 * Every rule that keeps the list read safe lives here, so there is exactly one place they can be got wrong and
 * one place to test them ({@code OrderQueryTest}). A second entry point — an export, a future
 * {@code order-service} controller — gets the same clamping for free rather than re-deriving it.
 *
 * <h3>The size cap is the point</h3>
 * OMS-7 was an unbounded read: {@code findScoped} returned every order for the org. Pagination alone does not
 * fix that if the client chooses the page size, because {@code ?size=100000} is the unbounded read again with
 * extra steps. {@link #MAX_SIZE} is enforced here, server-side, and there is no way to ask for more.
 *
 * <h3>Blank is absent, not empty</h3>
 * A blank filter is dropped rather than matched. {@code status=""} must mean "no status filter"; matching it
 * literally would return nothing and look like an empty shop.
 */
@Getter
@ToString
public final class OrderQuery {

    /** Hard ceiling on rows per page. A cap the caller can raise is not a cap. */
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 25;

    private final int page;
    private final int size;
    private final String status;          // fulfilment status, exact
    private final String paymentStatus;   // PENDING | PAID | FAILED, exact
    private final String source;          // POS | STOREFRONT, exact
    private final LocalDateTime from;     // inclusive, start of day
    private final LocalDateTime to;       // inclusive, END of day — see below
    private final String q;               // free text over orderNo / invoiceNo / customerName / customerContact
    /** OMS O5c — only orders promised before today and not yet complete. */
    private final boolean lateOnly;
    /** OMS O7 D2 — only orders booked by this rep; null for everyone's. */
    private final Long bookedBy;

    private OrderQuery(int page, int size, String status, String paymentStatus, String source,
                       LocalDateTime from, LocalDateTime to, String q, boolean lateOnly, Long bookedBy) {
        this.bookedBy = bookedBy;
        this.page = page;
        this.size = size;
        this.status = status;
        this.paymentStatus = paymentStatus;
        this.source = source;
        this.from = from;
        this.to = to;
        this.q = q;
        this.lateOnly = lateOnly;
    }

    /**
     * Build from raw request parameters, clamping everything.
     *
     * @param to the LAST DAY to include. Widened to 23:59:59.999999 of that day, because {@code created_at} is a
     *           timestamp: comparing it against midnight would silently exclude everything that happened on the
     *           final day of the range, and "1st to 31st" returning nothing from the 31st is the kind of wrong
     *           answer an operator trusts.
     */
    public static OrderQuery of(Integer page, Integer size, String status, String paymentStatus, String source,
                                LocalDate from, LocalDate to, String q, boolean lateOnly) {
        return of(page, size, status, paymentStatus, source, from, to, q, lateOnly, null);
    }

    /** With the O7 D2 booker filter — {@code bookedBy} null means everyone's orders. */
    public static OrderQuery of(Integer page, Integer size, String status, String paymentStatus, String source,
                                LocalDate from, LocalDate to, String q, boolean lateOnly, Long bookedBy) {
        int p = (page == null || page < 0) ? 0 : page;                    // a negative page is page one, not an error
        int s = clampSize(size);
        return new OrderQuery(p, s,
                upper(status), upper(paymentStatus), upper(source),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.atTime(java.time.LocalTime.MAX),
                trimToNull(q), lateOnly, bookedBy);
    }

    /** Without the late filter — every caller that predates OMS O5c, and the plain unfiltered list. */
    public static OrderQuery of(Integer page, Integer size, String status, String paymentStatus, String source,
                                LocalDate from, LocalDate to, String q) {
        return of(page, size, status, paymentStatus, source, from, to, q, false);
    }

    /** Everything, at the default page size — the plain "open the Orders screen" case. */
    public static OrderQuery firstPage() {
        return of(0, DEFAULT_SIZE, null, null, null, null, null, null, false, null);
    }

    /**
     * The size cap, exposed so the other paged reads share this one rule rather than restating the number.
     *
     * <p>Extracted for the review's R5 fix: {@code /orders/backorders} needed the same clamp, and a second
     * {@code Math.min(size, 100)} somewhere else is how two endpoints end up with two different ceilings.
     * Absent, zero and negative all mean {@link #DEFAULT_SIZE}; anything above {@link #MAX_SIZE} is capped.
     */
    public static int clampSize(Integer size) {
        return (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public boolean hasText() {
        return q != null;
    }

    /** The text filter as a SQL LIKE pattern, lower-cased; {@code null} when no text was supplied. */
    public String likePattern() {
        return q == null ? null : "%" + q.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private static String upper(String v) {
        String t = trimToNull(v);
        return t == null ? null : t.toUpperCase(java.util.Locale.ROOT);
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
