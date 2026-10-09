package com.myplus.marketplace.multiseller.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MKT-2e — one seller's scorecard over a window, computed from its parts (slice doc
 * {@code mkt-2e-merchant-performance.md}; source R20.3 "merchant performance", §18 "acceptance history").
 *
 * <p>Pure: the service reads one {@link Part} per seller order placed in the window and this class counts. Nothing is
 * stored, so the scorecard can never disagree with the orders it is made of.
 *
 * <h3>What counts</h3>
 * <ul>
 *   <li><b>Accepted</b>: ACCEPTED, HANDED_OVER, FAILED (it was accepted before it failed).</li>
 *   <li><b>Missed</b>: REJECTED or EXPIRED and the seller's doing: the shortage names the MERCHANT and stands
 *       (RECORDED or UPHELD), or there is no shortage record (a part from before MKT-2b).</li>
 *   <li><b>Not counted</b>: OFFERED / UNASSIGNED (not decided yet), CANCELLED (the customer or the payment ended it
 *       before the seller answered), a miss caused by someone else (supplier, platform, …), one the operator
 *       OVERTURNED, and one still DISPUTED: a record against a seller is not used while it is disputed (R12.4).
 *       The disputed ones are shown as their own number.</li>
 *   <li><b>On time</b>: an accepted part is due once its promise has passed or it was delivered; it is on time when
 *       delivered by acceptance + the longest promise of its lines, the same "expected by" the customer is shown.
 *       A part not delivered and past its promise is late.</li>
 * </ul>
 *
 * <p>A rate needs {@link #MIN_ORDERS} orders behind it; below that it is null ("not enough orders yet") and no flag is
 * raised. Flags are records for the operator, never a sanction (R12.4): nothing is stopped or charged by them.
 */
public final class SellerPerformance {

    /** Fewer decided (or due) parts than this and the rate is not shown, flagged or used for ranking. */
    public static final int MIN_ORDERS = 5;
    /** Below this share of orders accepted, the seller is flagged. */
    public static final double LOW_ACCEPTANCE = 0.80;
    /** Below this share of orders delivered on time, the seller is flagged. */
    public static final double LOW_ON_TIME = 0.90;

    public static final String FLAG_ACCEPTANCE = "LOW_ACCEPTANCE";
    public static final String FLAG_ON_TIME = "LATE_DELIVERY";
    public static final String FLAG_COD = "COD_OVERDUE";

    private SellerPerformance() {
    }

    /**
     * One seller order as the scorecard needs it. {@code promiseHours} is the longest promise of its lines (null when
     * none); {@code shortageParty}/{@code shortageStatus} are its MKT-2b record, null when there is none.
     */
    public record Part(Long sellerOrgId, String status, LocalDateTime createdAt, LocalDateTime decidedAt,
            LocalDateTime deliveredAt, Integer promiseHours, String shortageParty, String shortageStatus) {
    }

    /** The counts and the two rates (null below {@link #MIN_ORDERS}). */
    public record Score(int accepted, int missed, int disputed, int excused, Double acceptanceRate,
            Long avgMinutesToAccept, int due, int onTime, Double onTimeRate) {

        public List<String> flags(boolean codOverdue) {
            List<String> f = new ArrayList<>();
            if (acceptanceRate != null && acceptanceRate < LOW_ACCEPTANCE) f.add(FLAG_ACCEPTANCE);
            if (onTimeRate != null && onTimeRate < LOW_ON_TIME) f.add(FLAG_ON_TIME);
            if (codOverdue) f.add(FLAG_COD);
            return f;
        }
    }

    public static Score score(List<Part> parts, LocalDateTime now) {
        int accepted = 0, missed = 0, disputed = 0, excused = 0, due = 0, onTime = 0;
        long minutes = 0;
        int timed = 0;
        for (Part p : parts) {
            switch (p.status() == null ? "" : p.status()) {
                case "ACCEPTED", "HANDED_OVER", "FAILED" -> {
                    accepted++;
                    if (p.decidedAt() != null && p.createdAt() != null) {
                        minutes += Math.max(0, Duration.between(p.createdAt(), p.decidedAt()).toMinutes());
                        timed++;
                    }
                    LocalDateTime expected = expectedBy(p);
                    if (expected == null) break;
                    if (p.deliveredAt() != null) {
                        due++;
                        if (!p.deliveredAt().isAfter(expected)) onTime++;
                    } else if (now.isAfter(expected)) {
                        due++;                                  // past its promise and still not delivered: late
                    }
                }
                case "REJECTED", "EXPIRED" -> {
                    if (p.shortageParty() == null) missed++;    // before MKT-2b recorded a cause: the seller's
                    else if (!"MERCHANT".equals(p.shortageParty()) || "OVERTURNED".equals(p.shortageStatus())) excused++;
                    else if ("DISPUTED".equals(p.shortageStatus())) disputed++;
                    else missed++;
                }
                default -> {
                    // OFFERED, UNASSIGNED: not decided yet; CANCELLED: ended before the seller answered
                }
            }
        }
        int decided = accepted + missed;
        return new Score(accepted, missed, disputed, excused, decided < MIN_ORDERS ? null : (double) accepted / decided,
                timed == 0 ? null : minutes / timed, due, onTime, due < MIN_ORDERS ? null : (double) onTime / due);
    }

    /** The customer's "expected by": from acceptance (else placement) plus the longest promise. Null without a promise. */
    static LocalDateTime expectedBy(Part p) {
        if (p.promiseHours() == null || p.promiseHours() <= 0) return null;
        LocalDateTime from = p.decidedAt() != null ? p.decidedAt() : p.createdAt();
        return from == null ? null : from.plusHours(p.promiseHours());
    }
}
